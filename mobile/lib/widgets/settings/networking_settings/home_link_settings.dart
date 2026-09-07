import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/domain/models/settings_key.dart';
import 'package:immich_mobile/extensions/build_context_extensions.dart';
import 'package:immich_mobile/generated/translations.g.dart';
import 'package:immich_mobile/platform/home_link_api.g.dart';
import 'package:immich_mobile/providers/infrastructure/platform.provider.dart';
import 'package:immich_mobile/providers/infrastructure/settings.provider.dart';
import 'package:immich_mobile/providers/permission.provider.dart';
import 'package:immich_mobile/services/home_link.service.dart';
import 'package:immich_mobile/utils/url_helper.dart';
import 'package:immich_ui/immich_ui.dart';
import 'package:permission_handler/permission_handler.dart' as ph;

/// Settings → Networking → "Home Link": per-app WireGuard tunnel to home, used only when the home
/// network is out of reach, for the app on screen and for background backups.
class HomeLinkSettings extends HookConsumerWidget {
  const HomeLinkSettings({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final service = ref.watch(homeLinkServiceProvider);
    final config = useState<HomeLinkConfig?>(null);
    final status = useState<HomeLinkStatus?>(null);
    final busy = useState(false);
    final enabled = useState(false);
    final lanDirect = useState(true);
    final keepUp = useState(false);

    Future<void> refresh() async {
      status.value = await service.getStatus();
    }

    Future<void> load() async {
      final c = await service.getConfig();
      config.value = c;
      if (c != null) {
        enabled.value = c.enabled;
        lanDirect.value = c.lanDirect;
        keepUp.value = c.keepUpWhenAway;
      }
      await refresh();
    }

    useEffect(() {
      unawaited(load());
      final timer = Timer.periodic(const Duration(seconds: 2), (_) => unawaited(refresh()));
      return timer.cancel;
    }, const []);

    Future<void> save({
      bool? enabled,
      bool? lanDirect,
      bool? keepUpWhenAway,
      String? wireguardConfig,
      String? homeUrl,
    }) async {
      final c = config.value ?? HomeLinkConfig(enabled: false, lanDirect: true, keepUpWhenAway: false);
      final next = HomeLinkConfig(
        enabled: enabled ?? c.enabled,
        lanDirect: lanDirect ?? c.lanDirect,
        keepUpWhenAway: keepUpWhenAway ?? c.keepUpWhenAway,
        wireguardConfig: wireguardConfig ?? c.wireguardConfig,
        homeUrl: homeUrl ?? c.homeUrl,
      );
      busy.value = true;
      try {
        await service.setConfig(next);
        config.value = next;
        if (next.enabled) {
          // Immich's own Wi-Fi-name based URL switching would fight the tunnel: one URL, always.
          await ref.read(settingsProvider).write(SettingsKey.networkAutoEndpointSwitching, false);
          unawaited(service.ensureLink(holder: 'fg'));
        }
      } catch (e) {
        if (context.mounted) {
          context.showSnackBar(SnackBar(content: Text('$e'.replaceFirst('PlatformException(error, ', '').trimRight())));
        }
        await load();
      } finally {
        busy.value = false;
        await refresh();
      }
    }

    // First enable: needs config + home URL + VPN consent.
    useValueChanged<bool, void>(enabled.value, (_, _) async {
      final c = config.value;
      if (c == null || c.enabled == enabled.value) return;
      if (enabled.value) {
        if ((c.wireguardConfig ?? '').isEmpty || (c.homeUrl ?? '').isEmpty) {
          enabled.value = false;
          if (context.mounted) {
            context.showSnackBar(
              const SnackBar(content: Text('Add the WireGuard config and the home server URL first')),
            );
          }
          return;
        }
        final granted = await service.requestVpnPermission().catchError((_) => false);
        if (!granted) {
          enabled.value = false;
          if (context.mounted) {
            context.showSnackBar(const SnackBar(content: Text('VPN permission is needed for the tunnel')));
          }
          return;
        }
      }
      await save(enabled: enabled.value);
    });
    useValueChanged<bool, void>(lanDirect.value, (_, _) {
      if (config.value != null && config.value!.lanDirect != lanDirect.value)
        unawaited(save(lanDirect: lanDirect.value));
    });
    useValueChanged<bool, void>(keepUp.value, (_, _) {
      if (config.value != null && config.value!.keepUpWhenAway != keepUp.value) {
        unawaited(save(keepUpWhenAway: keepUp.value));
      }
    });

    Future<void> editWireguardConfig() async {
      final controller = TextEditingController(text: config.value?.wireguardConfig ?? '');
      final text = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('WireGuard config'),
          content: SizedBox(
            width: double.maxFinite,
            child: TextField(
              controller: controller,
              autofocus: true,
              maxLines: 14,
              minLines: 8,
              style: const TextStyle(fontFamily: 'GoogleSansCode', fontSize: 12),
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                hintText:
                    '[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = home.example:51820\nAllowedIPs = 192.168.1.0/24, 10.0.0.0/24',
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: Text(ctx.t.cancel.toUpperCase())),
            TextButton(onPressed: () => Navigator.pop(ctx, controller.text), child: Text(ctx.t.save.toUpperCase())),
          ],
        ),
      );
      if (text != null) await save(wireguardConfig: text.trim());
    }

    Future<void> scanConfig() async {
      try {
        final text = await service.scanQrCode();
        if (text != null && text.contains('[Interface]')) {
          await save(wireguardConfig: text.trim());
        } else if (text != null && context.mounted) {
          context.showSnackBar(const SnackBar(content: Text('That QR is not a WireGuard config')));
        }
      } catch (e) {
        if (context.mounted) context.showSnackBar(SnackBar(content: Text('Scan failed: $e')));
      }
    }

    Future<void> editHomeUrl() async {
      final controller = TextEditingController(text: config.value?.homeUrl ?? getServerUrl() ?? '');
      final url = await showDialog<String>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Home server URL'),
          content: ImmichURLInput(
            controller: controller,
            autofocus: true,
            keyboardAction: .done,
            hintText: 'http://192.168.1.10:2283',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: Text(ctx.t.cancel.toUpperCase())),
            TextButton(onPressed: () => Navigator.pop(ctx, controller.text), child: Text(ctx.t.save.toUpperCase())),
          ],
        ),
      );
      if (url != null) await save(homeUrl: url.trim());
    }

    Future<void> newKeyPair() async {
      final pair = (await service.generateKeyPair()).split('\n');
      if (!context.mounted) return;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('New key pair'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Add this public key as a peer on the home WireGuard server, then paste the private key into the config here.',
              ),
              const SizedBox(height: 12),
              SelectableText(
                'PublicKey = ${pair[1]}',
                style: const TextStyle(fontFamily: 'GoogleSansCode', fontSize: 12),
              ),
              const SizedBox(height: 8),
              SelectableText(
                'PrivateKey = ${pair[0]}',
                style: const TextStyle(fontFamily: 'GoogleSansCode', fontSize: 12),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Clipboard.setData(ClipboardData(text: pair[1])),
              child: const Text('COPY PUBLIC'),
            ),
            TextButton(onPressed: () => Navigator.pop(ctx), child: Text(ctx.t.close.toUpperCase())),
          ],
        ),
      );
    }

    final s = status.value;
    final c = config.value;
    final hasConfig = (c?.wireguardConfig ?? '').isNotEmpty;
    final mono = TextStyle(fontFamily: 'GoogleSansCode', fontSize: 13, color: context.primaryColor);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SettingGroupTitle(title: 'Home Link', icon: Icons.vpn_lock_outlined),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              borderRadius: const BorderRadius.all(Radius.circular(16)),
              side: BorderSide(color: context.colorScheme.surfaceContainerHighest, width: 1),
            ),
            child: ListTile(
              leading: _StateIcon(s?.state),
              title: Text(
                _title(s),
                style: TextStyle(fontSize: 14, color: context.primaryColor, fontWeight: FontWeight.w600),
              ),
              subtitle: Text(_subtitle(s), style: const TextStyle(fontSize: 12)),
              trailing:
                  s != null &&
                      (s.state == HomeLinkState.tunnel ||
                          s.state == HomeLinkState.error ||
                          s.state == HomeLinkState.idle)
                  ? IconButton(
                      tooltip: s.tunnelUp ? 'Disconnect' : 'Connect now',
                      icon: Icon(s.tunnelUp ? Icons.link_off : Icons.link),
                      onPressed: busy.value
                          ? null
                          : () async {
                              if (s.tunnelUp) {
                                await service.disconnect();
                              } else {
                                await service.ensureLink(holder: 'fg');
                              }
                              await refresh();
                            },
                    )
                  : null,
            ),
          ),
        ),
        SettingsSwitchListTile(
          valueNotifier: enabled,
          enabled: !busy.value,
          title: 'Use Home Link',
          subtitle: 'Only this app goes through the tunnel, and only when the home network is out of reach',
        ),
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 20),
          leading: const Icon(Icons.home_outlined),
          title: const Text('Home server URL'),
          subtitle: Text(
            c?.homeUrl ?? 'Not set — the server address on the home network',
            style: c?.homeUrl != null ? mono : null,
          ),
          onTap: busy.value ? null : editHomeUrl,
        ),
        ListTile(
          contentPadding: const EdgeInsets.symmetric(horizontal: 20),
          leading: const Icon(Icons.key_outlined),
          title: const Text('WireGuard config'),
          subtitle: Text(
            hasConfig
                ? 'Peer ${s?.endpoint ?? '…'}\nThis phone: ${s?.publicKey ?? '…'}'
                : 'Not set — paste the wg-quick config or scan its QR',
            style: hasConfig ? mono.copyWith(fontSize: 11) : null,
          ),
          isThreeLine: hasConfig,
          onTap: busy.value ? null : editWireguardConfig,
          trailing: IconButton(
            icon: const Icon(Icons.qr_code_scanner),
            tooltip: 'Scan QR',
            onPressed: busy.value ? null : scanConfig,
          ),
        ),
        SettingsSwitchListTile(
          valueNotifier: lanDirect,
          enabled: !busy.value,
          title: 'Direct on the home network',
          subtitle: 'Skip the tunnel whenever the server answers over Wi-Fi',
        ),
        SettingsSwitchListTile(
          valueNotifier: keepUp,
          enabled: !busy.value,
          title: 'Keep tunnel up when away (on charger)',
          subtitle: 'While charging, keep the tunnel up between uses for instant app start; on battery it always drops',
        ),
        const _BackgroundOnlyWhileChargingTile(),
        const _BatteryExemptionTile(),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: ImmichTextButton(
                  labelText: 'New key pair',
                  icon: Icons.autorenew,
                  variant: .ghost,
                  onPressed: newKeyPair,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ImmichTextButton(
                  labelText: 'Test',
                  icon: Icons.network_ping,
                  variant: .ghost,
                  onPressed: () async {
                    final ms = await service.probeHome();
                    if (!context.mounted) return;
                    context.showSnackBar(
                      SnackBar(content: Text(ms < 0 ? 'Home server did not answer' : 'Home server answered in $ms ms')),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
        if (s != null && !s.vpnPermissionGranted && enabled.value)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
            child: ImmichTextButton(
              labelText: 'Grant VPN permission',
              icon: Icons.security,
              onPressed: () async {
                await service.requestVpnPermission();
                await refresh();
              },
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
          child: Text(
            'Log in with the home server URL. At home the app talks to it directly; away, it brings up a WireGuard '
            'tunnel that carries only this app, for the time the app is on screen or a background backup runs.',
            style: context.textTheme.bodySmall?.copyWith(color: context.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }

  static String _title(HomeLinkStatus? s) => switch (s?.state) {
    null => 'Home Link',
    HomeLinkState.disabled => 'Home Link off',
    HomeLinkState.unconfigured => 'Not configured',
    HomeLinkState.idle => 'Idle',
    HomeLinkState.connecting => 'Connecting…',
    HomeLinkState.lan => 'Home network · direct',
    HomeLinkState.tunnel => 'Away · WireGuard tunnel',
    HomeLinkState.noNetwork => 'No network',
    HomeLinkState.error => 'Problem',
  };

  static String _subtitle(HomeLinkStatus? s) {
    if (s == null) return '…';
    final parts = <String>[];
    if (s.detail != null) parts.add(s.detail!);
    if (s.state == HomeLinkState.lan && s.homeNetworkName != null) parts.add('via ${s.homeNetworkName}');
    if (s.tunnelUp) {
      final hs = s.lastHandshakeMillis;
      if (hs != null && hs > 0) {
        final age = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(hs));
        parts.add('handshake ${age.inSeconds < 60 ? '${age.inSeconds}s' : '${age.inMinutes}m'} ago');
      } else {
        parts.add('no handshake yet');
      }
      parts.add('↓${_bytes(s.rxBytes)} ↑${_bytes(s.txBytes)}');
    }
    if (s.state == HomeLinkState.idle) parts.add('tunnel down, nothing needs it');
    if (s.holds.isNotEmpty) parts.add('held by ${s.holds.join(', ')}');
    return parts.isEmpty ? ' ' : parts.join(' · ');
  }

  static String _bytes(int? b) {
    if (b == null) return '–';
    if (b < 1024) return '$b B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(0)} KB';
    return '${(b / 1024 / 1024).toStringAsFixed(1)} MB';
  }
}

class _StateIcon extends StatelessWidget {
  const _StateIcon(this.state);

  final HomeLinkState? state;

  @override
  Widget build(BuildContext context) => switch (state) {
    HomeLinkState.lan => const Icon(Icons.home_rounded, color: Colors.green),
    HomeLinkState.tunnel => const Icon(Icons.vpn_lock_rounded, color: Colors.green),
    HomeLinkState.connecting => const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
    HomeLinkState.error => const Icon(Icons.error_rounded, color: Colors.red),
    HomeLinkState.noNetwork => const Icon(Icons.signal_wifi_off_rounded, color: Colors.orange),
    HomeLinkState.idle => const Icon(Icons.pause_circle_outline_rounded),
    _ => const Icon(Icons.circle_outlined),
  };
}

/// Same setting as Backup → "Charging": with it on, the background upload worker (and so the tunnel it
/// brings up) only runs while the phone is on the charger, and is stopped as soon as it is unplugged.
class _BackgroundOnlyWhileChargingTile extends ConsumerWidget {
  const _BackgroundOnlyWhileChargingTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final value = ref.watch(appConfigProvider.select((c) => c.backup.requireCharging));
    return Padding(
      padding: const EdgeInsets.only(left: 8.0),
      child: SettingListTile(
        title: 'Background backup only while charging',
        subtitle: 'Uploads and the tunnel run in the background only on the charger; plug in to trigger a backup',
        trailing: Switch(
          value: value,
          onChanged: (bool newValue) async {
            await ref.read(settingsProvider).write(SettingsKey.backupRequireCharging, newValue);
            unawaited(ref.read(backgroundWorkerFgServiceProvider).configure(requireCharging: newValue));
          },
        ),
      ),
    );
  }
}

/// Android only lets the background worker run as a foreground service, and start the WireGuard
/// VPN service from the background, when the app is exempt from battery optimisation. Without it a
/// background backup away from home cannot bring the tunnel up at all.
class _BatteryExemptionTile extends ConsumerWidget {
  const _BatteryExemptionTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(batteryOptimizationProvider).valueOrNull;
    if (status == null || status == ph.PermissionStatus.granted) return const SizedBox.shrink();
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20),
      leading: Icon(Icons.battery_alert_outlined, color: context.colorScheme.error),
      title: Text('Unrestricted battery needed', style: TextStyle(color: context.colorScheme.error)),
      subtitle: const Text(
        'Without it Android blocks the tunnel while the app is in the background, so backups on the charger away from home cannot run. Tap to allow.',
        style: TextStyle(fontSize: 12),
      ),
      onTap: () async {
        await ph.Permission.ignoreBatteryOptimizations.request();
        await ref.read(batteryOptimizationProvider.notifier).getBatteryOptimizationPermission();
      },
    );
  }
}

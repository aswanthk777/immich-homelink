import 'dart:async';
import 'dart:io';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/platform/home_link_api.g.dart';
import 'package:logging/logging.dart';

final homeLinkServiceProvider = Provider((_) => HomeLinkService(HomeLinkHostApi()));

/// Thin Dart face of the native Home Link engine (per-app WireGuard tunnel + home-network detection).
/// Android only; every call is a no-op elsewhere.
class HomeLinkService {
  final HomeLinkHostApi _api;
  final _log = Logger('HomeLinkService');

  HomeLinkService(this._api);

  bool get supported => Platform.isAndroid;

  Future<HomeLinkStatus?> getStatus() async {
    if (!supported) return null;
    try {
      return await _api.getStatus();
    } catch (e) {
      _log.warning('getStatus failed: $e');
      return null;
    }
  }

  Future<HomeLinkConfig?> getConfig() async {
    if (!supported) return null;
    try {
      return await _api.getConfig();
    } catch (e) {
      _log.warning('getConfig failed: $e');
      return null;
    }
  }

  /// Throws with a readable message when the WireGuard text does not parse.
  Future<void> setConfig(HomeLinkConfig config) => _api.setConfig(config);

  /// Make sure the server is reachable before talking to it. Never throws; returns null when
  /// Home Link is unavailable so callers just carry on with the plain network.
  Future<HomeLinkStatus?> ensureLink({String holder = 'app', Duration timeout = const Duration(seconds: 20)}) async {
    if (!supported) return null;
    try {
      final status = await _api.ensureLink(holder, timeout.inMilliseconds);
      if (status.state != HomeLinkState.disabled) {
        _log.info('link: ${status.state.name} ${status.detail ?? ''}');
      }
      return status;
    } catch (e) {
      _log.warning('ensureLink failed: $e');
      return null;
    }
  }

  Future<void> releaseLink([String holder = 'app']) async {
    if (!supported) return;
    try {
      await _api.releaseLink(holder);
    } catch (_) {}
  }

  Future<void> disconnect() => _api.disconnect();

  Future<bool> requestVpnPermission() => _api.requestVpnPermission();

  Future<String?> scanQrCode() => _api.scanQrCode();

  Future<String> generateKeyPair() => _api.generateKeyPair();

  Future<int> probeHome() => _api.probeHome();
}

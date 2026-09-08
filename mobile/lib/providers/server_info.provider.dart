import 'dart:async';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:immich_mobile/domain/models/user.model.dart';
import 'package:immich_mobile/models/server_info/server_config.model.dart';
import 'package:immich_mobile/models/server_info/server_disk_info.model.dart';
import 'package:immich_mobile/models/server_info/server_features.model.dart';
import 'package:immich_mobile/models/server_info/server_info.model.dart';
import 'package:immich_mobile/models/server_info/server_version.model.dart';
import 'package:immich_mobile/services/server_info.service.dart';
import 'package:immich_mobile/utils/semver.dart';
import 'package:logging/logging.dart';
import 'package:package_info_plus/package_info_plus.dart';

class ServerInfoNotifier extends StateNotifier<ServerInfo> {
  ServerInfoNotifier(this._serverInfoService)
    : super(
        const ServerInfo(
          serverVersion: ServerVersion(major: 0, minor: 0, patch: 0),
          serverFeatures: ServerFeatures(map: true, trash: true, oauthEnabled: false, passwordLogin: true),
          serverConfig: ServerConfig(
            trashDays: 30,
            oauthButtonText: '',
            externalDomain: '',
            mapLightStyleUrl: 'https://tiles.immich.cloud/v1/style/light.json',
            mapDarkStyleUrl: 'https://tiles.immich.cloud/v1/style/dark.json',
          ),
          serverDiskInfo: ServerDiskInfo(diskAvailable: "0", diskSize: "0", diskUse: "0", diskUsagePercentage: 0),
          versionStatus: VersionStatus.upToDate,
        ),
      );

  final ServerInfoService _serverInfoService;
  final _log = Logger("ServerInfoNotifier");

  Future<ServerInfo> getServerInfo() async {
    await getServerVersion();
    await getServerFeatures();
    await getServerConfig();
    return state;
  }

  Future<void> getServerVersion() async {
    try {
      final serverVersion = await _serverInfoService.getServerVersion();

      // using isClientOutOfDate since that will show to users regardless of if they are an admin
      if (serverVersion == null) {
        state = state.copyWith(versionStatus: VersionStatus.error);
        return;
      }

      await _checkServerVersionMismatch(serverVersion);
    } catch (e, stackTrace) {
      _log.severe("Failed to get server version", e, stackTrace);
      state = state.copyWith(versionStatus: VersionStatus.error);
      return;
    }
  }

  Future<void> _checkServerVersionMismatch(ServerVersion serverVersion, {ServerVersion? latestVersion}) async {
    state = state.copyWith(serverVersion: serverVersion, latestVersion: latestVersion ?? state.latestVersion);

    final packageInfo = await PackageInfo.fromPlatform();
    final SemVer clientVersion = SemVer.fromString(packageInfo.version);

    // Home Link builds come from upstream main, whose app version is a pre-release (x.y.0-rc.N) that
    // is ahead of every released server. A pre-release app must not flag a current stable server
    // as "out of date" just for being older than itself; only the server's own latest-release check
    // counts then. Stable app builds keep the upstream behaviour.
    final appIsPrerelease = clientVersion.prerelease != null;
    if ((!appIsPrerelease && serverVersion < clientVersion) || (latestVersion != null && serverVersion < latestVersion)) {
      state = state.copyWith(versionStatus: VersionStatus.serverOutOfDate);
      return;
    }

    if (clientVersion < serverVersion && clientVersion.differenceType(serverVersion) != SemVerType.patch) {
      state = state.copyWith(versionStatus: VersionStatus.clientOutOfDate);
      return;
    }

    state = state.copyWith(versionStatus: VersionStatus.upToDate);
  }

  void handleReleaseInfo(ServerVersion serverVersion, ServerVersion? latestVersion) {
    // Update local server version
    unawaited(_checkServerVersionMismatch(serverVersion, latestVersion: latestVersion));
  }

  Future<void> getServerFeatures() async {
    final serverFeatures = await _serverInfoService.getServerFeatures();
    if (serverFeatures == null) {
      return;
    }
    state = state.copyWith(serverFeatures: serverFeatures);
  }

  Future<void> getServerConfig() async {
    final serverConfig = await _serverInfoService.getServerConfig();
    if (serverConfig == null) {
      return;
    }
    state = state.copyWith(serverConfig: serverConfig);
  }
}

final serverInfoProvider = StateNotifierProvider<ServerInfoNotifier, ServerInfo>((ref) {
  return ServerInfoNotifier(ref.read(serverInfoServiceProvider));
});

final versionWarningPresentProvider = Provider.family<bool, UserDto?>((ref, user) {
  final serverInfo = ref.watch(serverInfoProvider);
  return switch (serverInfo.versionStatus) {
    VersionStatus.clientOutOfDate || VersionStatus.error => true,
    VersionStatus.serverOutOfDate => serverInfo.latestVersion != null && (user?.isAdmin ?? false),
    VersionStatus.upToDate => false,
  };
});

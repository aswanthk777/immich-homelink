import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/platform/home_link_api.g.dart',
    kotlinOut: 'android/app/src/main/kotlin/app/alextran/immich/homelink/HomeLink.g.kt',
    kotlinOptions: KotlinOptions(package: 'app.alextran.immich.homelink'),
    dartOptions: DartOptions(),
    dartPackageName: 'immich_mobile',
  ),
)
/// How the app currently reaches the home server.
enum HomeLinkState {
  /// Home Link is switched off; the app uses the network as-is.
  disabled,

  /// Enabled but no WireGuard config / home URL stored yet.
  unconfigured,

  /// Nothing holds the link open; the tunnel is down.
  idle,

  /// A link is being established.
  connecting,

  /// On the home network: talking to the server directly, tunnel down.
  lan,

  /// Away from home: talking to the server through the per-app WireGuard tunnel.
  tunnel,

  /// No usable network at all (airplane mode etc.).
  noNetwork,

  /// The last attempt failed; see [HomeLinkStatus.detail].
  error,
}

class HomeLinkStatus {
  const HomeLinkStatus({
    required this.state,
    required this.vpnPermissionGranted,
    required this.tunnelUp,
    required this.holds,
    this.detail,
    this.endpoint,
    this.publicKey,
    this.rxBytes,
    this.txBytes,
    this.lastHandshakeMillis,
    this.homeNetworkName,
  });

  final HomeLinkState state;
  final bool vpnPermissionGranted;
  final bool tunnelUp;

  /// Who is keeping the link open right now (e.g. "fg", "bg", "manual").
  final List<String> holds;
  final String? detail;

  /// WireGuard endpoint host:port of the home peer.
  final String? endpoint;

  /// This phone's WireGuard public key (derived from the stored config).
  final String? publicKey;
  final int? rxBytes;
  final int? txBytes;
  final int? lastHandshakeMillis;

  /// Interface / SSID description of the network we are bound to while on the LAN.
  final String? homeNetworkName;
}

class HomeLinkConfig {
  const HomeLinkConfig({
    required this.enabled,
    required this.lanDirect,
    required this.keepUpWhenAway,
    this.wireguardConfig,
    this.homeUrl,
  });

  final bool enabled;

  /// Standard wg-quick style config text ([Interface] / [Peer]).
  final String? wireguardConfig;

  /// Server base URL on the home network, e.g. http://192.168.1.10:2283.
  /// Used both to detect "home" and to verify the tunnel.
  final String? homeUrl;

  /// Skip the tunnel and talk directly when the home network is reachable.
  final bool lanDirect;

  /// Keep the per-app tunnel up while away even when nothing is using it
  /// (instant app start, costs a little battery).
  final bool keepUpWhenAway;
}

@HostApi()
abstract class HomeLinkHostApi {
  HomeLinkStatus getStatus();

  HomeLinkConfig getConfig();

  /// Validates and stores the config. Throws with a message if the WireGuard text does not parse.
  @async
  void setConfig(HomeLinkConfig config);

  /// Make sure the home server is reachable (LAN or tunnel). Returns the resulting status.
  /// Holds the link open under [holder] until [releaseLink] is called with the same name.
  @async
  HomeLinkStatus ensureLink(String holder, int timeoutMs);

  /// Drop a hold; the tunnel goes down after a short grace once nobody holds it.
  void releaseLink(String holder);

  /// Force the tunnel down now (holds are cleared).
  @async
  void disconnect();

  /// Shows the Android VPN consent dialog if needed. Returns true if granted.
  @async
  bool requestVpnPermission();

  /// Opens the camera QR scanner and returns the scanned text, or null if cancelled.
  @async
  String? scanQrCode();

  /// Generates a fresh WireGuard key pair and returns "private\npublic".
  String generateKeyPair();

  /// Best-effort probe of the home URL over the current link, returns round-trip millis or -1.
  @async
  int probeHome();
}

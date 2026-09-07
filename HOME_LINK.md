# Immich Home Link

**A fork of the [Immich](https://github.com/immich-app/immich) Android app with a built-in, per-app
[WireGuard®](https://www.wireguard.com/) tunnel to your home network.**

Your phone reaches your self-hosted Immich server from anywhere, but:

- **only this app** goes through the tunnel (Android per-app VPN, `includeApplication`), never the whole phone;
- the tunnel exists **only while it is needed**: while the app is on screen, or while a background backup runs;
- on the **home network** the app talks to the server directly and the tunnel stays down;
- **background backups run only while the phone is on the charger**, and plugging in is the trigger:
  photos taken during the day are uploaded when the phone goes on the charger at night, with the app closed;
- **zero cloud dependencies**: no relay, no coordination server, no push notifications, no accounts.
  A WireGuard peer on a box at home is all it needs.

Everything else is stock Immich. The server is untouched: any Immich server works.

> Not affiliated with or endorsed by the Immich project or by WireGuard. See [Credits and license](#credits-and-license).

---

## Contents

1. [Why](#why)
2. [How it behaves](#how-it-behaves)
3. [Setting it up](#setting-it-up)
   - [Server side: a WireGuard peer for the phone](#server-side-a-wireguard-peer-for-the-phone)
   - [Phone side](#phone-side)
4. [Building from source](#building-from-source)
5. [How it works inside](#how-it-works-inside)
6. [Android gotchas you should know](#android-gotchas-you-should-know)
7. [Troubleshooting](#troubleshooting)
8. [Security notes](#security-notes)
9. [What differs from upstream Immich](#what-differs-from-upstream-immich)
10. [Credits and license](#credits-and-license)

---

## Why

The usual ways to reach a home Immich server from outside are:

| Approach | Drawback |
|---|---|
| Expose the server on the internet (reverse proxy, port forward) | Attack surface on a box holding every photo you own. |
| Tailscale / ZeroTier / Cloudflare tunnel | A third-party coordination service, an account, and usually the whole phone routed through a VPN. |
| Always-on WireGuard on the phone | Every app on the phone goes through home; battery and latency cost; breaks captive portals; the tunnel is up even when nothing needs it. |

Home Link keeps the WireGuard model (a single UDP port on your router, keys you own) but moves the
tunnel *into the app* and makes it strictly on-demand. The phone's system VPN slot is only occupied
while Immich is actually talking to home.

## How it behaves

| Situation | What the engine does |
|---|---|
| Phone on the home Wi-Fi/Ethernet (server answers `/api/server/ping` over a local network) | Talks to the server directly. Tunnel stays down. If that Wi-Fi is not the phone's default route (home internet down), the app's sockets are bound to it so requests still go over the LAN. |
| App on screen, away from home | Brings up the **per-app** WireGuard tunnel. Keepalive 25 s while in use. |
| App left (backgrounded), on battery | 20 s grace, then tunnel down, even mid-transfer. Nothing of the app runs in the background on battery. |
| App left (backgrounded), on charger | 20 s grace, then tunnel down as soon as 10 s pass with no bytes moving (an upload still running finishes first). |
| Background backup | **Only while charging** (Backup → *Charging*, on by default in this build; also shown in the Home Link card). Plugging in is the trigger: a photo taken during the day is queued by Immich's media observer and uploaded when the phone next goes on the charger, no app open needed. The upload worker asks the engine for a link first (home network, else tunnel), releases it when done. Unplugging stops the upload and the tunnel at once and re-arms the trigger for the next charge. While the phone stays on the charger, Immich's hourly periodic check keeps it in sync. |
| Wi-Fi ↔ mobile change while linked | Debounced 2 s, re-evaluated. Leaving home → tunnel; the LAN path is then blocked for 3 min so a marginal Wi-Fi cannot flap. Arriving home → tunnel dropped. |
| "Keep tunnel up when away" (setting, off by default) | **On the charger only**: the tunnel stays up between uses with keepalive 0 (sends nothing) so the app opens instantly. On battery it is ignored. |

The server URL is the **same in both cases**: the LAN address (e.g. `http://192.168.1.10:2283`).
The tunnel routes the LAN subnet (`AllowedIPs`), so Immich's own Wi-Fi-name based URL switching is
turned off while Home Link is enabled. Log in with the LAN URL.

Immich's other backup rules still apply on top: on mobile data, uploads of photos/videos happen only
if cellular is allowed for them in Backup settings. The Home Link engine looks *through* the VPN
when it reports the transport, so "Wi-Fi only" sees the real Wi-Fi or cellular network, not the tunnel.

## Setting it up

### Server side: a WireGuard peer for the phone

You need one Linux box on your home LAN running WireGuard, reachable on one UDP port from the
internet (port-forward on the router, or a public IP / DDNS name). The Immich server itself needs
nothing: it just sees connections coming from the WireGuard box's LAN address.

**Option A — you already run a WireGuard server at home.** Add the phone as a peer. Its `AllowedIPs`
on the phone side must include the LAN subnet the Immich server lives in (e.g. `192.168.1.0/24`),
and the server must forward/NAT tunnel traffic onto the LAN. Get a wg-quick config or a QR for the phone.

**Option B — start from scratch (or keep it separate).** `mobile/scripts/homelink-server.sh` creates a
dedicated `wg1` interface for the app (default udp/51821, tunnel net 10.66.78.0/24, NAT to the LAN)
without touching any existing `wg0` or firewall rules:

```bash
sudo PUBLIC=home.example.com LAN_CIDR=192.168.1.0/24 ./homelink-server.sh init   # once
sudo ./homelink-server.sh add "My phone"                 # prints a wg-quick config and a QR code
sudo ./homelink-server.sh add "Mom" --pubkey <key>       # for a key pair generated on the phone
sudo ./homelink-server.sh list | remove <name> | status
```

Prefer keys generated **on the phone** (Home Link card → *New key pair*): the private key never
leaves the device; you only paste the public key into `add … --pubkey`.

### Phone side

1. Install the APK (see [Building](#building-from-source)). It replaces the Play Store Immich app
   only if signed with the same key, so treat it as a separate app: log out of the store version or uninstall it.
2. **Settings → Networking → Home Link**:
   - *Home server URL*: the server's LAN address, e.g. `http://192.168.1.10:2283`.
   - *WireGuard config*: paste the wg-quick text, or *Scan QR*.
   - Switch **Use Home Link** on and accept Android's VPN prompt (once).
   - If a red **Unrestricted battery needed** row is shown, tap it and allow. This is mandatory:
     without the battery-optimisation exemption Android forbids starting the VPN service from the
     background, so no backup away from home can ever run (see [gotchas](#android-gotchas-you-should-know)).
3. Log in with the same LAN URL. The card shows *Home network · direct* at home and *Tunnel* elsewhere,
   with a *Test* button that probes the server both ways.
4. **Settings → Backup**: enable background backup as usual. *Charging* is on by default in this build.

Family phones: repeat per phone, each with its own peer. On some vendors (MIUI and friends) `adb install`
is blocked; copy the APK to the phone and install it from the file manager instead.

## Building from source

Requirements: Flutter (the version pinned by upstream Immich, see `mobile/`), Android SDK with NDK,
Java 21, `openapi-generator-cli`, and a release keystore. Generated sources (`mobile/generated`,
`lib/platform`, `*.g.dart`, `*.g.kt`, freezed/drift output) are gitignored and must be regenerated:

```bash
cd open-api && bash ./bin/generate-dart-sdk.sh
cd ../mobile && flutter pub get
ls pigeon/*.dart | xargs -I{} dart run pigeon --input {}          # lib/platform + android *.g.kt
dart run easy_localization:generate -S ../i18n && dart run bin/generate_keys.dart
dart run drift_dev make-migrations && dart run build_runner build --delete-conflicting-outputs
flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

Release signing: put your keystore at `mobile/android/key.jks` and its details in
`mobile/android/key.properties` (both gitignored). **Back that keystore up**: a phone installed with
it can only be updated with it.

Debug the engine with `adb logcat -s HomeLink BackgroundWorker ChargeTrigger MediaObserver`.

## How it works inside

```
 Dart (Flutter)                                  Kotlin (Android)
 ─────────────────────────────                   ─────────────────────────────────────────────
 AuthService.setOpenApiServiceEndpoint()  ──►    HomeLinkApiImpl (pigeon host)
   ensureLink() before any server call             │
 HomeLinkSettings card (status, config, QR)        ▼
                                                 HomeLinkEngine  ── process-wide singleton
 Background worker (Flutter engine #2)             ├─ holds: "fg" (activity visible), "app" (Dart), "bg" (worker)
   goes through the same ensureLink()              ├─ LAN probe: bound sockets per candidate Wi-Fi/Ethernet network
                                                   ├─ tunnel up/down via wireguard-android GoBackend (per-app)
 ImmichApp lifecycle callbacks ──► foreground()    ├─ idle policy (battery vs charger), retry with backoff
 BackgroundWorker ──► acquire("bg") / release      ├─ connectivity callbacks (debounced), LAN flap guard
 ChargeTriggerWorker ──► enqueue upload worker     └─ ACTION_POWER_DISCONNECTED → idle policy right away
                                                 HomeLinkStore  ── private SharedPreferences (config text, URL, toggles)
```

**Holds.** The engine keeps the link alive as long as at least one holder wants it. The activity
lifecycle holds `fg` while any screen is visible; the Dart side holds `app` for the duration of a
call chain; the background worker holds `bg` for its whole run. When the last hold is released the
idle policy runs. Dart requests arriving while nothing is on screen and no worker runs are treated
as transient, and on battery they do not bring the tunnel up at all.

**Evaluation order** (`evaluate()`): 1) if a local network answers the server's ping → bind to it,
tunnel down, state `LAN`; 2) else if there is any internet network and VPN consent exists → tunnel up,
wait for the server to answer through it (state `TUNNEL`), otherwise tear it down and retry later
with exponential backoff (30 s → 5 min) while someone still wants it.

**Charging enforcement.** Immich's media-observer and hourly periodic jobs carry WorkManager's
`requiresCharging`, which Android's JobScheduler honours to *wake* the app. The upload worker itself
carries **no** WorkManager charging constraint (see gotcha 2) but checks `EXTRA_PLUGGED` on start
and listens for `ACTION_POWER_DISCONNECTED` while running. If it finds the phone on battery it arms
`ChargeTriggerWorker` (a JobScheduler `requiresCharging` job) and exits; that trigger fires at the
next plug-in and enqueues the upload worker again.

### Code map

| File | Role |
|---|---|
| `mobile/android/.../homelink/HomeLinkEngine.kt` | The state machine: holds, LAN probe, tunnel, idle policy, connectivity and power callbacks. |
| `mobile/android/.../homelink/HomeLinkStore.kt` | Persisted settings (private SharedPreferences). |
| `mobile/android/.../homelink/HomeLinkApiImpl.kt` | Pigeon host, VPN consent dialog, QR scanner (`PortraitCaptureActivity`). |
| `mobile/android/.../background/BackgroundWorker.kt` | Upstream worker + link acquisition, plugged-in check, unplug receiver. |
| `mobile/android/.../background/BackgroundWorkerApiImpl.kt` | Scheduling; `ChargeTriggerWorker`; stale-job handling on settings change. |
| `mobile/android/.../background/ChargeTriggerWorker.kt` | Plug-in wake-up job. |
| `mobile/android/.../connectivity/ConnectivityApiImpl.kt` | Reports the real transport (looks through the VPN). |
| `mobile/android/.../ImmichApp.kt` | Engine init and activity-visibility hold. |
| `mobile/pigeon/home_link_api.dart` | Dart ↔ Kotlin API definition (generated files are gitignored). |
| `mobile/lib/services/home_link.service.dart` | Dart wrapper; `AuthService` calls `ensureLink()` before setting the endpoint. |
| `mobile/lib/widgets/settings/networking_settings/home_link_settings.dart` | The settings card. |
| `mobile/scripts/homelink-server.sh` | Optional server-side helper (dedicated `wg1`, peers, QR). |
| `mobile/HOME_LINK.md` | Shorter engineering notes kept next to the code. |

## Android gotchas you should know

These cost a full afternoon on a OnePlus running Android 16; they apply to most modern phones.

1. **"Charging" is not "plugged in" for the scheduler.** Android's JobScheduler flags a device as
   charging only once the battery level has visibly risen for a while, or immediately at ≥ 90 %.
   On a slow USB port that took 16 minutes; on a wall charger from 83 % it took 5 minutes; at 90 %+
   it is instant. Since Android 8 no app can react to the plug event itself from the background
   (`ACTION_POWER_CONNECTED` cannot be declared in the manifest), so a few minutes of lag on a
   wall charger is the floor. Overnight charging does not care.
2. **Charge limits confuse WorkManager.** Phones that hold the battery at 80–90 % (OnePlus, Samsung
   "protect battery", Pixel "adaptive charging") report status *not charging* while plugged in.
   JobScheduler still counts that as charging and starts the job, but WorkManager's own in-process
   battery tracker disagrees and stops any *foreground* worker with a charging constraint within
   milliseconds of its promotion. That is why the upload worker enforces "plugged in" itself.
3. **The battery-optimisation exemption is mandatory.** Upstream Immich promotes its worker to a
   foreground service only when the app is exempt (`isIgnoringBatteryOptimizations`). Without a
   foreground service, `startService()` for the WireGuard `VpnService` from the background throws
   `BackgroundServiceStartNotAllowedException`. The Home Link card warns and offers the system dialog.
4. **Vendor log buffers are tiny.** The OnePlus ships a 256 KiB logcat ring buffer that overflows in
   seconds. Run `adb logcat -G 8M` before debugging.
5. **Per-app VPN and adb over Wi-Fi coexist.** The tunnel only captures the app's own traffic, so
   `adb tcpip 5555` keeps working while the phone moves to a wall charger, which is how the
   plug-in behaviour was verified.

## Troubleshooting

| Symptom | Look at |
|---|---|
| Card says *Home not reachable through the tunnel* | Handshake OK but the server does not answer: `AllowedIPs` on the phone must include the LAN; the WireGuard box must forward + NAT to the LAN; the *Home server URL* must be the LAN address. |
| *VPN permission not granted* | Switch *Use Home Link* off and on to get the consent dialog again. Some vendors also require enabling "Always-on"-style permissions in their VPN settings page. |
| Tunnel up but no handshake | Router port-forward, `PUBLIC` endpoint, or clock skew. `wg show` on the server shows the last handshake per peer. |
| Backups never run away from home | The battery exemption (red row in the card). Then Backup → *Charging* semantics (see gotcha 1). Then cellular rules in Backup settings. |
| Tunnel lingers on the charger | *Keep tunnel up when away* is on; that is by design on the charger. Turn it off for tunnel-only-during-backups. |
| Everything | `adb logcat -s HomeLink BackgroundWorker ChargeTrigger MediaObserver` tells the whole story, including why the engine took each decision. |

## Security notes

- The WireGuard private key and config live in the app's private storage (same protection as the
  Immich session token). Generate keys on the phone when you can so the private key never travels.
- Only the app's own traffic enters the tunnel. Nothing else on the phone can use it, and the tunnel
  does not change the phone's default route.
- The exposed surface at home is a single WireGuard UDP port. WireGuard is silent to unauthenticated
  packets. Your Immich server stays LAN-only.
- No telemetry, no third-party servers, no accounts beyond your own Immich login.

## What differs from upstream Immich

All changes are in `mobile/` (Android app). The server, web app and CLI are untouched. Touched
upstream files: `ImmichApp.kt`, `MainActivity.kt`, `BackgroundWorker.kt`, `BackgroundWorkerApiImpl.kt`,
`BackgroundWorkerPreferences.kt` (default *Charging* = on), `ConnectivityApiImpl.kt`, `AndroidManifest.xml`,
`build.gradle` / `libs.versions.toml` (wireguard-android, zxing), `auth.service.dart`,
`background_worker.service.dart`, `backup_config.dart` (default *Charging* = on), `networking_settings.dart`.
New files are listed in the [code map](#code-map). iOS is not supported (Android per-app VPN only).

## Credits and license

- **[Immich](https://github.com/immich-app/immich)** by Alex Tran, Jason Rasmussen and the Immich
  team and contributors. This project is a fork and would not exist without their work. Please
  consider [supporting them](https://immich.app/).
- **[WireGuard®](https://www.wireguard.com/)** by Jason A. Donenfeld, and the
  [wireguard-android](https://git.zx2c4.com/wireguard-android/) tunnel library (Apache License 2.0)
  that powers the in-app tunnel. *WireGuard* and the *WireGuard* logo are registered trademarks of
  Jason A. Donenfeld. This project is not affiliated with or endorsed by the WireGuard project.
- **[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)** (Apache License 2.0) for QR scanning.

Immich is licensed under the **GNU Affero General Public License v3.0**, and so is this fork, in its
entirety: see [LICENSE](LICENSE). If you distribute builds of it, the AGPL requires you to make the
corresponding source available.

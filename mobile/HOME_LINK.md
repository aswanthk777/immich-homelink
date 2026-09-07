# Home Link — engineering notes (Android)

User-facing documentation, setup and rationale live in the repository root: [HOME_LINK.md](../HOME_LINK.md). This file is the short version kept next to the code.

Goal: reach the home Immich server from anywhere **without putting the whole phone on a VPN**, and keep
background backups working with as little battery as possible.

## How it behaves
| Situation | What the engine does |
|---|---|
| Phone on the home Wi-Fi/Ethernet (server answers `/api/server/ping` over a local network) | Talks to the server directly. Tunnel stays down. If that Wi-Fi is not the phone's default route (home internet down), the process is bound to it so requests still go over the LAN. |
| App on screen, away from home | Brings up a **per-app** WireGuard tunnel (`includeApplication` = only Immich's traffic). Keepalive 25 s while in use. |
| App left (backgrounded), on battery | 20 s grace, then tunnel down, even mid-transfer. Nothing of ours runs in the background on battery. |
| App left (backgrounded), on charger | 20 s grace, then tunnel down as soon as 10 s pass with no bytes moving (so an upload still running finishes first). |
| Background backup (WorkManager) | **Only while charging** (Backup → *Charging*, on by default in this build, also shown in the Home Link card). Plugging in is the trigger: a photo taken during the day is queued by the media observer and uploaded when the phone next goes on the charger, no app open needed; a `ChargeTriggerWorker` (JobScheduler `requiresCharging`) is armed whenever an upload was skipped or cut short on battery. The upload worker itself checks that a charger is connected (else it arms the trigger and exits), and an `ACTION_POWER_DISCONNECTED` receiver stops it, tunnel included, the moment the phone is unplugged. Deliberately **no** WorkManager charging constraint on the upload worker: on phones with a charge limit (OnePlus holds at 90 % and reports "not charging") WorkManager's in-process tracker would kill the foreground worker although JobScheduler considers the phone charging. While it stays on the charger the hourly periodic worker keeps it in sync. Note Android flags "charging" for JobScheduler only after the level has risen a few percent (or at ≥90 %), so the first backup can lag the plug-in by a few minutes. The worker asks the engine for a link *before* starting the Flutter engine; LAN or tunnel, whichever works. No link → `Result.retry()` with WorkManager's backoff. Link released when the worker completes. |
| Wi-Fi ↔ mobile change while linked | Debounced 2 s, re-evaluated. Leaving home → tunnel; the LAN path is then blocked for 3 min so a marginal Wi-Fi cannot flap us. Arriving home → tunnel dropped. |
| "Keep tunnel up when away" (setting, off by default) | **On the charger only**: tunnel stays up when idle with keepalive 0, so nothing is sent. On battery it is ignored and the tunnel drops like above. |

The server URL is the **same in both cases** (the LAN address, e.g. `http://192.168.1.10:2283`): the
tunnel routes the LAN subnet (`AllowedIPs`), so Immich's own Wi-Fi-name based URL switching is turned
off when Home Link is enabled. Log in with the LAN URL.

## Code map
- `android/.../homelink/HomeLinkEngine.kt` — the state machine (holds, LAN probe with bound sockets, tunnel up/down, idle policy, connectivity callbacks). Process-wide singleton shared by the UI and background Flutter engines.
- `android/.../homelink/HomeLinkStore.kt` — persisted settings (private SharedPreferences: wg config text, home URL, toggles).
- `android/.../homelink/HomeLinkApiImpl.kt` — pigeon host + VPN consent dialog + QR scanner (zxing, `PortraitCaptureActivity`).
- `pigeon/home_link_api.dart` → `lib/platform/home_link_api.g.dart` + `HomeLink.g.kt` (generated, gitignored).
- `lib/services/home_link.service.dart` — Dart wrapper; `AuthService.setOpenApiServiceEndpoint()` calls `ensureLink()` first (splash, resume, background worker all go through it).
- `lib/widgets/settings/networking_settings/home_link_settings.dart` — Settings → Networking → *Home Link* card: status, toggles, paste/scan config, home URL, new key pair, test.
- `ImmichApp.kt` — activity lifecycle → foreground hold; `BackgroundWorker.kt` — background hold; `ConnectivityApiImpl.kt` — looks through the VPN so "Wi-Fi only" backup rules see the real transport.
- `scripts/homelink-server.sh` — optional helper for a dedicated `wg1` (udp/51821, 10.66.78.0/24, NAT to the LAN), `add`/`list`/`remove` peers, prints wg-quick config + QR. **Not used in the current setup**: the phone can simply be a peer of an existing home WireGuard server whose AllowedIPs already cover the LAN.

## Setting up a phone
1. Add the phone as a peer on the existing home WireGuard server (AllowedIPs must include the LAN, e.g. 192.168.1.0/24) and get its wg-quick config / QR. (`scripts/homelink-server.sh` exists if a separate `wg1` is ever wanted.)
2. In the app: Settings → Networking → Home Link → set *Home server URL* (LAN address), *WireGuard config* (paste or scan the QR), then switch *Use Home Link* on → accept the Android VPN prompt.
   Prefer keys made on the phone: *New key pair* → `add "Name" --pubkey <key>` on the WireGuard box → paste the printed config and fill in the private key.
3. Backup settings: allow "unrestricted battery" for the app (Immich already asks) — that is what lets the background worker run as a foreground service and start the VPN service from the background.

## Building
```
export PATH=$HOME/flutter-sdk/flutter/bin:$HOME/tools/bin:$PATH ANDROID_HOME=$HOME/Android/Sdk
cd open-api && bash ./bin/generate-dart-sdk.sh            # openapi-generator-cli = java wrapper in ~/tools/bin
cd ../mobile && flutter pub get
ls pigeon/*.dart | xargs -I{} dart run pigeon --input {}   # regenerates lib/platform + *.g.kt
dart run easy_localization:generate -S ../i18n && dart run bin/generate_keys.dart
dart run drift_dev make-migrations && dart run build_runner build --delete-conflicting-outputs
flutter build apk --release                               # signed with android/key.jks (key.properties)
adb install -r build/app/outputs/flutter-apk/app-release.apk
```
Debug the engine with `adb logcat -s HomeLink BackgroundWorker`.

## Server-side notes (Raspberry Pi running Immich in Docker)
- A Pi that runs **both ConnMan and NetworkManager**: ConnMan used to grab every
  Docker `veth` interface, give it a 169.254.x.x address and a `default dev vethX` route, which silently killed the Pi's
  internet (ML models could not download). Fixed 2026-09-07 with `NetworkInterfaceBlacklist=...,veth,docker,br-` in
  `/etc/connman/main.conf` plus `unmanaged-devices` in `/etc/NetworkManager/conf.d/10-docker-unmanaged.conf`.
  If the Pi loses internet after a container restart, check `ip route` for `veth` default routes first.
- ML sanity check from the Pi (downloads the model on first run):
  `docker exec immich_server curl -F 'entries={"clip":{"textual":{"modelName":"ViT-B-32__openai"}}}' -F text="a dog" http://immich-machine-learning:3003/predict`


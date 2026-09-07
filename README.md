<p align="center">
  <img src="design/immich-logo-stacked-light.svg" width="220" alt="Immich logo">
</p>
<h1 align="center">Immich Home Link</h1>
<h3 align="center">The Immich Android app with a built-in, per-app WireGuard® tunnel to your home</h3>

<p align="center">
  <a href="https://github.com/aswanthk777/immich-homelink/releases/latest"><img src="https://img.shields.io/github/v/release/aswanthk777/immich-homelink?style=for-the-badge&label=APK&color=3F51B5" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL_v3-blue.svg?style=for-the-badge&color=3F51B5" alt="AGPL v3"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Server-unchanged-success?style=for-the-badge" alt="Server unchanged">
</p>

<p align="center">
  <b>Reach your self-hosted Immich from anywhere.</b><br>
  No exposed server. No reverse proxy, domain or certificate. No Tailscale or ZeroTier account.<br>
  No always-on VPN eating the whole phone. Scan the WireGuard QR from your home box, and you're done.
</p>

> [!NOTE]
> This is a **community fork of Immich** that adds one thing: a per-app WireGuard tunnel to your home,
> with charger-gated background backups. Everything else is upstream Immich, and the server is not
> modified at all. Currently based on Immich `main` as of 2026-09-07 (mobile app 3.2.0-rc.0, tested
> against a v3.1.0 server). The `home-link` branch is rebased on upstream regularly to stay up to date.
> See [What this fork changes](#what-this-fork-changes) below.

---

## What this fork changes

Everything in the official Immich app is still here. On top of it, the Android app carries its own
WireGuard tunnel and a small engine that decides *when* to use it.

| | Stock Immich | Immich Home Link |
|---|---|---|
| Reaching home from outside | Expose the server, or run a VPN client on the phone | **Tunnel built into the app**, nothing else needed on the phone |
| Scope of the tunnel | Device-wide VPN, every app affected | **Only Immich's traffic** (Android per-app VPN) |
| When the tunnel is up | Always, or manually | **Only while the app is on screen or a backup runs**; down when idle |
| On the home Wi-Fi | Depends on your VPN setup | **Detected automatically**, talks to the server directly, tunnel stays down |
| Server URL | Separate local / remote URLs, Wi-Fi-name switching | **One URL**, the LAN address, works everywhere |
| Background backup away from home | Needs the VPN up in the background | **Runs only while charging**; plugging in is the trigger, app closed |
| Battery cost while idle | VPN keepalives all day | **Zero**: no tunnel, no keepalives, no background work on battery |
| Running another VPN app too | Impossible, one VPN at a time | **Coexists**: Home Link never takes the slot from another app, uses that VPN if it reaches home, and resumes after it's off |
| Services involved | Your choice of relay / coordination server | **None**. One WireGuard UDP port at home, silent to strangers |

Your Immich **server is untouched**. Any version works, Docker or not.

## How it behaves

| Situation | Home Link does |
|---|---|
| At home (server answers over the local network) | Direct connection. No tunnel. |
| App open, away from home | Per-app WireGuard tunnel up, keepalive while in use. |
| App closed, on battery | Tunnel down within 20 s. Nothing runs in the background. |
| App closed, on the charger | Photos taken during the day upload through the tunnel, then it drops. Unplug = stop. |
| Wi-Fi ↔ mobile data | Re-evaluated in 2 s; arriving home drops the tunnel, leaving home brings it up. |

Full behaviour table, internals and design notes: **[HOME_LINK.md](HOME_LINK.md)**.

## Quick start

**1. Install the app** from the [latest release](https://github.com/aswanthk777/immich-homelink/releases/latest)
(universal APK, Android 8+). It installs next to the Play Store app, not over it.

**2. Give your phone a WireGuard peer at home.** Any Linux box on the LAN works, a Raspberry Pi is plenty.
If you already run WireGuard, just add a peer whose `AllowedIPs` include your LAN. If not, the bundled
helper sets up a dedicated interface in one go and prints a QR code per phone:

```bash
sudo PUBLIC=home.example.com LAN_CIDR=192.168.1.0/24 ./mobile/scripts/homelink-server.sh init
sudo ./mobile/scripts/homelink-server.sh add "My phone"      # prints wg-quick config + QR
```

Forward **UDP 51821** on your router to that box. That is the only thing exposed, and WireGuard does
not answer anything that isn't your phone's key. Step-by-step WireGuard instructions, including keys
generated on the phone so the private key never leaves it: [HOME_LINK.md → WireGuard from scratch](HOME_LINK.md#wireguard-from-scratch-step-by-step).

**3. In the app:** Settings → Networking → **Home Link**
- *Home server URL*: the server's LAN address, e.g. `http://192.168.1.10:2283`
- *WireGuard config*: tap the QR icon and **scan** the code (or paste the text)
- Switch **Use Home Link** on, accept Android's VPN prompt, and allow **Unrestricted battery** when the card asks (required for background backups)

**4. Log in** with that same LAN URL. The card shows *Home network · direct* at home and *Tunnel* elsewhere. Done.

## What we learned about Android on the way

Two findings that also affect the official app's *Only while charging* option (documented with fixes in [HOME_LINK.md](HOME_LINK.md#android-gotchas-you-should-know)):

- Phones with a **charge limit** (OnePlus 90 %, Samsung "protect battery", Pixel adaptive charging) report *not charging* while plugged in. Android's scheduler starts the backup job anyway, but WorkManager's own battery tracker kills it milliseconds later. Home Link enforces "charger connected" itself instead.
- Without the **battery-optimisation exemption**, the upload worker never becomes a foreground service, and starting a VPN service from the background is then forbidden. Home Link warns and offers the system dialog.

## Building from source

```bash
cd open-api && bash ./bin/generate-dart-sdk.sh
cd ../mobile && flutter pub get
ls pigeon/*.dart | xargs -I{} dart run pigeon --input {}
dart run easy_localization:generate -S ../i18n && dart run bin/generate_keys.dart
dart run drift_dev make-migrations && dart run build_runner build --delete-conflicting-outputs
flutter build apk --release
```

Details, signing and debugging: [HOME_LINK.md → Building](HOME_LINK.md#building-from-source).

## Status

- Android only (per-app VPN is an Android feature). iOS is not planned.
- Tested end to end on a OnePlus running Android 16: LAN detection, tunnel, login through the tunnel, multi-GB backups over the tunnel, charger-triggered background backup with the app closed and swiped away, unplug behaviour.
- Reports from other devices and vendors are very welcome: open an issue with `adb logcat -s HomeLink BackgroundWorker ChargeTrigger`.
- The Immich team has declined to take this upstream ([immich-app/immich#31315](https://github.com/immich-app/immich/discussions/31315)), so it lives on as an independent community fork. Contributions here are welcome.

## Credits and license

Built on **[Immich](https://github.com/immich-app/immich)** by the Immich team and contributors, licensed under the
**GNU AGPL v3**, and so is this fork in its entirety ([LICENSE](LICENSE)). Please consider [supporting Immich](https://immich.app/).
The in-app tunnel uses the **[wireguard-android](https://git.zx2c4.com/wireguard-android/)** library (Apache 2.0).
*WireGuard* and the *WireGuard* logo are registered trademarks of Jason A. Donenfeld. QR scanning by
[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded) (Apache 2.0).
Not affiliated with or endorsed by the Immich or WireGuard projects.

The original Immich README, with the project's own documentation links, is kept at [README.upstream.md](README.upstream.md).

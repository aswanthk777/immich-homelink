# Changelog

All notable changes to the Home Link fork. Upstream Immich changes are not listed; the base is noted per release.

## Unreleased
- Base moved from upstream `main` (3.2.0-rc.0) to the **Immich v3.2.0** stable release; the branch now follows upstream release tags instead of `main`.
- CI: APKs built on every push and pull request, releases published from `homelink-v*` tags (universal, arm64-v8a, armeabi-v7a).
- Repository: issue templates (device report, bug), fork CONTRIBUTING, this changelog.

## v0.1.3 — 2026-09-09
- Backups on the charger no longer depend on Android's charging flag: a 15-minute plug check reads the cable state itself and starts the upload worker when nothing ran in the last 30 minutes.
- Opening the app cancels a running background backup (stock behaviour); when the app leaves the screen the charge trigger is re-armed 5 s later so the backup resumes right away.

## v0.1.2 — 2026-09-08
- No more false "Server update is available": a pre-release app build (the fork is built from upstream `main`, currently 3.2.0-rc.0) no longer flags a current stable server as out of date.

## v0.1.1 — 2026-09-08
- Coexists with other VPN apps: Home Link never takes Android's single VPN slot from another app, uses that VPN if it reaches the server, and resumes 45 s after it is gone. On OPLUS ROMs even `VpnService.prepare()` re-assigned the slot; that call is no longer made while a foreign VPN exists.
- README disclosure of AI-assisted development.

## v0.1.0 — 2026-09-07
- First release. Per-app WireGuard tunnel inside the Immich Android app, home-network detection, charger-gated background backup, settings card with QR scanning and on-phone key generation, `homelink-server.sh` helper. Base: Immich `main` as of 2026-09-07 (mobile 3.2.0-rc.0).

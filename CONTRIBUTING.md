# Contributing to Immich Home Link

This is a fork of [Immich](https://github.com/immich-app/immich). Everything outside the Home Link
feature is upstream code; please send fixes for the server, web app or stock mobile features to
Immich itself (their guide: [CONTRIBUTING.upstream.md](CONTRIBUTING.upstream.md)).

## What lives here

The Android app's per-app WireGuard engine and the charger-gated background backup. The code map is
in [HOME_LINK.md](HOME_LINK.md#code-map). Almost everything is under `mobile/android/.../homelink/`,
`mobile/android/.../background/`, `mobile/lib/services/home_link.service.dart` and the settings card.

## Reporting

- Works or doesn't on your phone: open a **Device report** issue. Both outcomes are useful; the
  README's tested-devices table is built from them.
- Broken behaviour: open a **Bug** issue with the log from
  `adb logcat -s HomeLink BackgroundWorker ChargeTrigger PlugCheck MediaObserver`.
  Strip keys, endpoints and IPs first.

## Changes

- Keep pull requests to one thing. Describe what you tested on which phone; the engine's behaviour
  can only be judged on real devices (charge limits, vendor ROMs and battery managers all differ).
- Build instructions: [HOME_LINK.md → Building](HOME_LINK.md#building-from-source). CI builds every
  push and PR and attaches the APKs as an artifact, so you can test a PR build on a phone.
- Upstream Immich declines pull requests generated with an LLM, and this fork has no better way to
  review code than they do. If you used an AI assistant, say so in the PR and be able to explain every
  line; PRs whose author cannot answer questions about them will be closed.
- Releases are tagged `homelink-vX.Y.Z` on the `home-link` branch. The branch is rebased on
  each upstream stable release tag (currently `v3.2.0`); do not base work on `main` here, it is a mirror.

## Scope

Things deliberately out of scope: iOS (per-app VPN on iOS needs an MDM-managed device, so it cannot
be done in a normal app), and re-implementing anything Immich upstream already provides.

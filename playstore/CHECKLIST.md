# OhmLauncher Google Play submission checklist

Applies only to the current `playRelease` artifact. Do not send for review, start rollout, or publish as part of this checklist without separate explicit authorization.

## 1. Exact artifact boundary

- [ ] Build the signed `playRelease` AAB with the intended version code.
- [ ] Inspect the merged Play manifest and generated AAB, not only source overlays.
- [ ] Confirm `QUERY_ALL_PACKAGES` is present only for the HOME-launcher app list/search/open function.
- [ ] Confirm `RECORD_AUDIO` is present only for optional on-device Visualizer/TTFX audio response.
- [ ] Confirm `SET_WALLPAPER` is present for selected/synced home backgrounds.
- [ ] Confirm `android:allowBackup="false"`.
- [ ] Confirm `android:usesCleartextTraffic="true"`; this is required for direct authenticated local HTTP pairing. Do not claim HTTPS or encrypted transport.
- [ ] Confirm app-specific storage only; no broad storage permissions.
- [ ] Confirm no screen capture/share, file transfer, clipboard service, notification listener, remote input, full accessibility service, or inbound LAN server is packaged or reachable.
- [ ] Confirm optional QR pairing requires a non-empty token and the phone only initiates requests to the paired computer.

## 2. Functional checks on the built Play artifact

- [ ] Install the artifact-derived APK on a clean device.
- [ ] Set OhmLauncher as Home and test app enumeration, search, launch, favorites, edge boxes, widgets, local plugins, terminal, bundled themes, and backgrounds.
- [ ] Test optional microphone denial and grant; verify audio-reactive visuals work locally and no audio is recorded or transmitted.
- [ ] Test background selection and Android wallpaper synchronization.
- [ ] Pair through a QR code on a trusted local network; verify authenticated theme/background sync and disconnect.
- [ ] Verify pairing traffic is direct local HTTP, not HTTPS; ensure all public text says it is authenticated but unencrypted.
- [ ] Verify no inbound listening server starts in the Play build.
- [ ] Enable the minimal accessibility service, press the visible Recents button, verify Recents, disable the service, and verify safe navigation fallback.

## 3. Play declarations

- [ ] Data safety matches `data-safety.md`: no developer collection/sharing and no encryption claim.
- [ ] Privacy policy URL serves the bilingual policy matching both copies in this repository.
- [ ] Ads: No. Advertising ID use: No.
- [ ] App access matches `reviewer-instructions.md`; no account or credentials required.
- [ ] Content rating and target audience match the final answers in `content-rating.md`.
- [ ] Complete the `QUERY_ALL_PACKAGES` declaration for core launcher functionality.
- [ ] Complete the AccessibilityService declaration with `accessibility-declaration.md`.
- [x] Upload the accessibility demonstration video and verify its link signed out: https://youtube.com/shorts/nYRgwM4ktSg (verified signed out 2026-09-22: playability OK, not private, 69 s). **Read back from the live Play Console declaration form on 2026-09-22: video URL present, "video meets requirements" checkbox checked, purpose = App functionality, sensitive data = No. The video was submitted with the original rejection — the missing piece was the store-listing description only.**
- [ ] Declare microphone use as optional, on-device Visualizer functionality; do not mark audio as collected.

## 4. Privacy and listing audit

- [ ] Privacy policy states the exact local pairing flow: QR address/token, phone-initiated direct authenticated HTTP, no encryption, no developer server.
- [ ] Privacy policy lists installed-app metadata, microphone behavior, pairing details, background/theme sync, wallpaper use, local retention/deletion, and excluded Play features.
- [ ] All 19 store listings pass title ≤30, short description ≤80, and full description ≤4000 characters.
- [ ] All 19 listings avoid claims that all settings/data stay solely on the phone; they accurately note optional direct theme/background sync.
- [ ] No listing claims screen sharing, file transfer, clipboard sync, notification access, remote input, full accessibility, broad storage, an inbound LAN server, HTTPS, or encrypted pairing.
- [ ] Release notes match the current feature set.

## 5. Store assets and release draft

- [ ] Verify icon, feature graphic, and at least two phone screenshots after save/reload.
- [ ] Verify screenshots show the Play edition and contain no private QR/token/address.
- [ ] Save and reload every one of the 19 localized listings.
- [ ] Attach exactly the verified AAB to the intended track and read back version name/code.
- [ ] Add intended countries/regions and testers if applicable, then verify after reload.
- [ ] Resolve every App content and Production review blocker.
- [ ] Stop at the completed draft. **Do not Send for review, roll out, or publish without explicit authorization.**

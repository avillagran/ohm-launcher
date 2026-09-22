# AccessibilityService declaration and video checklist — OhmLauncher

## Play Console declaration

**Purpose:** OhmLauncher is a HOME launcher with an optional compact navigation layout. Its minimal accessibility service lets the user open Android Recents by directly pressing OhmLauncher's visible Recents button when the normal Recents control is not available in that layout.

**Operation:** One deliberate user press calls only `GLOBAL_ACTION_RECENTS`. The service cannot perform gestures and cannot retrieve window content. It does not inspect apps, read text, monitor input, type, tap, swipe, automate tasks, run remote control, or expose any network command.

**Data use:** The service collects, stores, shares, or transmits no data.

**User choice:** The feature is optional. OhmLauncher opens Android's Accessibility settings and the user enables or disables the service there. The launcher remains usable without it and keeps ordinary system navigation available whenever the service is not enabled and bound.

## Declaration-form answers

- Is AccessibilityService a core function? **No; it supports one optional launcher navigation control.**
- Primary permitted purpose: **User interface/navigation assistance**.
- Does it collect or share personal or sensitive data? **No**.
- Does it perform autonomous actions? **No**.
- Does it initiate, plan, or execute actions without direct user input? **No**.
- Does it change settings or prevent uninstall/disable? **No**.
- Does it provide remote control? **No**.

## Demonstration video

Reviewer link: https://youtube.com/shorts/nYRgwM4ktSg

Recorded 2026-09-21 on the physical phone from the exact Play build. One continuous take covering the full enable/use/disable flow below; audited locally (full decode, contact sheet, key frames, privacy review) and verified signed out on 2026-09-22: `playabilityStatus` OK, `isPrivate` false, duration 69 s, video ID matches. Read back from the live Play Console declaration form on 2026-09-22: URL present with the acceptance checkbox checked — the video was part of the original (rejected) submission; the rejection was caused by the listing description only.

Checklist covered by the take:

- [x] Show the installed OhmLauncher app/version and that it is the active Home launcher.
- [x] Show the in-app explanation immediately before opening Accessibility settings.
- [x] Show the exact service name and Android's enable confirmation.
- [x] Return to OhmLauncher and show the visible Recents button.
- [x] Tap Recents once and show Android Recents opening immediately.
- [x] Return to OhmLauncher without demonstrating any unrelated accessibility action.
- [x] Disable the service in Android settings.
- [x] Return to OhmLauncher and show that ordinary system navigation remains available and the Recents feature does not pretend the service is active.
- [x] Keep QR codes, pairing tokens, IP addresses, notifications, account names, and other personal/device information out of frame or blur them.
- [x] Upload an accessible reviewer link and verify it works in a signed-out/incognito browser with no access request.

## Artifact evidence to retain

- [ ] Merged Play manifest contains only `OmarchyNavigationService`, not the full remote-input service.
- [ ] Service XML has `android:canPerformGestures="false"`.
- [ ] Service XML has `android:canRetrieveWindowContent="false"`.
- [ ] Code exposes only the Recents global action.
- [ ] On-device test confirms the action occurs only after the visible button press.
- [ ] Video behavior matches the declaration text and the exact uploaded AAB.

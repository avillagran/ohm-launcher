# Google Play Data safety — OhmLauncher

This declaration applies only to the signed `playRelease` AAB. Recheck the merged manifest and runtime behavior of the exact production artifact before every submission.

## Final Play Console answers

- Does the app collect or share any of the required user data types? **No**.
- Data collected: **No data types**.
- Data shared: **No data types**.
- Is all user data encrypted in transit? **Not applicable**. Do not select or claim encryption: optional Omarchy pairing uses authenticated, unencrypted HTTP directly between the phone and the user's computer on the local network.
- Can users request deletion? **Not applicable** because the developer does not collect or retain user data. Users can disconnect the paired computer, clear app storage, or uninstall the app to remove local settings and the saved pairing address/token.
- Independent security review: **No** unless a qualifying review is completed later.
- Account creation: **No**.

## Why the answer is “No data collected”

OhmLauncher has no ads, analytics, tracking, accounts, developer cloud, or developer-operated backend. Villagrán & Quiroz does not receive data from the app.

- Installed-app names, icons, package identifiers, and launch activities are read on the phone for launcher display, search, favorites, and opening apps.
- Optional microphone samples are analyzed transiently on the phone for audio-reactive TTFX visuals. They are not recorded, saved, or sent.
- Launcher preferences, favorites, widgets, local plugins, terminal settings, and pairing details are stored in app-specific storage.
- Optional Omarchy pairing is initiated by the user from a QR/deep link. The phone contacts only the computer address in that link, authenticates each request with the pairing token, sends basic connection details (the phone's local address, the app port value, and device model) and theme/background choices, and receives theme settings and background images. The traffic is direct on the local network and does not pass through the developer. It is not encrypted, so users should pair only with a computer they control on a trusted local network.
- A received background can be applied to the Android home screen with `SET_WALLPAPER`.

## Exact Play artifact boundary

The production artifact includes:

- `QUERY_ALL_PACKAGES`, solely because showing and launching installed apps is the core HOME-launcher purpose.
- `RECORD_AUDIO`, solely for optional on-device audio-reactive visuals.
- `SET_WALLPAPER`, to apply the selected or synced background to the Android home screen.
- A minimal AccessibilityService that can only invoke Android Recents after the user presses the launcher's Recents button. It cannot read window content, perform gestures, type, tap, swipe, or accept network commands.
- Outbound authenticated Omarchy pairing and theme/background synchronization over direct, unencrypted local HTTP.

The Play artifact excludes screen sharing/capture, file transfer, clipboard synchronization, notification access, remote input, full accessibility control, broad/all-files storage access, and an inbound local-network server.

## Artifact verification requirements

Require all of the following before submission:

- `android:allowBackup="false"`.
- `android:usesCleartextTraffic="true"` remains documented; it is required for direct local HTTP pairing and must never be described as HTTPS or encrypted transport.
- No `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, or `WRITE_EXTERNAL_STORAGE` in the merged Play manifest.
- No `NotificationListenerService`, MediaProjection service, clipboard service, remote-input accessibility service, or inbound LAN server in the Play artifact.
- `OmarchyNavigationService` has `canPerformGestures="false"` and `canRetrieveWindowContent="false"`.

# Google Play Data safety — OhmLauncher

This declaration applies only to the signed `playRelease` AAB. Verify it against the exact production AAB before each submission.

## Product declarations

- Ads: No.
- Analytics: No.
- User accounts: No.
- Developer-operated backend: No.
- Data collected: No.
- Data shared: No.
- Data sold: No.
- Installed-app metadata: processed locally for launcher display, search, favorites, and app opening; never transmitted.
- Microphone samples: optional, transient, processed locally for audio-reactive TTFX; never recorded, retained, or transmitted.
- User files and launcher configuration: stored in app-specific storage; never transmitted.
- Data encrypted in transit: Not applicable because this edition does not transmit user data.
- Data deletion request: Not applicable because the developer does not collect or retain user data. Users delete local data by clearing app storage or uninstalling.

## Artifact requirements

The production manifest must retain these properties:

- `android:usesCleartextTraffic="false"`
- `android:allowBackup="false"`
- No `MANAGE_EXTERNAL_STORAGE`
- No AccessibilityService
- No NotificationListenerService
- No MediaProjection or clipboard foreground service
- No Bluetooth/LAN pairing UI or active local API server

`QUERY_ALL_PACKAGES` remains because enumerating installed launchable applications is the core purpose of a HOME launcher. Package metadata is processed only on-device.

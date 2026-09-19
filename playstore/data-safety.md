# Google Play Data safety — OhmLauncher

This document describes the current no-ads build. Verify it again against the exact production AAB before submission.

## Product declarations

- Ads: No.
- Analytics: No.
- User accounts: No.
- Developer-operated backend: No.
- Data sold: No.
- Data shared with advertisers, analytics providers, data brokers, or other third parties: No.
- On-device microphone processing: not collected; samples are transient and are neither recorded nor transmitted.
- Installed-app metadata: processed locally for launcher display, search, and opening apps; not transmitted.
- Notification state: processed locally for badge counts; notification text is not stored or transmitted.

## Optional peer transfers

Google Play can treat data transmitted off the phone as collected even when it goes directly to the user's own paired computer. Declare the following as optional collection for App functionality if the production build can transfer them:

- Photos: user-selected photo transfer or backup.
- Files and documents: user-selected file transfer.
- Other personal information: clipboard text, when no more precise category exists.
- Other user-generated content: screen content or clipboard content where applicable.
- Device or other IDs: pairing identifier and local-network address used for peer discovery, authentication, and connection.

These categories are not shared with the developer or third parties. Copies may remain on the user's paired computer until the user deletes them.

## Screen sharing

The form has no single screen-content category. Select every category the release can expose while screen sharing. Do not select microphone audio unless the release actually sends audio with the screen.

## Security and deletion

Do not claim that all data is encrypted in transit until every production peer transfer has been verified as encrypted. OhmLauncher has no backend deletion endpoint because the developer stores no user content. Users delete data by clearing app storage or uninstalling, revoking permissions, removing pairings, and deleting transferred copies from their own computer.

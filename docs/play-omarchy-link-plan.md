# Google Play Omarchy Link expansion plan

This document defines the widest Omarchy Link feature set intended for the Google Play build without weakening the full GitHub distribution.

## Distribution boundary

### Google Play build

Allowed:

- Authenticated QR pairing with an Omarchy computer.
- Visible connection state, disconnect, local revoke, expiry, and token rotation.
- Outbound theme and background synchronization.
- Explicit one-shot clipboard send and receive with preview.
- File and photo transfer only for documents selected through Android's Storage Access Framework or Photo Picker.
- User-initiated screen sharing through MediaProjection, with fresh system consent, a foreground notification, and stop controls on the phone and desktop.
- Bounded Omarchy messages delivered inside OhmLauncher.
- Local notification badge counts only if the optional Notification Access declaration passes review; notification contents must not leave the phone.

Excluded:

- AccessibilityService remote input or general remote control.
- Continuous background clipboard monitoring.
- Notification title, text, action, or content mirroring.
- Broad storage access or arbitrary filesystem browsing.
- Shell commands, package installation, widget injection, settings mutation, or developer administration over LAN.
- Bluetooth discovery, silent capture, always-on LAN listeners, wildcard CORS, and unauthenticated HTTP or WebSocket operations.

### Full GitHub build

The direct GitHub build may retain advanced remote-control and administration features. It must still authenticate every LAN request. Omarchy Link and both project READMEs must explain the distribution split and link to the full launcher repository.

## Security architecture

1. Treat the QR token as a short-lived, single-use pairing secret, not a permanent bearer credential.
2. Exchange it for a session containing a random session ID, independent credentials in each direction, granted capabilities, issue time, idle expiry, and absolute expiry.
3. Store Android credentials with Android Keystore-backed encryption. Persist only non-secret peer metadata in launcher JSON settings.
4. Authenticate before reading request bodies or upgrading WebSockets. Use constant-time credential comparison.
5. Remove wildcard CORS from peer endpoints. Browser origins are not clients of the peer protocol.
6. Separate the authenticated peer API from the localhost-only developer API. The Play artifact must not contain a route from the network listener to command, package, widget, shell, or unrestricted file operations.
7. Encrypt sensitive payloads in transit. Screen frames, clipboard contents, and selected files cannot ship in the Play build over raw LAN HTTP.
8. Bind the peer listener only while a visible approved session requires it. Revocation or expiry closes sockets, stops capture, clears frames and temporary files, and removes credentials.
9. Apply strict payload, MIME, filename, frequency, concurrency, and timeout limits.

## Implementation slices

### Slice 1 — protocol hardening

- Add mandatory optional session authentication to `LocalApiServer` before body parsing and WebSocket upgrade.
- Authenticate every remote desktop endpoint, including clipboard PUT.
- Remove shell interpolation from desktop file downloads.
- Add route policies that physically deny developer/admin and unsupported capability routes.
- Add expiry and revocation models with deterministic unit tests.

Acceptance tests:

- Every protected REST route returns 401 without a valid credential.
- WebSocket upgrade returns 401 without a valid credential.
- Oversized unauthorized bodies are not read.
- Revocation closes active sessions and rejects subsequent requests.
- Play route policy cannot dispatch command, binary, widget, input, broad-file, photo-backup, or clipboard-monitor operations.

### Slice 2 — Play connection UX

- Replace the non-decoding camera action with a functional QR flow or continue through a verified system-camera HTTPS/HTTP bridge.
- Show peer identity and requested capabilities before approval.
- Add connected-device details, granted capabilities, last activity, expiry, Disconnect, Revoke, and Revoke all.
- Start only the restricted peer transport required by approved capabilities.

Acceptance tests:

- A missing, malformed, reused, or expired pairing secret is rejected.
- The user can deny pairing without persistent state.
- Disconnect and revoke remove secrets and stop services.
- Restored state never starts a privileged operation without a new user action.

### Slice 3 — one-shot transfers

- Add manual clipboard Send and Receive actions; do not start `ClipboardMonitorService` in Play.
- Use SAF and Photo Picker URIs for every Play file/photo transfer.
- Stage selected content in private temporary storage, authorize one object per session, stream it with limits, and delete it on completion, cancellation, expiry, or revoke.
- Add transfer preview, explicit destination, progress, cancel, and completion state.

Acceptance tests:

- Play cannot enumerate `/sdcard`, DCIM, or Pictures.
- Unselected URIs and expired object grants are rejected.
- Clipboard is read only after a foreground user action.
- Cancellation removes temporary data.

### Slice 4 — MediaProjection screen sharing

- Restore only `FOREGROUND_SERVICE_MEDIA_PROJECTION` and `ScreenCaptureService` in the Play manifest.
- Require an authenticated session, prominent in-app disclosure, foreground user action, and fresh Android MediaProjection consent for each share.
- Make the service non-sticky and add a Stop notification action.
- Stop on disconnect, revoke, expiry, projection callback, or user action.
- Encrypt frame delivery and never combine it with remote input in Play.

Acceptance tests:

- A remote request cannot silently launch consent or mark sharing active.
- Denial produces a visible inactive state.
- The foreground notification and both stop controls end capture.
- The merged Play manifest contains MediaProjection but no AccessibilityService.

### Slice 5 — Play metadata and review evidence

Update:

- `playstore/data-safety.md`
- `playstore/content-rating.md`
- `playstore/legal/privacy-policy.html`
- `docs/privacy-policy.html`
- all 19 localized files under `playstore/store-listing/`
- `playstore/CHECKLIST.md`
- App access instructions
- Foreground service declaration
- Data safety answers
- reviewer videos

The public listing must describe user benefits, not policy or implementation details. Policy mechanics belong only in declarations, reviewer instructions, and the privacy policy.

## Release gates

- Keep public `versionName` at `0.0.4` unless explicitly changed by the user.
- Increment only `versionCode` for every replacement AAB consumed by Play.
- Run the complete JVM and Python suites, merged-manifest audit, APK/AAB builds, signing verification, and real peer tests.
- Verify QR pairing and every admitted feature on a physical phone and Omarchy computer.
- Update Play drafts only after the implementation and declarations match.
- Never send for review, roll out, or publish without explicit user authorization.

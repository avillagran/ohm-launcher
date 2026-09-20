# Google Play reviewer instructions — OhmLauncher

No account, login, payment, or developer credential is required.

## Core review

1. Install and open OhmLauncher.
2. If Android asks for a default Home app, select OhmLauncher. This can be changed later in Android settings.
3. Verify app search and launch, favorites, edge boxes, Android widgets, local plugins, bundled themes/backgrounds, the Quake-style terminal, and optional TTFX visuals.
4. To test audio-reactive visuals, enable the audio-reactive option and grant microphone permission. Audio is analyzed only on the phone and is not recorded or sent.
5. Select a background and verify that it is also applied as the Android home wallpaper. This uses `SET_WALLPAPER`.

## Optional Omarchy computer pairing

Pairing is optional and core review does not depend on it.

1. Put the Android device and a computer running the included Omarchy Link companion on the same trusted local network.
2. Display the companion's pairing QR code and scan/open it on the phone.
3. The QR link contains the computer's local address and a pairing token. The phone initiates direct authenticated HTTP requests to that computer. The connection is local and authenticated but not encrypted; it is not HTTPS and does not use a developer server.
4. Verify that the phone can receive the computer's current theme and background and can request a listed theme/background choice.
5. Disconnect the peer from OhmLauncher when finished.

The Play edition does not start an inbound LAN server and does not provide screen sharing, file transfer, clipboard synchronization, notification access, or remote input.

## Recents accessibility review

1. Open OhmLauncher settings and choose the control that opens Android Accessibility settings for the Omarchy navigation service.
2. Enable **OhmLauncher navigation** and return to OhmLauncher.
3. Press the launcher's visible **Recents** button once.
4. Verify that Android Recents opens only in direct response to that press.
5. Return to Accessibility settings and disable the service; the launcher keeps system navigation available when the service is unavailable.

The service does not read screen/window content, observe user input, type, tap, swipe, perform arbitrary gestures, collect data, or accept a remote/network command. It only requests Android's global Recents action after the user presses the matching button.

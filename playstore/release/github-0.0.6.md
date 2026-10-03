OhmLauncher 0.0.6 adds foreground Flux integration to the full GitHub edition and improves theme/background selection and persistence across editions.

### Full GitHub edition

- Pair with Flux using mutual TLS, retained certificate pins and explicit local confirmation. Native Flux and the previously supported legacy protocol are selected explicitly, with no automatic downgrade.
- Discover and connect in both directions while Ohm is open. Session replacement, cancellation, Home recreation and secondary launcher tasks retain clear ownership of pending operations.
- Choose an Omarchy theme to apply its palette and background on both devices. Choose a background to change the background on both devices while keeping the palette.
- Keep Background’s theme-style card carousel: the active album’s images, followed by Custom. Only Custom opens the file picker. Original images are validated and synchronized independently of thumbnails.
- Share explicit text, URLs, a clipboard snapshot, battery, one selected notification or file; accept incoming shares and files through foreground confirmation.
- Control desktop media after consent and mirror the phone through a fresh Android screen-capture prompt, with reliable Stop controls across launcher tasks.
- Use a desktop touchpad/keyboard only after desktop Approve and secure Android credential confirmation. Temporary permission expires and leaves the saved desktop remote-input setting unchanged.

Theme/background selection and temporary input require the negotiated Ohm desktop extensions described in `docs/flux-interoperability.md`. Notification shells must expose explicit Approve/Deny actions.

### Shared launcher and Google Play edition

- Backgrounds retain the same card presentation as Themes, with a final Custom option for file selection.
- More reliable persistence of themes, wallpapers and favorites, including concurrent settings updates.
- The Play edition retains optional authenticated Omarchy Link theme/background synchronization. Its local HTTP transport is unencrypted. Flux, text sharing, file transfers, mirroring and remote input remain exclusive to the full edition.

Install the full edition with `OhmLauncher-0.0.6-full.apk`. Google Play uses a separate signed bundle and release notes. Updates preserve app settings and existing pairing.

Validation: 782 JVM tests passed with no failures or skips, including real native/legacy Go ↔ Kotlin mutual-TLS sessions in both directions. Full lint passed for both editions with no errors. Signed artifact, manifest and 16 KB alignment checks are recorded in [the validation report](https://github.com/avillagran/ohm-launcher/blob/0.0.6/docs/releases/0.0.6-validation.md).

# OhmLauncher Play upload signing

The Google Play upload key was generated specifically for `cl.villagranquiroz.ohm_launcher` under the Villagrán & Quiroz identity.

Local files (never commit them):

- `app/upload-keystore.jks`
- `key.properties`

Protected local backup:

- `~/.local/share/ohmlauncher-signing/upload-keystore.jks`
- `~/.local/share/ohmlauncher-signing/key.properties`

Alias: `upload`

Certificate fingerprints:

- SHA-1: `6F:03:0E:5E:23:7F:EF:67:4F:49:64:01:22:1F:3F:DB:16:76:26:A4`
- SHA-256: `5C:A3:83:D6:CF:8E:B6:45:8C:47:27:27:47:73:AF:3E:D5:9F:F7:CB:33:BC:F5:A3:DD:4B:3A:92:7B:77:41:40`

Back up both secret files in a secure offline location. Losing the upload key requires a Play Console upload-key reset before another update can be submitted.

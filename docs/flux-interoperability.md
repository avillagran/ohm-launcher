# Flux interoperability

OhmLauncher’s full GitHub edition implements an independent Flux LAN client.
Flux and Omarchy Link have separate discovery, identities, trust and capabilities.
The Google Play edition does not advertise or expose Flux. Omarchy Link’s optional
local HTTP theme/background synchronization remains independent of Flux mutual TLS.

## Supported flows

- Foreground discovery and control connections in both directions, with one
  process-owned phone listener. Home takes priority over secondary launcher tasks.
- Mutual TLS, persisted certificate pins and consistent plaintext/secured identity.
  Replaced certificates are refused. The phone’s software identity is encrypted
  with an Android Keystore wrapping key and retained across updates.
- Explicit pairing with a timestamp-bound comparison key and local confirmation.
  A single monotonic reply deadline includes partial frames; cancellation and
  socket closure run outside the UI thread with bounded admission/deadlines.
- Explicit text, HTTP(S) URLs, one clipboard snapshot, ping, battery, one selected
  notification and one selected file. Notification access requires additional
  confirmation; secret, local-only, ongoing and summary notifications are excluded.
- Incoming text/URLs on the current paired link, shown for explicit copying/opening.
  Incoming files require foreground acceptance, a pinned payload connection and
  a Storage Access Framework destination. Reverse tunnels and direct ports have
  separate offer, transport and provider deadlines.
- Desktop media with session consent, player selection, playback controls, seek
  and volume where supported. Protocol positions remain milliseconds; commands
  refresh actual player state instead of assuming a successful TLS write is an effect.
- Phone screen mirroring after fresh Android MediaProjection consent, with a
  visible foreground service, capture-specific Stop action and pinned H.264 channel.
  A process-wide Stop row works from a secondary launcher task.
- Omarchy palette selection, original wallpaper synchronization, a theme album
  selector and explicitly approved desktop touchpad/keyboard access.

The Activity owns these connections. Disconnect, replacement, unpair and Activity
teardown revoke session-owned consent, dialogs, pending operations and queued effects.
No automatic clipboard or notification monitoring is enabled.

## Desktop extensions

The upstream Flux palette-only `flux.theme` contract remains intact. Ohm uses
independently negotiated extensions:

| Packet | Direction | Contract |
| --- | --- | --- |
| `flux.omarchy_theme` | Desktop → Ohm | v1 bounded catalog: current ID, installed IDs, labels and palettes |
| `flux.omarchy_theme.select` | Ohm → desktop | v1 canonical UUID-v4 request and installed theme ID |
| `flux.omarchy_theme.selected` | Desktop → Ohm | v1 correlated boolean result and current ID |
| `flux.wallpaper.v2` | Both | Original state/transfers/ACKs plus correlated album requests/selections |
| `flux.input.request.v2` | Ohm → desktop | Exact boolean request and canonical UUID-v4 request ID |
| `flux.input` | Desktop → Ohm | Exact boolean grant and matching request ID |

The catalog is bounded to 64 themes and 48 colors per palette, within a 64 KiB
control frame. Active palettes come from canonical Omarchy state; installed
palettes honor user overlays. The daemon watches changes every two seconds.
Theme selection is serialized, validated and bound to the exact paired link/trust
epoch. One-use request IDs prevent replay. The canonical Omarchy setter resets
the theme and its wallpaper, including when selecting the current theme again.
Process cancellation and bounded writes prevent revoked requests from continuing.
A matching result and committed local apply must finish within Ohm’s selection
deadline. See [the settings transaction contract](theme-settings-transaction.md)
for the practical filesystem revocation and reconciliation limits.

Ohm peers identified by authenticated capability never obtain input through the
persistent `remote_input` setting. A desktop-local Approve notification within
20 seconds grants a five-minute lease to the exact paired TLS link. Actions must
come from the actual notification server. Ohm additionally requires Android 11
or newer and secure device-credential confirmation before opening its touchpad.
Cancellation, expiry, replacement, disconnect and unpair revoke input and queued
operations. Failed native cleanup blocks further grants; the setting stays unchanged.
Other Flux clients retain their upstream opt-in remote-input behavior.

Notification shells must render explicit Approve/Deny actions. Clicking a body
that only offers the default focus/dismiss action does not approve input. A
separate [Omarchy notification patch, tests and rollback guide](../tools/flux/omarchy-notification-actions/README.md)
implements buttons with exact live-owner correlation. It is not part of Flux’s
wire protocol and cannot grant input through restored or stale notifications.

## Wallpaper selection

Theme changes apply their palette and background on both devices. Selecting a
background changes the background on both devices without changing their palette.
The Background menu retains Theme’s card presentation: the active album’s images
followed by one Custom card. Only Custom opens the document picker.

Original JPEG, PNG and WebP images are bounded to 32 MiB and 32 megapixels and
validated by MIME, dimensions, length and SHA-256. Transfers use 24 KiB chunks on
the existing pinned connection. An omitted first Go chunk offset means zero;
continuation offsets must be explicit and match the received bytes. Album previews
are bounded JPEGs, separate from originals. The phone sends opaque content-bound
IDs, never filesystem paths. Desktop revision and theme checks reject stale picks.
Custom originals live outside theme albums and do not add gallery entries.
Received images/ACKs do not create outbound selection loops. See
[the original wallpaper contract](wallpaper-originals.md).

## Lifecycle and persistence

Logical closure precedes bounded transport cleanup. Separate TCP and TLS close
pools prevent a blocked TLS close from stalling socket interruption. Responder
shutdown closes unfinished handshakes; queued writers recheck live authority after
serialization. Home ownership generations reject late starters and callbacks.
Pending/active capture pins its listener owner; a pending outbound connection
blocks starting a conflicting capture. Deferred ownership resumes after capture.

Wallpaper writes share a root lock and executor, merge the latest configuration
and retain pending local intent until acknowledged. Theme transactions serialize
with the same settings-store monitor. A rename already entered can briefly publish
stale data after revocation; reconciliation schedules the newest live theme when
I/O returns. Permanently blocked filesystem I/O cannot guarantee progress.

## Validation scope

Physical phone/desktop observations on October 2, 2026 verified both connection
directions, persisted pairing across in-place updates and Home recreation, desktop
receipt of explicit shares, mutually authenticated file transfers, MPRIS command
units/state, H.264 decoding and capture teardown from a secondary launcher task.
Input was granted through the real desktop Approve action and the user’s phone
credential confirmation. Controlled opposite pointer gestures restored its initial
position; closing input retained pairing and left persistent remote input disabled.

Final wallpaper observations verified album ordering, the final Custom card,
matching original hashes on both devices after album/custom selections, unchanged
palettes during background selection, unchanged album contents after a custom
selection and restoration of a theme’s wallpaper when selecting that theme again.
The user confirmed these flows worked. These observations describe the deployed
integration baseline; they are distinct from automated validation of a newer
upstream Flux revision. Public release validation records identify the checked
source revision and build without publishing machine paths or LAN identifiers.

A TLS write alone is not delivery or an observed native effect. Audible playback
on a personal player and blocked-peer cancellation have no separate physical
effect observation. Continuous notifications, camera/microphone streaming, album
art and desktop control of the phone remain outside the Flux adapter.

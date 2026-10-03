# Original wallpapers over Flux

The direct edition negotiates `flux.wallpaper.v2` in both directions, independently
of the strict `flux.omarchy_theme` v1 palette catalog. Play does not expose Flux.

An authenticated state frame announces the current theme and wallpaper revision.
Each transfer has an operation, origin, base revision, theme, SHA-256, MIME, byte
length and dimensions. The receiver validates original JPEG/PNG/WebP bytes within
32 MiB and 32 megapixels. Chunks are 24 KiB on the existing pinned TLS connection.
The first Go chunk omits its zero offset; later offsets must match received bytes.

The phone canonicalizes its trusted Android storage root, then stores immutable
originals by content hash before atomically updating its latest configuration.
Symlinks below that root remain rejected. It acknowledges after the guarded local apply. Explicit
document selection creates durable outbound intent; a received image or ACK does
not. Pending choices retry on reconnection. Rejected choices retain their original
file, and a later authenticated desktop revision can resume synchronization.

Flux watches the canonical Omarchy background and its source generation every
two seconds. Manually selected images outside the theme folders are accepted only
through that local canonical link. Original reads retain regular-file, no-symlink
and byte/image bounds; peers cannot request an arbitrary filesystem path.

Desktop commit shares the theme mutex and Omarchy filesystem lock, checks the
base revision and paired link, saves immutable content, replaces the background
link atomically and calls the fixed shell IPC. Failed notification rolls back.
Received images live in `~/.local/state/omarchy/flux-wallpapers/`, outside the
Omarchy theme albums. Choosing an image changes the current wallpaper; choosing
a theme afterwards applies that theme through Omarchy and selects its background.
A durable receipt ledger deduplicates operations; ACK and updated state return
to the source phone without echoing its image. Other paired peers receive updates.

The ordinary Theme and Background menus use Flux when an eligible paired session
is present. Background uses the same visual cards as Theme: every image from the
active desktop theme's album, followed by a Custom card. Only Custom opens the
document picker. Selecting an album image changes the wallpaper on both devices
without changing their palette. Selecting a theme still applies its wallpaper.

The album uses bounded `gallery_*` messages within `flux.wallpaper.v2`. Requests
and selections belong to the exact paired session and operation. Selection checks
the current theme and wallpaper revision and resolves a content-bound image ID;
the phone never supplies a desktop path. Album previews are JPEGs at most 320 ×
180 and 16 KiB each, with at most 1,024 entries. They stay previews: the normal
original transfer applies the selected image on the phone. Legacy catalog
previews remain previews and are never uploaded as original wallpapers.

Theme selection confirmation stays attached to its live session when the desktop
refreshes its catalog before the ACK. A newer desktop theme supersedes an older
ACK without restoring its palette. An authoritative ACK received while the
selection write returns also stays confirmed until its guarded local commit.

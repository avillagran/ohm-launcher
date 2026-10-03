These fixtures use the Go Ohm adapter’s wire format to exercise catalog parsing,
correlated selection results, and original wallpaper receive/storage in Kotlin.
Theme IDs and palettes are synthetic test data. Wallpaper fixtures contain only
public test imagery. No machine-local path, address, certificate or identity is
required to replay them.

The native and legacy identity fixtures are serialized by the corresponding Go
protocol implementation and include public SPKI, capability and verification-key
data. Disposable private software keys for the optional TLS checks are generated
in the test fixture directory, not stored here.

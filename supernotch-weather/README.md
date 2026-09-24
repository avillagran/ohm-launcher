# supernotch-weather (Android port)

Weather map renderer and world-clock backend from
[omarchy-supernotch](https://github.com/avillagran/omarchy-supernotch)
(`plugins/weather/rust` and `plugins/clock/rust`), MIT-licensed, vendored here
with only the Android adaptations listed below:

- `state_dir()` honours `SUPERNOTCH_STATE_DIR` (Android has no `$HOME`).
- `weather_map::render_chart` is `pub` and `weather_map` is a `pub mod`
  so the JNI bridge can call it.
- `src/lib.rs` is a thin JNI entry point (same rendering code, loaded with
  `System.loadLibrary("supernotchweather")`); the original CLI in
  `src/main.rs` keeps working unchanged.
- The weather chart height is doubled from 320 to 640 pixels; the existing
  Rust map renderer still draws the forecast and wind layers.
- `src/clock.rs` is the original Supernotch clock Rust module; its timezone
  table is bundled so world-clock coordinates are available on Android, and
  JNI supplies the app-private state directory and Android's local timezone.
- The clock map uses the original `world-equal-earth.svg` asset; Android ships a
  rasterized copy for the native view, while clock times and map coordinates
  still come from the Supernotch Rust backend.
- Release profile optimizes for size (`opt-level = "z"`, LTO, strip).

The crate produces two artifacts:

- `supernotch-weather` — the original Linux CLI (forecast, geocoding,
  chart PNG rendering).
- `libsupernotchweather.so` — Android cdylib used by OhmLauncher's weather
  forecast map and world-clock map overlays.

## Rebuild for Android

```sh
./build-android.sh
```

requires the `aarch64-linux-android` and `x86_64-linux-android` rustup
targets plus the Android NDK (see `ANDROID_NDK` in the script). Output is
installed into `../app/src/main/jniLibs/<abi>/`.

## Data

One Open-Meteo (ECMWF IFS) request per fresh timeline, cached on disk
with a TTL; frames are pre-rendered PNGs the host side flips through.
Wind flow markers are drawn only when the device has more than 8 GiB of
RAM (same behaviour as upstream).

// Android JNI bridge for the OhmLauncher weather map.
//
// This is a thin foreign-function entry point over the exact same
// `weather_map` code that the omarchy-supernotch Linux CLI runs; no
// rendering logic lives here. Android cannot `exec()` binaries from the
// app home directory (SELinux, targetSdk 29+), so the crate is also
// built as a cdylib and loaded with `System.loadLibrary`.
//
// `weather_map.rs` resolves its helpers (`GridField`, `CHART_W`,
// `fetch_timeline`, ...) through `use super::*` against the CLI crate
// root, so the whole `main.rs` is included here as an inert module
// (`fn main` simply goes unused) instead of duplicating any logic.

#[allow(dead_code)]
#[path = "main.rs"]
mod host;

#[allow(dead_code)]
#[path = "clock.rs"]
mod clock;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::json;

#[no_mangle]
pub extern "system" fn Java_cl_villagranquiroz_ohm_1launcher_WeatherMapController_nativeChart(
    mut env: JNIEnv,
    _class: JClass,
    lat: JString,
    lon: JString,
    layer: JString,
    zoom: JString,
    state_dir: JString,
) -> jstring {
    let out = render(&mut env, lat, lon, layer, zoom, state_dir)
        .unwrap_or_else(|e| json!({ "error": e }).to_string());
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_cl_villagranquiroz_ohm_1launcher_ClockMapController_nativeWorldClocks(
    mut env: JNIEnv,
    _class: JClass,
    state_dir: JString,
    local_zone: JString,
) -> jstring {
    let out = world_clocks(&mut env, state_dir, local_zone)
        .unwrap_or_else(|error| json!({ "error": error }).to_string());
    env.new_string(out)
        .map(|string| string.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn world_clocks(
    env: &mut JNIEnv,
    state_dir: JString,
    local_zone: JString,
) -> Result<String, String> {
    let state_dir = env
        .get_string(&state_dir)
        .map_err(|_| "clock state dir".to_string())?;
    let local_zone = env
        .get_string(&local_zone)
        .map_err(|_| "local timezone".to_string())?;
    unsafe {
        std::env::set_var("SUPERNOTCH_CLOCK_STATE_DIR", state_dir.to_string_lossy().as_ref());
        std::env::set_var("SUPERNOTCH_CLOCK_LOCAL_TZ", local_zone.to_string_lossy().as_ref());
    }
    let prefs = std::path::PathBuf::from(state_dir.to_string_lossy().as_ref()).join("prefs.json");
    clock::worldclocks(&prefs)
        .map(|value| value.to_string())
        .map_err(|error| error.to_string())
}

#[no_mangle]
pub extern "system" fn Java_cl_villagranquiroz_ohm_1launcher_ClockMapController_nativeClockCommand(
    mut env: JNIEnv,
    _class: JClass,
    state_dir: JString,
    local_zone: JString,
    action: JString,
    argument: JString,
) -> jstring {
    let out = run_clock_command(&mut env, state_dir, local_zone, action, argument)
        .unwrap_or_else(|error| json!({ "error": error }).to_string());
    env.new_string(out)
        .map(|string| string.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn run_clock_command(
    env: &mut JNIEnv,
    state_dir: JString,
    local_zone: JString,
    action: JString,
    argument: JString,
) -> Result<String, String> {
    let state_dir = env
        .get_string(&state_dir)
        .map_err(|_| "clock state dir".to_string())?;
    let local_zone = env
        .get_string(&local_zone)
        .map_err(|_| "local timezone".to_string())?;
    let action = env
        .get_string(&action)
        .map_err(|_| "clock action".to_string())?;
    let argument = env
        .get_string(&argument)
        .map_err(|_| "clock argument".to_string())?;
    unsafe {
        std::env::set_var("SUPERNOTCH_CLOCK_STATE_DIR", state_dir.to_string_lossy().as_ref());
        std::env::set_var("SUPERNOTCH_CLOCK_LOCAL_TZ", local_zone.to_string_lossy().as_ref());
    }
    let prefs = std::path::PathBuf::from(state_dir.to_string_lossy().as_ref()).join("prefs.json");
    clock::execute_command(
        &prefs,
        action.to_string_lossy().as_ref(),
        argument.to_string_lossy().as_ref(),
    )
    .map(|value| value.to_string())
    .map_err(|error| error.to_string())
}

fn render(
    env: &mut JNIEnv,
    lat: JString,
    lon: JString,
    layer: JString,
    zoom: JString,
    state_dir: JString,
) -> Result<String, String> {
    let lat = env.get_string(&lat).map_err(|_| "lat".to_string())?;
    let lon = env.get_string(&lon).map_err(|_| "lon".to_string())?;
    let layer = env.get_string(&layer).map_err(|_| "layer".to_string())?;
    let zoom = env.get_string(&zoom).map_err(|_| "zoom".to_string())?;
    let state_dir = env
        .get_string(&state_dir)
        .map_err(|_| "state dir".to_string())?;
    // Android has no $HOME; state_dir() honours this override (see main.rs).
    unsafe {
        std::env::set_var("SUPERNOTCH_STATE_DIR", state_dir.to_string_lossy().as_ref())
    };
    host::weather_map::render_chart(
        lat.to_string_lossy().as_ref(),
        lon.to_string_lossy().as_ref(),
        layer.to_string_lossy().as_ref(),
        zoom.to_string_lossy().as_ref(),
        true,
    )
    .map(|v| v.to_string())
}

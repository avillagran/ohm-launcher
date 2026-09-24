// SuperNotch weather backend (Rust port).
// CLI surface kept identical to the previous Python backend so the QML layer
// and its tests do not change:
//   state | forecast <lat> <lon> | search <query> | chart <lat> <lon> <layer>
//   add-city <json> [confirm] | replace-city <id> <json> [confirm]
//   activate <id> | reorder <id> <delta> | remove <id>

use serde_json::{json, Map, Value};
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

pub mod weather_map;

const MAX_DOWNLOAD: usize = 1024 * 1024;
const CACHE_TTL: i64 = 7 * 24 * 60 * 60;
const MAX_CACHE_ENTRIES: usize = 32;
const USER_AGENT: &str = "SuperNotch/1.0";

const RAIN_LEGEND: &[(f64, (u8, u8, u8))] = &[
    (0.2, (151, 255, 255)), (0.5, (119, 229, 255)), (1.0, (53, 199, 255)),
    (2.0, (53, 144, 255)), (3.0, (52, 89, 255)), (4.0, (37, 43, 213)),
    (5.0, (25, 25, 112)), (10.0, (89, 37, 126)), (15.0, (113, 55, 144)),
    (20.0, (136, 72, 163)), (25.0, (160, 89, 181)), (30.0, (184, 106, 200)),
    (35.0, (208, 124, 218)), (40.0, (231, 141, 237)), (45.0, (255, 158, 255)),
    (50.0, (255, 128, 128)), (60.0, (222, 7, 9)), (70.0, (189, 13, 18)),
    (80.0, (155, 20, 26)), (100.0, (122, 27, 35)), (120.0, (89, 33, 44)),
    (150.0, (0, 0, 0)),
];

fn state_dir() -> PathBuf {
    // Android hosts (OhmLauncher) set SUPERNOTCH_STATE_DIR because there is no
    // $HOME; every other platform keeps the original XDG-style location.
    let dir = std::env::var("SUPERNOTCH_STATE_DIR").map(PathBuf::from).unwrap_or_else(|_| {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
        PathBuf::from(home).join(".local/state/omarchy-supernotch")
    });
    let _ = fs::create_dir_all(&dir);
    dir
}
fn state_file() -> PathBuf { state_dir().join("weather.json") }
fn cache_file() -> PathBuf { state_dir().join("weather-geocode-cache.json") }
fn lock_file() -> PathBuf { state_dir().join("weather.lock") }
fn omarchy_weather_file() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    PathBuf::from(home).join(".local/state/omarchy/settings/weather.json")
}

fn now_epoch() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn emit(value: &Value) {
    println!("{}", serde_json::to_string(value).unwrap_or_else(|_| "null".into()));
}

fn fail(message: &str) -> ! {
    eprintln!("{message}");
    std::process::exit(1)
}

fn http_get(url: &str, limit: usize) -> Result<Vec<u8>, String> {
    let response = ureq::get(url)
        .set("User-Agent", USER_AGENT)
        .set("Accept", "application/json")
        .timeout(std::time::Duration::from_secs(12))
        .call()
        .map_err(|e| {
            let status = match &e {
                ureq::Error::Status(code, _) => code.to_string(),
                _ => "transport".into(),
            };
            format!("http error {status}: {url}: {e}")
        })?;
    let mut bytes = Vec::new();
    response
        .into_reader()
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("read error: {e}"))?;
    if bytes.len() > limit {
        return Err("response too large".into());
    }
    Ok(bytes)
}

fn http_json(url: &str) -> Result<Value, String> {
    let bytes = http_get(url, MAX_DOWNLOAD)?;
    serde_json::from_slice(&bytes).map_err(|e| format!("invalid json: {e}"))
}

fn valid_coords(lat: f64, lon: f64) -> bool {
    lat.is_finite() && lon.is_finite() && (-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon)
}

fn parse_coords(lat: &str, lon: &str) -> Result<(f64, f64), String> {
    let la: f64 = lat.parse().map_err(|_| "invalid coordinates".to_string())?;
    let lo: f64 = lon.parse().map_err(|_| "invalid coordinates".to_string())?;
    if !valid_coords(la, lo) {
        return Err("invalid coordinates".into());
    }
    Ok((la, lo))
}

fn uuid12() -> String {
    let mut bytes = [0u8; 6];
    let _ = fs::File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut bytes));
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn offline() -> bool { std::env::var("WEATHER_OFFLINE").ok().as_deref() == Some("1") }

// ---- state ------------------------------------------------------------------

fn validate_state(state: &Value) -> Result<Value, String> {
    let obj = state.as_object().ok_or("invalid weather state")?;
    if obj.get("version").and_then(Value::as_i64) != Some(1) {
        return Err("invalid weather state".into());
    }
    let cities = obj.get("cities").and_then(Value::as_array).ok_or("invalid weather state")?;
    if cities.len() > 8 {
        return Err("invalid weather state".into());
    }
    let active = obj.get("activeId").and_then(Value::as_str).unwrap_or("");
    if obj.get("mapLayer").is_some_and(|layer| !matches!(layer.as_str(),
        Some("rain" | "clouds" | "temperature" | "wind" | "humidity")))
        || obj.get("mapZoom").is_some_and(|zoom| !matches!(zoom.as_u64(), Some(1..=3)))
    {
        return Err("invalid weather map view".into());
    }
    let mut ids = std::collections::HashSet::new();
    for city in cities {
        let id = city.get("id").and_then(Value::as_str).ok_or("invalid weather state")?;
        let name = city.get("name").and_then(Value::as_str).ok_or("invalid weather state")?;
        let lat = city.get("latitude").and_then(Value::as_f64).ok_or("invalid weather state")?;
        let lon = city.get("longitude").and_then(Value::as_f64).ok_or("invalid weather state")?;
        if id.is_empty() || name.trim().is_empty() || !valid_coords(lat, lon) || !ids.insert(id.to_string()) {
            return Err("invalid weather state".into());
        }
    }
    if (!cities.is_empty() && !ids.contains(active)) || (cities.is_empty() && !active.is_empty()) {
        return Err("invalid weather state".into());
    }
    Ok(state.clone())
}

fn save_state(state: &Value) -> Result<(), String> {
    let tmp = state_file().with_extension(format!("tmp.{}", std::process::id()));
    fs::write(&tmp, serde_json::to_string(state).map_err(|e| e.to_string())? + "\n")
        .map_err(|e| e.to_string())?;
    fs::rename(&tmp, state_file()).map_err(|e| e.to_string())?;
    Ok(())
}

fn migrated_state() -> Value {
    let mut cities = Vec::new();
    if let Ok(text) = fs::read_to_string(omarchy_weather_file()) {
        if let Ok(src) = serde_json::from_str::<Value>(&text) {
            let name = src.get("name").and_then(Value::as_str).unwrap_or("").trim().to_string();
            let lat = src.get("latitude").and_then(Value::as_f64);
            let lon = src.get("longitude").and_then(Value::as_f64);
            if !name.is_empty() {
                if let (Some(la), Some(lo)) = (lat, lon) {
                    if valid_coords(la, lo) {
                        cities.push(json!({"id": uuid12(), "name": name, "region": "", "country": "",
                            "latitude": la, "longitude": lo}));
                    }
                }
            }
        }
    }
    let active = cities.first().and_then(|c| c.get("id").and_then(Value::as_str)).unwrap_or("").to_string();
    let state = json!({"version": 1, "activeId": active, "cities": cities,
        "mapLayer": "rain", "mapZoom": 1});
    let _ = save_state(&state);
    state
}

fn load_state() -> Result<Value, String> {
    match fs::read_to_string(state_file()) {
        Ok(text) => {
            let state: Value = serde_json::from_str(&text).map_err(|_| "invalid weather state".to_string())?;
            validate_state(&state)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(migrated_state()),
        Err(_) => Err("invalid weather state".into()),
    }
}

fn with_state(f: impl FnOnce(Value) -> Result<(Value, Value), String>) -> Result<(), String> {
    let _ = fs::create_dir_all(state_dir());
    let lock = fs::OpenOptions::new().create(true).append(true).open(lock_file()).map_err(|e| e.to_string())?;
    fs2::FileExt::lock_exclusive(&lock).map_err(|e| e.to_string())?;
    let state = load_state()?;
    let (state, out) = f(state)?;
    save_state(&state)?;
    fs2::FileExt::unlock(&lock).map_err(|e| e.to_string())?;
    emit(&out);
    Ok(())
}

// ---- geocoding search --------------------------------------------------------

fn load_cache() -> Value {
    fs::read_to_string(cache_file())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .filter(|v: &Value| v.get("entries").is_some())
        .unwrap_or_else(|| json!({"version": 1, "entries": {}}))
}

fn save_cache(cache: &Value) {
    let mut entries: Vec<(String, Value)> = cache
        .get("entries").and_then(Value::as_object)
        .map(|m| m.iter().map(|(k, v)| (k.clone(), v.clone())).collect())
        .unwrap_or_default();
    entries.sort_by_key(|(_, v)| std::cmp::Reverse(v.get("fetchedAt").and_then(Value::as_i64).unwrap_or(0)));
    entries.truncate(MAX_CACHE_ENTRIES);
    let map: Map<String, Value> = entries.into_iter().collect();
    let out = json!({"version": 1, "entries": map});
    let tmp = cache_file().with_extension(format!("tmp.{}", std::process::id()));
    if fs::write(&tmp, serde_json::to_string(&out).unwrap_or_default() + "\n").is_ok() {
        let _ = fs::rename(&tmp, cache_file());
    }
}

fn parse_search_results(payload: &Value) -> Vec<Value> {
    let mut results = Vec::new();
    for item in payload.get("results").and_then(Value::as_array).cloned().unwrap_or_default() {
        if results.len() >= 8 {
            break;
        }
        let name = item.get("name").and_then(Value::as_str).unwrap_or("").trim().to_string();
        let region = item.get("admin1").and_then(Value::as_str).unwrap_or("").trim().to_string();
        let country = item.get("country").and_then(Value::as_str).unwrap_or("").trim().to_string();
        let lat = item.get("latitude").and_then(Value::as_f64);
        let lon = item.get("longitude").and_then(Value::as_f64);
        let (Some(la), Some(lo)) = (lat, lon) else { continue };
        if name.is_empty() || !valid_coords(la, lo) {
            continue;
        }
        let parts: Vec<&str> = [name.as_str(), region.as_str(), country.as_str()]
            .into_iter().filter(|p| !p.is_empty()).collect();
        results.push(json!({
            "name": name, "region": region, "country": country,
            "countryCode": item.get("country_code").and_then(Value::as_str).unwrap_or("").trim(),
            "latitude": la, "longitude": lo,
            "displayName": parts.join(", "),
        }));
    }
    results
}

fn search_cities(query: &str) -> Result<Value, String> {
    let query = query.trim();
    if query.len() < 2 {
        return Err("search requires at least two characters".into());
    }
    let key = query.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase();
    let mut cache = load_cache();
    let cached = cache.pointer(&format!("/entries/{}", key.replace('/', "~1"))).cloned();
    if let Some(entry) = &cached {
        if now_epoch() - entry.get("fetchedAt").and_then(Value::as_i64).unwrap_or(0) < CACHE_TTL {
            return Ok(entry.get("results").cloned().unwrap_or_else(|| json!([])));
        }
    }
    let payload = if let Ok(fixture) = std::env::var("WEATHER_SEARCH_FIXTURE") {
        let text = fs::read_to_string(fixture).map_err(|e| e.to_string())?;
        serde_json::from_str(&text).map_err(|e| e.to_string())?
    } else {
        if offline() {
            return Err("offline fixture mode".into());
        }
        let url = format!(
            "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name={}",
            urlencoding(query)
        );
        match http_json(&url) {
            Ok(v) => v,
            Err(e) => {
                if let Some(entry) = cached {
                    return Ok(entry.get("results").cloned().unwrap_or_else(|| json!([])));
                }
                return Err(e);
            }
        }
    };
    let results = parse_search_results(&payload);
    if let Some(entries) = cache.get_mut("entries").and_then(Value::as_object_mut) {
        entries.insert(key, json!({"fetchedAt": now_epoch(), "results": results}));
        save_cache(&cache);
    }
    Ok(json!(results))
}

fn urlencoding(text: &str) -> String {
    let mut out = String::new();
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' | b',' => out.push(b as char),
            b' ' => out.push_str("%20"),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

// ---- forecast -----------------------------------------------------------------

fn forecast(lat: &str, lon: &str) -> Result<Value, String> {
    let (la, lo) = parse_coords(lat, lon)?;
    if let Ok(fixture) = std::env::var("WEATHER_FORECAST_FIXTURE") {
        let text = fs::read_to_string(fixture).map_err(|e| e.to_string())?;
        return serde_json::from_str(&text).map_err(|e| e.to_string());
    }
    if offline() {
        return Err("offline fixture mode".into());
    }
    let url = format!(
        "https://api.open-meteo.com/v1/forecast?latitude={}&longitude={}\
         &daily=weather_code,temperature_2m_max,temperature_2m_min\
         &current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day,precipitation,rain,showers\
         &forecast_days=4&timezone=auto",
        la, lo
    );
    http_json(&url)
}

// ---- local ECMWF IFS chart render ----------------------------------------------
// Data: ECMWF IFS via Open-Meteo (models=ecmwf_ifs025). No third-party tile
// servers: the field is fetched as a small JSON grid and rendered locally —
// bilinear interpolation, meteorological color ramps, Natural Earth coastlines.

const COASTLINES_GEOJSON: &str = include_str!("../data/coastlines_50m.geojson");
const BOUNDARIES_GEOJSON: &str = include_str!("../data/boundaries.geojson");

const CHART_W: u32 = 1024;
const CHART_H: u32 = 640;
const CHART_LAT_SPAN: f64 = 2.4; // ~267 km N-S; E-W span follows the 1.6:1 canvas
const IFS_MODEL: &str = "ecmwf_ifs"; // native hourly HRES forecast

fn lon_span_for(lat: f64, lat_span: f64) -> f64 {
    let cos_lat = lat.to_radians().cos().abs().max(0.25);
    lat_span * (CHART_W as f64 / CHART_H as f64) / cos_lat
}

const TEMP_LEGEND: &[(f64, (u8, u8, u8))] = &[
    (-15.0, (30, 60, 180)), (-5.0, (70, 140, 220)), (5.0, (140, 210, 220)),
    (15.0, (190, 235, 170)), (25.0, (245, 220, 100)), (35.0, (235, 120, 50)),
];
const WIND_LEGEND: &[(f64, (u8, u8, u8))] = &[
    (0.0, (70, 170, 130)), (15.0, (150, 200, 100)), (30.0, (225, 190, 70)),
    (50.0, (240, 120, 50)), (75.0, (230, 60, 45)),
];
const HUMIDITY_LEGEND: &[(f64, (u8, u8, u8))] = &[
    (0.0, (215, 180, 95)), (40.0, (170, 205, 165)), (70.0, (105, 190, 200)),
    (100.0, (65, 160, 225)),
];

fn legend_color(legend: &[(f64, (u8, u8, u8))], value: f64) -> (u8, u8, u8) {
    if value <= legend[0].0 {
        return legend[0].1;
    }
    for idx in 0..legend.len() - 1 {
        let (v0, c0) = legend[idx];
        let (v1, c1) = legend[idx + 1];
        if value <= v1 {
            let t = if v1 > v0 { (value - v0) / (v1 - v0) } else { 0.0 };
            return (
                (c0.0 as f64 + (c1.0 as f64 - c0.0 as f64) * t).round() as u8,
                (c0.1 as f64 + (c1.1 as f64 - c0.1 as f64) * t).round() as u8,
                (c0.2 as f64 + (c1.2 as f64 - c0.2 as f64) * t).round() as u8,
            );
        }
    }
    legend.last().unwrap().1
}

const IFS_VARIABLES: &str =
    "precipitation,cloud_cover,temperature_2m,wind_speed_10m,wind_direction_10m,relative_humidity_2m";
const IFS_CACHE_TTL: i64 = 600;

struct GridField {
    lats: Vec<f64>,        // descending
    lons: Vec<f64>,        // ascending
    values: Vec<Vec<f64>>, // [row][col]
    run_time: String,      // model valid time from the API
}

fn fetch_ifs_grid(lat: f64, lon: f64, variable: &str) -> Result<GridField, String> {
    let cache_path = state_dir().join(format!(
        "ifs-grid-{}_{}.json",
        lat.round(),
        lon.round()
    ));
    let mut cached: Option<Value> = None;
    if let Ok(text) = fs::read_to_string(&cache_path) {
        if let Ok(doc) = serde_json::from_str::<Value>(&text) {
            let age = now_epoch() - doc.get("fetchedAt").and_then(Value::as_i64).unwrap_or(0);
            if age < IFS_CACHE_TTL {
                cached = Some(doc);
            }
        }
    }

    let values2d = |doc: &Value| -> Result<Vec<Vec<f64>>, String> {
        let vars = doc
            .get("values")
            .and_then(Value::as_object)
            .ok_or("IFS cache is invalid")?;
        let rows = vars
            .get(variable)
            .and_then(Value::as_array)
            .ok_or_else(|| format!("IFS grid lacks variable {variable}"))?;
        Ok(rows
            .iter()
            .map(|row| {
                row.as_array()
                    .map(|r| r.iter().filter_map(Value::as_f64).collect::<Vec<f64>>())
                    .unwrap_or_default()
            })
            .collect())
    };
    let lats_of = |doc: &Value| -> Result<Vec<f64>, String> {
        doc.get("lats")
            .and_then(Value::as_array)
            .and_then(|a| a.iter().map(Value::as_f64).collect())
            .ok_or_else(|| "IFS cache is invalid".to_string())
    };
    let lons_of = |doc: &Value| -> Result<Vec<f64>, String> {
        doc.get("lons")
            .and_then(Value::as_array)
            .and_then(|a| a.iter().map(Value::as_f64).collect())
            .ok_or_else(|| "IFS cache is invalid".to_string())
    };

    let run_time_of = |doc: &Value| -> String {
        doc.get("runTime").and_then(Value::as_str).unwrap_or("").to_string()
    };
    if let Some(doc) = cached {
        return Ok(GridField {
            lats: lats_of(&doc)?,
            lons: lons_of(&doc)?,
            values: values2d(&doc)?,
            run_time: run_time_of(&doc),
        });
    }

    let lon_span = lon_span_for(lat, CHART_LAT_SPAN);
    let step = 0.7f64;
    let n_rows = (CHART_LAT_SPAN / step).ceil() as i64 + 2;
    let n_cols = (lon_span / step).ceil() as i64 + 2;
    let lat_max = lat + CHART_LAT_SPAN / 2.0;
    let lon_min = lon - lon_span / 2.0;

    let lats: Vec<f64> = (0..n_rows).map(|r| lat_max - r as f64 * step).collect();
    let lons: Vec<f64> = (0..n_cols).map(|c| lon_min + c as f64 * step).collect();

    let mut pairs_lat = Vec::new();
    let mut pairs_lon = Vec::new();
    for la in &lats {
        for lo in &lons {
            pairs_lat.push(format!("{la:.4}"));
            pairs_lon.push(format!("{lo:.4}"));
        }
    }
    let url = format!(
        "https://api.open-meteo.com/v1/forecast?latitude={}&longitude={}&current={}&models={}&timezone=auto",
        pairs_lat.join(","),
        pairs_lon.join(","),
        IFS_VARIABLES,
        IFS_MODEL
    );
    // On failure, fall back to a stale cache rather than leaving the map empty.
    let payload = match http_json(&url) {
        Ok(doc) => doc,
        Err(err) => {
            if let Some(text) = fs::read_to_string(&cache_path).ok() {
                if let Ok(doc) = serde_json::from_str::<Value>(&text) {
                    return Ok(GridField {
                        lats: lats_of(&doc)?,
                        lons: lons_of(&doc)?,
                        values: values2d(&doc)?,
                        run_time: run_time_of(&doc),
                    });
                }
            }
            return Err(err);
        }
    };
    let reports = payload
        .as_array()
        .ok_or("IFS grid response is not an array")?;
    let run_time = reports
        .first()
        .and_then(|r| r.pointer("/current/time"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    if reports.len() != lats.len() * lons.len() {
        return Err("IFS grid response is incomplete".into());
    }

    let var_names: Vec<&str> = IFS_VARIABLES.split(',').collect();
    let mut grids: std::collections::HashMap<String, Vec<Vec<f64>>> = std::collections::HashMap::new();
    for name in &var_names {
        grids.insert((*name).to_string(), vec![vec![0.0f64; lons.len()]; lats.len()]);
    }
    for (idx, report) in reports.iter().enumerate() {
        let row = idx / lons.len();
        let col = idx % lons.len();
        for name in &var_names {
            let value = report
                .pointer(&format!("/current/{name}"))
                .and_then(Value::as_f64)
                .unwrap_or(0.0);
            if let Some(grid) = grids.get_mut(*name) {
                grid[row][col] = value;
            }
        }
    }

    let grid = grids
        .get(variable)
        .cloned()
        .ok_or_else(|| format!("IFS grid lacks variable {variable}"))?;

    // Persist all variables so layer switches within the TTL are free.
    let mut cache_doc = Map::new();
    cache_doc.insert("fetchedAt".into(), json!(now_epoch()));
    cache_doc.insert("runTime".into(), json!(run_time));
    cache_doc.insert(
        "lats".into(),
        json!(lats),
    );
    cache_doc.insert(
        "lons".into(),
        json!(lons),
    );
    let values_json: Map<String, Value> = grids
        .into_iter()
        .map(|(k, v)| (k, json!(v)))
        .collect();
    cache_doc.insert("values".into(), Value::Object(values_json));
    let _ = fs::write(&cache_path, serde_json::to_string(&cache_doc).unwrap_or_default());

    Ok(GridField { lats, lons, values: grid, run_time })
}

impl GridField {
    fn sample(&self, lat: f64, lon: f64) -> f64 {
        let step_lat = (self.lats[0] - *self.lats.last().unwrap()) / (self.lats.len() - 1) as f64;
        let step_lon = (*self.lons.last().unwrap() - self.lons[0]) / (self.lons.len() - 1) as f64;
        let fy = ((self.lats[0] - lat) / step_lat).clamp(0.0, (self.lats.len() - 1) as f64 - 0.001);
        let fx = ((lon - self.lons[0]) / step_lon).clamp(0.0, (self.lons.len() - 1) as f64 - 0.001);
        let (y0, x0) = (fy.floor() as usize, fx.floor() as usize);
        let (y1, x1) = (
            (y0 + 1).min(self.lats.len() - 1),
            (x0 + 1).min(self.lons.len() - 1),
        );
        let (ty, tx) = (fy - y0 as f64, fx - x0 as f64);
        let v00 = self.values[y0][x0];
        let v01 = self.values[y0][x1];
        let v10 = self.values[y1][x0];
        let v11 = self.values[y1][x1];
        (v00 * (1.0 - tx) + v01 * tx) * (1.0 - ty) + (v10 * (1.0 - tx) + v11 * tx) * ty
    }
}

fn field_rgba(field: &GridField, lat: f64, lon: f64, layer: &str, lat_span: f64) -> image::RgbaImage {
    let lon_span = lon_span_for(lat, lat_span);
    let lat_max = lat + lat_span / 2.0;
    let lon_min = lon - lon_span / 2.0;
    let mut img = image::RgbaImage::new(CHART_W, CHART_H);
    for py in 0..CHART_H {
        let la = lat_max - (py as f64 + 0.5) / CHART_H as f64 * lat_span;
        for px in 0..CHART_W {
            let lo = lon_min + (px as f64 + 0.5) / CHART_W as f64 * lon_span;
            let v = field.sample(la, lo);
            let color: Option<image::Rgba<u8>> = match layer {
                "rain" | "precipitation" => {
                    if v >= 0.05 {
                        let (r, g, b) = legend_color(RAIN_LEGEND, v);
                        Some(image::Rgba([r, g, b, 155]))
                    } else {
                        None
                    }
                }
                "clouds" => {
                    if v >= 3.0 {
                        let alpha = (v * 0.55).round().min(55.0) as u8;
                        let shade = (205.0 + v * 0.35).round().min(240.0) as u8;
                        Some(image::Rgba([shade, shade, shade.saturating_add(5), alpha]))
                    } else {
                        None
                    }
                }
                "temperature" => {
                    let (r, g, b) = legend_color(TEMP_LEGEND, v);
                    Some(image::Rgba([r, g, b, 110]))
                }
                "wind" => {
                    let (r, g, b) = legend_color(WIND_LEGEND, v);
                    Some(image::Rgba([r, g, b, 110]))
                }
                "humidity" => {
                    let (r, g, b) = legend_color(HUMIDITY_LEGEND, v);
                    Some(image::Rgba([r, g, b, 110]))
                }
                _ => None,
            };
            if let Some(c) = color {
                img.put_pixel(px, py, c);
            }
        }
    }
    // Blur smooths the lattice into a continuous field.
    image::imageops::blur(&img, 9.0)
}

fn each_line(geojson: &str, mut f: impl FnMut(&[(f64, f64)])) {
    let Ok(doc) = serde_json::from_str::<Value>(geojson) else { return };
    let Some(features) = doc.get("features").and_then(Value::as_array) else { return };
    for feature in features {
        let Some(geom) = feature.get("geometry") else { continue };
        let kind = geom.get("type").and_then(Value::as_str).unwrap_or("");
        let Some(coords) = geom.get("coordinates").and_then(Value::as_array) else { continue };
        let mut push_line = |line: &[Value]| {
            let pts: Vec<(f64, f64)> = line
                .iter()
                .filter_map(|p| {
                    let a = p.as_array()?;
                    Some((a.first()?.as_f64()?, a.get(1)?.as_f64()?))
                })
                .collect();
            if pts.len() >= 2 {
                f(&pts);
            }
        };
        match kind {
            "LineString" => push_line(coords),
            "MultiLineString" => {
                for line in coords {
                    if let Some(line) = line.as_array() {
                        push_line(line);
                    }
                }
            }
            _ => {}
        }
    }
}

fn draw_geojson(
    canvas: &mut image::RgbaImage,
    geojson: &str,
    lat: f64,
    lon: f64,
    lat_span: f64,
    color: image::Rgba<u8>,
) {
    let lon_span = lon_span_for(lat, lat_span);
    let lat_max = lat + lat_span / 2.0;
    let lat_min = lat - lat_span / 2.0;
    let lon_min = lon - lon_span / 2.0;
    let lon_max = lon + lon_span / 2.0;
    let to_px = |la: f64, lo: f64| -> (f32, f32) {
        let x = ((lo - lon_min) / lon_span * CHART_W as f64) as f32;
        let y = ((lat_max - la) / lat_span * CHART_H as f64) as f32;
        (x, y)
    };
    each_line(geojson, |pts| {
        for pair in pts.windows(2) {
            let (lo1, la1) = pair[0];
            let (lo2, la2) = pair[1];
            // Cheap bbox reject
            if (lo1 < lon_min && lo2 < lon_min) || (lo1 > lon_max && lo2 > lon_max)
                || (la1 < lat_min && la2 < lat_min) || (la1 > lat_max && la2 > lat_max)
            {
                continue;
            }
            let p1 = to_px(la1, lo1);
            let p2 = to_px(la2, lo2);
            imageproc::drawing::draw_antialiased_line_segment_mut(
                canvas, (p1.0 as i32, p1.1 as i32), (p2.0 as i32, p2.1 as i32),
                color, imageproc::pixelops::interpolate,
            );
        }
    });
}

fn render_chart(lat: &str, lon: &str, layer: &str, zoom: &str) -> Result<Value, String> {
    weather_map::render_chart(lat, lon, layer, zoom, false)
}


// ---- city mutations -----------------------------------------------------------

fn validated_city(raw: &str) -> Result<Value, String> {
    let data: Value = serde_json::from_str(raw).map_err(|_| "invalid city".to_string())?;
    let obj = data.as_object().ok_or("invalid city")?;
    let name = obj.get("name").and_then(Value::as_str).unwrap_or("").trim().to_string();
    let region = obj.get("region").and_then(Value::as_str).unwrap_or("").trim().to_string();
    let country = obj.get("country").and_then(Value::as_str).unwrap_or("").trim().to_string();
    let country_code = obj.get("countryCode").and_then(Value::as_str).unwrap_or("").trim().to_uppercase();
    let lat = obj.get("latitude").and_then(Value::as_f64).ok_or("invalid city coordinates")?;
    let lon = obj.get("longitude").and_then(Value::as_f64).ok_or("invalid city coordinates")?;
    if name.is_empty() || !valid_coords(lat, lon) {
        return Err("invalid city".into());
    }
    let mut display = obj.get("displayName").and_then(Value::as_str).unwrap_or("").trim().to_string();
    if display.is_empty() {
        let parts: Vec<&str> = [name.as_str(), region.as_str(), country.as_str()]
            .into_iter().filter(|p| !p.is_empty()).collect();
        display = parts.join(", ");
    }
    Ok(json!({"id": uuid12(), "name": name, "region": region, "country": country,
        "countryCode": country_code, "latitude": lat, "longitude": lon, "displayName": display}))
}

fn sync_omarchy(city: &Value) -> Result<(), String> {
    let command = std::env::var("WEATHER_LOCATION_COMMAND").unwrap_or_else(|_| "omarchy-weather-location".into());
    let name = city.get("name").and_then(Value::as_str).unwrap_or("");
    let coords = format!("{},{}",
        city.get("latitude").and_then(Value::as_f64).unwrap_or(0.0),
        city.get("longitude").and_then(Value::as_f64).unwrap_or(0.0));
    Command::new(&command)
        .args(["--set", name, &coords])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped())
        .output()
        .map_err(|e| format!("could not sync Omarchy weather location: {e}"))
        .and_then(|out| {
            if out.status.success() {
                Ok(())
            } else {
                Err("could not sync Omarchy weather location".into())
            }
        })
}

fn distance_km(a: &Value, b: &Value) -> f64 {
    let (la, lo) = (a.get("latitude").and_then(Value::as_f64).unwrap_or(0.0), a.get("longitude").and_then(Value::as_f64).unwrap_or(0.0));
    let (lb, lb2) = (b.get("latitude").and_then(Value::as_f64).unwrap_or(0.0), b.get("longitude").and_then(Value::as_f64).unwrap_or(0.0));
    let (lat1, lat2) = (la.to_radians(), lb.to_radians());
    let dlat = lat2 - lat1;
    let dlon = (lb2 - lo).to_radians();
    let h = (dlat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);
    6371.0 * 2.0 * h.sqrt().atan2((1.0 - h).sqrt())
}

fn city_index(state: &Value, id: &str) -> Result<usize, String> {
    state.get("cities").and_then(Value::as_array)
        .and_then(|c| c.iter().position(|city| city.get("id").and_then(Value::as_str) == Some(id)))
        .ok_or_else(|| "city not found".to_string())
}

fn add_city(mut state: Value, raw: &str, confirmed: bool) -> Result<(Value, Value), String> {
    let cities_len = state.get("cities").and_then(Value::as_array).map(|c| c.len()).unwrap_or(0);
    if cities_len >= 8 {
        return Err("weather supports at most eight cities".into());
    }
    let city = validated_city(raw)?;
    let mut nearest: Option<(f64, Value)> = None;
    for existing in state.get("cities").and_then(Value::as_array).cloned().unwrap_or_default() {
        let dist = distance_km(&city, &existing);
        if nearest.as_ref().map(|(d, _)| dist < *d).unwrap_or(true) {
            nearest = Some((dist, existing));
        }
    }
    if let Some((dist, existing)) = &nearest {
        if *dist <= 0.1 {
            return Ok((state, json!({"duplicate": true, "requiresConfirmation": false,
                "existing": existing, "message": "That location is already in the weather list"})));
        }
        if *dist <= 10.0 && !confirmed {
            return Ok((state, json!({"duplicate": false, "requiresConfirmation": true,
                "existing": existing, "candidate": city,
                "message": "A nearby city is already in the weather list"})));
        }
    }
    if state.get("activeId").and_then(Value::as_str).unwrap_or("").is_empty() {
        sync_omarchy(&city)?;
        state["activeId"] = city.get("id").cloned().unwrap_or(json!(""));
    }
    state.get_mut("cities").and_then(Value::as_array_mut).ok_or("invalid state")?.push(city);
    Ok((state.clone(), state))
}

fn activate_city(mut state: Value, id: &str) -> Result<(Value, Value), String> {
    let index = city_index(&state, id)?;
    let city = state.pointer(&format!("/cities/{index}")).cloned().ok_or("city not found")?;
    sync_omarchy(&city)?;
    state["activeId"] = json!(id);
    Ok((state.clone(), state))
}

fn reorder_city(mut state: Value, id: &str, delta: &str) -> Result<(Value, Value), String> {
    let current = city_index(&state, id)?;
    let delta: i64 = delta.parse().map_err(|_| "invalid reorder delta".to_string())?;
    let len = state.get("cities").and_then(Value::as_array).map(|c| c.len()).unwrap_or(0);
    let target = (current as i64 + delta).clamp(0, len as i64 - 1) as usize;
    if target != current {
        let cities = state.get_mut("cities").and_then(Value::as_array_mut).ok_or("invalid state")?;
        let city = cities.remove(current);
        cities.insert(target, city);
    }
    Ok((state.clone(), state))
}

fn remove_city(mut state: Value, id: &str) -> Result<(Value, Value), String> {
    let current = city_index(&state, id)?;
    state.get_mut("cities").and_then(Value::as_array_mut).ok_or("invalid state")?.remove(current);
    if state.get("activeId").and_then(Value::as_str) == Some(id) {
        let len = state.get("cities").and_then(Value::as_array).map(|c| c.len()).unwrap_or(0);
        if len > 0 {
            let idx = current.min(len - 1);
            let city = state.pointer(&format!("/cities/{idx}")).cloned().ok_or("city not found")?;
            sync_omarchy(&city)?;
            state["activeId"] = city.get("id").cloned().unwrap_or(json!(""));
        } else {
            state["activeId"] = json!("");
        }
    }
    Ok((state.clone(), state))
}

fn replace_city(mut state: Value, id: &str, raw: &str, confirmed: bool) -> Result<(Value, Value), String> {
    let current = city_index(&state, id)?;
    let mut replacement = validated_city(raw)?;
    replacement["id"] = json!(id);
    let others: Vec<Value> = state.get("cities").and_then(Value::as_array).cloned().unwrap_or_default()
        .into_iter().filter(|c| c.get("id").and_then(Value::as_str) != Some(id)).collect();
    let nearest = others.iter()
        .map(|c| (distance_km(&replacement, c), c.clone()))
        .min_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    if let Some((dist, existing)) = &nearest {
        if *dist <= 0.1 {
            return Ok((state, json!({"duplicate": true, "requiresConfirmation": false,
                "existing": existing, "message": "That location is already in the weather list"})));
        }
        if *dist <= 10.0 && !confirmed {
            return Ok((state, json!({"duplicate": false, "requiresConfirmation": true,
                "existing": existing, "candidate": replacement,
                "message": "A nearby city is already in the weather list"})));
        }
    }
    if state.get("activeId").and_then(Value::as_str) == Some(id) {
        sync_omarchy(&replacement)?;
    }
    state.get_mut("cities").and_then(Value::as_array_mut).ok_or("invalid state")?[current] = replacement;
    Ok((state.clone(), state))
}

// ---- main ---------------------------------------------------------------------

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("state");
    let arg = |i: usize| args.get(i).map(String::as_str).unwrap_or("");

    let result: Result<(), String> = (|| {
        match command {
            "search" => emit(&search_cities(arg(1))?),
            "forecast" => emit(&forecast(arg(1), arg(2))?),
            "chart" => emit(&render_chart(arg(1), arg(2), if arg(3).is_empty() { "rain" } else { arg(3) }, arg(4))?),
            "chart-live" => emit(&weather_map::render_chart(arg(1), arg(2),
                if arg(3).is_empty() { "rain" } else { arg(3) }, arg(4), true)?),
            "state" => {
                let _ = fs::create_dir_all(state_dir());
                let lock = fs::OpenOptions::new().create(true).append(true).open(lock_file()).map_err(|e| e.to_string())?;
                fs2::FileExt::lock_exclusive(&lock).map_err(|e| e.to_string())?;
                let state = load_state()?;
                fs2::FileExt::unlock(&lock).map_err(|e| e.to_string())?;
                emit(&state);
            }
            "add-city" => with_state(|state| add_city(state, arg(1), arg(2) == "confirm"))?,
            "replace-city" => with_state(|state| replace_city(state, arg(1), arg(2), arg(3) == "confirm"))?,
            "activate" => with_state(|state| activate_city(state, arg(1)))?,
            "reorder" => with_state(|state| reorder_city(state, arg(1), arg(2)))?,
            "remove" => with_state(|state| remove_city(state, arg(1)))?,
            "map-view" => {
                let layer = arg(1);
                let zoom: u8 = arg(2).parse().map_err(|_| "invalid map zoom")?;
                if !matches!(layer, "rain" | "clouds" | "temperature" | "wind" | "humidity")
                    || !(1..=3).contains(&zoom) {
                    return Err("invalid weather map view".into());
                }
                with_state(|mut state| {
                    state["mapLayer"] = json!(layer);
                    state["mapZoom"] = json!(zoom);
                    Ok((state.clone(), state))
                })?
            }
            other => return Err(format!("unknown command: {other}")),
        }
        Ok(())
    })();

    if let Err(e) = result {
        fail(&e);
    }
}

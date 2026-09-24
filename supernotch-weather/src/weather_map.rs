//! Bounded, locally rendered IFS time steps for the weather panel.
//!
//! One Open-Meteo request fetches five variables at 16 locations and ten hours.
//! Ten hourly forecast steps are rendered once to PNG; QML only swaps images to play.

use super::*;

const ROWS: usize = 4;
const COLS: usize = 4;
const FORECAST_HOURS: usize = 10;
const FRAME_HOURS: [usize; FORECAST_HOURS] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
const LAND_GEOJSON: &str = include_str!("../data/land_50m.geojson");
const PLACES: &str = include_str!("../data/places.tsv");

type Values = std::collections::HashMap<String, Vec<Vec<Vec<f64>>>>;

struct Timeline {
    lats: Vec<f64>,
    lons: Vec<f64>,
    times: Vec<String>,
    values: Values,
    stale: bool,
    model: String,
}

fn cache_path(lat: f64, lon: f64, zoom: u8) -> PathBuf {
    state_dir().join(format!("ifs-grid-{lat:.4}_{lon:.4}_z{zoom}.json"))
}

fn variable(layer: &str) -> Option<(&'static str, &'static str)> {
    match layer {
        "rain" | "precipitation" => Some(("precipitation", "mm")),
        "clouds" => Some(("cloud_cover", "%")),
        "temperature" => Some(("temperature_2m", "°C")),
        "wind" => Some(("wind_speed_10m", "km/h")),
        "humidity" => Some(("relative_humidity_2m", "%")),
        _ => None,
    }
}

fn wrapped_lon(lon: f64, center: f64) -> f64 {
    center + (lon - center + 540.0).rem_euclid(360.0) - 180.0
}

fn memory_allows_vectors(meminfo: &str) -> bool {
    meminfo.lines().find_map(|line| line.strip_prefix("MemTotal:")
        .and_then(|value| value.split_whitespace().next()?.parse::<u64>().ok()))
        .is_some_and(|kib| kib > 8 * 1024 * 1024)
}

fn flow_direction(from_degrees: f64) -> (f64, f64) {
    let downwind = (from_degrees + 180.0).to_radians();
    (downwind.sin(), -downwind.cos())
}

// Interpolate unit vectors, not degrees: 350° and 10° average to north, not south.
fn sample_bearing(direction: &GridField, lat: f64, lon: f64) -> f64 {
    let components = |trig: fn(f64) -> f64| GridField {
        lats: direction.lats.clone(), lons: direction.lons.clone(), run_time: String::new(),
        values: direction.values.iter().map(|row| row.iter()
            .map(|degrees| trig(degrees.to_radians())).collect()).collect(),
    };
    let east = components(f64::sin).sample(lat, lon);
    let north = components(f64::cos).sample(lat, lon);
    east.atan2(north).to_degrees().rem_euclid(360.0)
}

// Pre-render a sparse field once per forecast frame; QML only swaps PNGs.
fn draw_flow_markers(canvas: &mut image::RgbaImage, speed: &GridField, direction: &GridField,
    rain: Option<&GridField>, lat: f64, lon: f64, span: f64) {
    let lon_span = lon_span_for(lat, span);
    for fraction in [0.2, 0.5, 0.8] {
        let y = CHART_H as f64 * fraction;
        for x in (64..CHART_W).step_by(84) {
            let x = x as f64;
            let place_lat = lat + span * (0.5 - y / CHART_H as f64);
            let place_lon = lon + lon_span * (x / CHART_W as f64 - 0.5);
            let strength = speed.sample(place_lat, place_lon);
            if strength < 4.0 || rain.is_some_and(|field| field.sample(place_lat, place_lon) < 0.25) {
                continue;
            }
            let (dx, dy) = flow_direction(sample_bearing(direction, place_lat, place_lon));
            let length = (10.0 + strength.min(65.0) * 0.11) as f32;
            let (dx, dy) = (dx as f32, dy as f32);
            let (x, y) = (x as f32, y as f32);
            let tail = (x - dx * length * 0.5, y - dy * length * 0.5);
            let tip = (x + dx * length * 0.5, y + dy * length * 0.5);
            let wings = [
                (tip.0 - dx * 5.0 - dy * 3.0, tip.1 - dy * 5.0 + dx * 3.0),
                (tip.0 - dx * 5.0 + dy * 3.0, tip.1 - dy * 5.0 - dx * 3.0),
            ];
            let ink = if rain.is_some() { image::Rgba([206, 253, 255, 255]) }
                else { image::Rgba([252, 248, 224, 255]) };
            for (start, end) in [(tail, tip), (tip, wings[0]), (tip, wings[1])] {
                imageproc::drawing::draw_line_segment_mut(canvas,
                    (start.0 + 1.0, start.1 + 1.0), (end.0 + 1.0, end.1 + 1.0),
                    image::Rgba([8, 24, 36, 255]));
                imageproc::drawing::draw_line_segment_mut(canvas, start, end, ink);
            }
        }
    }
}

fn base_map(lat: f64, lon: f64, span: f64) -> image::RgbaImage {
    let mut canvas = image::RgbaImage::from_pixel(CHART_W, CHART_H, image::Rgba([15, 30, 50, 255]));
    let lon_span = lon_span_for(lat, span);
    let lat_min = lat - span / 2.0;
    let lat_max = lat + span / 2.0;
    let lon_min = lon - lon_span / 2.0;
    let lon_max = lon + lon_span / 2.0;
    if let Ok(doc) = serde_json::from_str::<Value>(LAND_GEOJSON) {
        if let Some(features) = doc.get("features").and_then(Value::as_array) {
            for feature in features {
                let geom = &feature["geometry"];
                let polygons = match geom["type"].as_str() {
                    Some("Polygon") => vec![&geom["coordinates"]],
                    Some("MultiPolygon") => geom["coordinates"].as_array()
                        .map(|arr| arr.iter().collect()).unwrap_or_default(),
                    _ => continue,
                };
                for polygon in polygons {
                    let Some(rings) = polygon.as_array() else { continue };
                    for (ring_index, ring) in rings.iter().enumerate() {
                        let Some(vertices) = ring.as_array() else { continue };
                        let points: Vec<(f64, f64)> = vertices.iter().filter_map(|pair| {
                            Some((wrapped_lon(pair.get(0)?.as_f64()?, lon), pair.get(1)?.as_f64()?))
                        }).collect();
                        if points.len() < 3 { continue; }
                        let (min_lo, max_lo, min_la, max_la) = points.iter().fold(
                            (f64::INFINITY, f64::NEG_INFINITY, f64::INFINITY, f64::NEG_INFINITY),
                            |(min_x, max_x, min_y, max_y), &(x, y)|
                                (min_x.min(x), max_x.max(x), min_y.min(y), max_y.max(y)));
                        if max_lo < lon_min || min_lo > lon_max || max_la < lat_min || min_la > lat_max {
                            continue;
                        }
                        let mut pixels: Vec<imageproc::point::Point<i32>> = points.iter()
                            .map(|&(lo, la)| imageproc::point::Point::new(
                                ((lo - lon_min) / lon_span * CHART_W as f64).round() as i32,
                                ((lat_max - la) / span * CHART_H as f64).round() as i32,
                            )).collect();
                        if pixels.first() == pixels.last() { pixels.pop(); }
                        if pixels.len() < 3 { continue; }
                        let color = if ring_index == 0 { [72, 89, 78, 255] } else { [15, 30, 50, 255] };
                        imageproc::drawing::draw_polygon_mut(&mut canvas, &pixels, image::Rgba(color));
                    }
                }
            }
        }
    }
    for fraction in [0.25, 0.5, 0.75] {
        let x = (CHART_W as f64 * fraction) as f32;
        imageproc::drawing::draw_line_segment_mut(&mut canvas,
            (x, 0.0), (x, CHART_H as f32), image::Rgba([42, 58, 72, 255]));
    }
    for fraction in [0.33, 0.66] {
        let y = (CHART_H as f64 * fraction) as f32;
        imageproc::drawing::draw_line_segment_mut(&mut canvas,
            (0.0, y), (CHART_W as f32, y), image::Rgba([42, 58, 72, 255]));
    }
    canvas
}

fn place_labels(lat: f64, lon: f64, span: f64) -> Vec<Value> {
    let lon_span = lon_span_for(lat, span);
    let mut candidates = Vec::new();
    for row in PLACES.lines() {
        let mut cols = row.splitn(4, '\t');
        let (Some(lo), Some(la), Some(pop), Some(name)) =
            (cols.next(), cols.next(), cols.next(), cols.next()) else { continue };
        let (Ok(lo), Ok(la), Ok(pop)) = (lo.parse::<f64>(), la.parse::<f64>(), pop.parse::<i64>()) else {
            continue;
        };
        let x = 0.5 + (wrapped_lon(lo, lon) - lon) / lon_span;
        let y = 0.5 + (lat - la) / span;
        if x > 0.07 && x < 0.93 && y > 0.13 && y < 0.87 {
            let centered = (x - 0.5).abs() < 0.05 && (y - 0.5).abs() < 0.12;
            candidates.push((centered, pop, name, x, y));
        }
    }
    candidates.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| b.1.cmp(&a.1)));
    let mut labels: Vec<(f64, f64, Value)> = Vec::new();
    for (_, _, name, x, y) in candidates {
        if labels.len() == 9 { break; }
        if labels.iter().all(|(old_x, old_y, _)| (x - old_x).abs() > 0.10 || (y - old_y).abs() > 0.13) {
            labels.push((x, y, json!({ "name": name, "x": x, "y": y })));
        }
    }
    labels.into_iter().map(|(_, _, label)| label).collect()
}

fn chart_legend(layer: &str) -> Vec<Value> {
    let (ramp, values): (&[(f64, (u8, u8, u8))], &[f64]) = match layer {
        "rain" => (RAIN_LEGEND, &[0.2, 1.0, 3.0, 10.0]),
        "temperature" => (TEMP_LEGEND, &[-5.0, 5.0, 15.0, 25.0, 35.0]),
        "wind" => (WIND_LEGEND, &[5.0, 15.0, 30.0, 50.0]),
        "humidity" => (HUMIDITY_LEGEND, &[20.0, 40.0, 70.0, 100.0]),
        _ => return [20, 50, 80, 100].iter().map(|v|
            json!({ "label": v.to_string(), "color": "#d9e0e6" })).collect(),
    };
    values.iter().map(|&value| {
        let (r, g, b) = legend_color(ramp, value);
        json!({ "label": value.to_string(), "color": format!("#{r:02x}{g:02x}{b:02x}") })
    }).collect()
}

fn parse_cache(doc: &Value, stale: bool) -> Result<Timeline, String> {
    let lats: Vec<f64> = serde_json::from_value(doc.get("lats").cloned().ok_or("missing latitudes")?)
        .map_err(|e| format!("invalid latitudes: {e}"))?;
    let lons: Vec<f64> = serde_json::from_value(doc.get("lons").cloned().ok_or("missing longitudes")?)
        .map_err(|e| format!("invalid longitudes: {e}"))?;
    let times: Vec<String> = serde_json::from_value(doc.get("times").cloned().ok_or("missing times")?)
        .map_err(|e| format!("invalid times: {e}"))?;
    let values: Values = serde_json::from_value(doc.get("values").cloned().ok_or("missing values")?)
        .map_err(|e| format!("invalid values: {e}"))?;
    if lats.len() < 2 || lons.len() < 2 || lats.len() * lons.len() > 512
        || times.is_empty() || times.len() > FRAME_HOURS.len()
        || lats.iter().chain(lons.iter()).any(|v| !v.is_finite())
    {
        return Err("invalid IFS grid dimensions".into());
    }
    for field in values.values() {
        if field.len() != times.len()
            || field.iter().any(|frame| frame.len() != lats.len()
                || frame.iter().any(|row| row.len() != lons.len() || row.iter().any(|v| !v.is_finite())))
        {
            return Err("invalid IFS frame dimensions".into());
        }
    }
    Ok(Timeline { lats, lons, times, values, stale,
        model: doc.get("model").and_then(Value::as_str).unwrap_or("ecmwf_ifs025").to_string() })
}

fn cached_timeline(lat: f64, lon: f64, zoom: u8) -> Option<Timeline> {
    let doc: Value = serde_json::from_str(&fs::read_to_string(cache_path(lat, lon, zoom)).ok()?).ok()?;
    let age = now_epoch() - doc.get("fetchedAt")?.as_i64()?;
    if !(0..IFS_CACHE_TTL).contains(&age) { return None; }
    parse_cache(&doc, false).ok().filter(|timeline|
        timeline.times.len() == FORECAST_HOURS && timeline.model == IFS_MODEL
            && timeline.values.contains_key("wind_direction_10m"))
}

fn legacy_snapshot(lat: f64, lon: f64) -> Option<Timeline> {
    let path = state_dir().join(format!("ifs-grid-{}_{}.json", lat.round(), lon.round()));
    let old: Value = serde_json::from_str(&fs::read_to_string(path).ok()?).ok()?;
    let age = now_epoch() - old.get("fetchedAt")?.as_i64()?;
    if !(0..86_400).contains(&age) { return None; }
    let values = old.get("values")?.as_object()?;
    let wrapped: Map<String, Value> = values.iter()
        .map(|(name, grid)| (name.clone(), json!([grid]))).collect();
    let doc = json!({ "lats": old.get("lats")?, "lons": old.get("lons")?,
        "times": [old.get("runTime")?], "values": wrapped });
    parse_cache(&doc, age >= IFS_CACHE_TTL).ok()
}

fn fetch_timeline(lat: f64, lon: f64, zoom: u8) -> Result<Timeline, String> {
    let path = cache_path(lat, lon, zoom);
    let cached = fs::read_to_string(&path)
        .ok()
        .and_then(|text| serde_json::from_str::<Value>(&text).ok());
    if let Some(doc) = &cached {
        let age = now_epoch() - doc.get("fetchedAt").and_then(Value::as_i64).unwrap_or(0);
        if age >= 0 && age < IFS_CACHE_TTL {
            if let Ok(timeline) = parse_cache(doc, false) {
                if timeline.times.len() == FORECAST_HOURS && timeline.model == IFS_MODEL
                    && timeline.values.contains_key("wind_direction_10m") {
                    return Ok(timeline);
                }
            }
        }
    }
    if offline() { return Err("offline and no cached IFS timeline".into()); }

    let lat_span = CHART_LAT_SPAN / 2f64.powi((zoom - 1) as i32);
    let lon_span = lon_span_for(lat, lat_span);
    let lats: Vec<f64> = (0..ROWS)
        .map(|row| lat + lat_span / 2.0 - row as f64 * lat_span / (ROWS - 1) as f64)
        .collect();
    let lons: Vec<f64> = (0..COLS)
        .map(|col| lon - lon_span / 2.0 + col as f64 * lon_span / (COLS - 1) as f64)
        .collect();
    let mut lat_list = Vec::with_capacity(ROWS * COLS);
    let mut lon_list = Vec::with_capacity(ROWS * COLS);
    for la in &lats {
        for lo in &lons {
            lat_list.push(format!("{la:.4}"));
            lon_list.push(format!("{lo:.4}"));
        }
    }
    let url = format!(
        "https://api.open-meteo.com/v1/forecast?latitude={}&longitude={}&hourly={}&forecast_hours={}&models={}&timezone=UTC",
        lat_list.join(","), lon_list.join(","), IFS_VARIABLES, FORECAST_HOURS, IFS_MODEL
    );
    let payload = match http_json(&url) {
        Ok(value) => value,
        Err(error) => {
            if let Some(doc) = cached {
                // Label old data as stale; never pretend this is live radar.
                if let Ok(timeline) = parse_cache(&doc, true) { return Ok(timeline); }
            }
            return Err(error);
        }
    };
    let reports = payload.as_array().ok_or("invalid IFS hourly response")?;
    if reports.len() != ROWS * COLS { return Err("incomplete IFS hourly grid".into()); }
    let times_all = reports[0].pointer("/hourly/time").and_then(Value::as_array)
        .ok_or("missing IFS hours")?;
    let times: Vec<String> = FRAME_HOURS.iter()
        .map(|&hour| times_all.get(hour).and_then(Value::as_str).map(str::to_string)
            .ok_or_else(|| "missing IFS forecast step".to_string()))
        .collect::<Result<_, _>>()?;
    let mut values = Values::new();
    for name in IFS_VARIABLES.split(',') {
        let mut frames = vec![vec![vec![0.0; COLS]; ROWS]; FRAME_HOURS.len()];
        for (point, report) in reports.iter().enumerate() {
            let series = report.pointer(&format!("/hourly/{name}")).and_then(Value::as_array)
                .ok_or("missing IFS variable")?;
            for (frame, &hour) in FRAME_HOURS.iter().enumerate() {
                frames[frame][point / COLS][point % COLS] = series.get(hour)
                    .and_then(Value::as_f64).ok_or("invalid IFS hourly value")?;
            }
        }
        values.insert(name.to_string(), frames);
    }
    let doc = json!({ "fetchedAt": now_epoch(), "model": IFS_MODEL, "lats": lats, "lons": lons,
        "times": times, "values": values });
    let timeline = parse_cache(&doc, false)?;
    // Atomic replacement prevents partial reads when two chart commands race.
    let tmp = path.with_extension(format!("json.{}", std::process::id()));
    fs::write(&tmp, serde_json::to_vec(&doc).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
    Ok(timeline)
}

pub fn render_chart(lat: &str, lon: &str, layer: &str, zoom: &str, live: bool) -> Result<Value, String> {
    let (la, lo) = parse_coords(lat, lon)?;
    let zoom: u8 = if zoom.is_empty() { 1 } else { zoom.parse().map_err(|_| "invalid zoom")? };
    if !(1..=3).contains(&zoom) { return Err("zoom must be 1, 2, or 3".into()); }
    let layer = if layer == "precipitation" { "rain" } else { layer };
    let (name, unit) = variable(layer).ok_or("unknown chart layer")?;
    let timeline = if live { fetch_timeline(la, lo, zoom)? }
        else if let Some(cached) = cached_timeline(la, lo, zoom) { cached }
        else if let Some(snapshot) = legacy_snapshot(la, lo) { snapshot }
        else { return Err("no cached IFS map".into()); };
    let selected = timeline.values.get(name).ok_or("missing IFS layer")?;
    let clouds = timeline.values.get("cloud_cover");
    let flow_markers = matches!(layer, "wind" | "rain")
        && timeline.values.contains_key("wind_speed_10m")
        && timeline.values.contains_key("wind_direction_10m")
        && memory_allows_vectors(&fs::read_to_string("/proc/meminfo").unwrap_or_default());
    let lat_span = CHART_LAT_SPAN / 2f64.powi((zoom - 1) as i32);
    let mut frames = Vec::new();
    let base = base_map(la, lo, lat_span);
    for (index, values) in selected.iter().enumerate() {
        let mut canvas = base.clone();
        if layer == "rain" {
            if let Some(clouds) = clouds.and_then(|all| all.get(index)) {
                let field = GridField { lats: timeline.lats.clone(), lons: timeline.lons.clone(),
                    values: clouds.clone(), run_time: String::new() };
                image::imageops::overlay(&mut canvas, &field_rgba(&field, la, lo, "clouds", lat_span), 0, 0);
            }
        }
        let field = GridField { lats: timeline.lats.clone(), lons: timeline.lons.clone(),
            values: values.clone(), run_time: String::new() };
        let city_value = field.sample(la, lo);
        image::imageops::overlay(&mut canvas, &field_rgba(&field, la, lo, layer, lat_span), 0, 0);
        draw_geojson(&mut canvas, COASTLINES_GEOJSON, la, lo, lat_span, image::Rgba([210, 224, 224, 255]));
        draw_geojson(&mut canvas, BOUNDARIES_GEOJSON, la, lo, lat_span, image::Rgba([110, 128, 141, 255]));
        if flow_markers {
            let make_field = |values: Vec<Vec<f64>>| GridField { lats: timeline.lats.clone(),
                lons: timeline.lons.clone(), values, run_time: String::new() };
            let direction = make_field(timeline.values["wind_direction_10m"][index].clone());
            let wind = if layer == "wind" { None } else {
                Some(make_field(timeline.values["wind_speed_10m"][index].clone()))
            };
            draw_flow_markers(&mut canvas, wind.as_ref().unwrap_or(&field), &direction,
                if layer == "rain" { Some(&field) } else { None }, la, lo, lat_span);
        }
        let center = ((CHART_W / 2) as i32, (CHART_H / 2) as i32);
        imageproc::drawing::draw_hollow_circle_mut(&mut canvas, center, 6, image::Rgba([10, 14, 24, 220]));
        imageproc::drawing::draw_filled_circle_mut(&mut canvas, center, 3, image::Rgba([255, 255, 255, 255]));
        let path = state_dir().join(format!("chart-{}-{index}.png", uuid12()));
        canvas.save(&path).map_err(|e| format!("save error: {e}"))?;
        frames.push(json!({ "path": path.to_string_lossy(),
            "valid": timeline.times[index], "value": (city_value * 10.0).round() / 10.0,
            "hasSignal": values.iter().flatten().any(|&v| v >= 0.05) }));
    }
    let scale_km = [50, 25, 10][(zoom - 1) as usize];
    let scale_px = scale_km as f64 * CHART_H as f64 / (lat_span * 111.2);
    Ok(json!({ "frames": frames, "layer": layer, "zoom": zoom, "unit": unit,
        "labels": place_labels(la, lo, lat_span), "scaleKm": scale_km, "scalePx": scale_px,
        "legend": chart_legend(layer),
        "stale": timeline.stale, "snapshot": timeline.times.len() == 1, "model": timeline.model,
        "flowMarkers": flow_markers,
        "licence": "ECMWF IFS · Open-Meteo CC-BY-4.0" }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn weather_map_is_rendered_at_twice_the_original_height() {
        assert_eq!(CHART_H, 640);
    }

    #[test]
    fn flow_markers_require_more_than_eight_gib_of_total_ram() {
        assert!(!memory_allows_vectors("MemTotal: 8388608 kB\n"));
        assert!(memory_allows_vectors("MemTotal: 8388609 kB\n"));
        assert!(!memory_allows_vectors("MemTotal: missing kB\n"));
        assert!(!memory_allows_vectors("MemAvailable: 16777216 kB\n"));
    }

    #[test]
    fn meteorological_wind_bearing_points_downwind_on_the_image() {
        let (dx, dy) = flow_direction(270.0);
        assert!(dx > 0.99 && dy.abs() < 0.01, "wind from west blows east");
        let (dx, dy) = flow_direction(0.0);
        assert!(dx.abs() < 0.01 && dy > 0.99, "wind from north blows south");
    }

    #[test]
    fn interpolated_direction_wraps_across_north() {
        let field = GridField { lats: vec![1.0, 0.0], lons: vec![0.0, 1.0],
            values: vec![vec![350.0, 10.0], vec![350.0, 10.0]], run_time: String::new() };
        let bearing = sample_bearing(&field, 0.5, 0.5);
        assert!(bearing < 20.0 || bearing > 340.0, "got {bearing}");
    }
}

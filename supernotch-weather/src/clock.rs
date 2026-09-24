use fs2::FileExt;
use jiff::{tz::TimeZone, Timestamp};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, HashSet};
use std::env;
use std::error::Error;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn prefs_path() -> Result<PathBuf> {
    if let Some(state_dir) = env::var_os("SUPERNOTCH_CLOCK_STATE_DIR") {
        let state_dir = PathBuf::from(state_dir);
        fs::create_dir_all(&state_dir)?;
        return Ok(state_dir.join("prefs.json"));
    }
    let home = env::var_os("HOME").ok_or("HOME is not set")?;
    Ok(PathBuf::from(home).join(".local/state/omarchy-supernotch/prefs.json"))
}

fn read_prefs(path: &Path) -> Result<Value> {
    let prefs = match fs::read(path) {
        Ok(contents) => serde_json::from_slice(&contents)?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => json!({}),
        Err(error) => return Err(error.into()),
    };
    if !prefs.is_object() {
        return Err("clock preferences must be a JSON object".into());
    }
    Ok(prefs)
}

fn update_prefs(
    path: &Path,
    edit: impl FnOnce(&mut Map<String, Value>) -> Result<()>,
) -> Result<()> {
    let dir = path
        .parent()
        .ok_or("preferences have no parent directory")?;
    fs::create_dir_all(dir)?;
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .mode(0o600)
        .open(dir.join("clock.lock"))?;
    lock.lock_exclusive()?;
    let mut prefs = read_prefs(path)?;
    edit(prefs.as_object_mut().ok_or("invalid clock preferences")?)?;
    let nonce = SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos();
    let temporary = dir.join(format!(".prefs.{}.{}.tmp", std::process::id(), nonce));
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)?;
        serde_json::to_writer(&mut file, &prefs)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn local_zone() -> String {
    if let Ok(zone) = env::var("SUPERNOTCH_CLOCK_LOCAL_TZ") {
        if TimeZone::get(&zone).is_ok() {
            return zone;
        }
    }
    let from_system = fs::canonicalize("/etc/localtime")
        .ok()
        .and_then(|path| {
            path.strip_prefix("/usr/share/zoneinfo/")
                .ok()
                .map(Path::to_path_buf)
        })
        .map(|path| path.to_string_lossy().into_owned())
        .filter(|zone| TimeZone::get(zone).is_ok());
    from_system
        .or_else(|| TimeZone::system().iana_name().map(str::to_owned))
        .unwrap_or_else(|| "UTC".to_owned())
}

fn decimal_coordinate(part: &str, degree_digits: usize) -> Option<f64> {
    let (sign, digits) = match part.as_bytes().first()? {
        b'+' => (1.0, &part[1..]),
        b'-' => (-1.0, &part[1..]),
        _ => return None,
    };
    if !digits.bytes().all(|c| c.is_ascii_digit())
        || (digits.len() != degree_digits + 2 && digits.len() != degree_digits + 4)
    {
        return None;
    }
    let degrees: f64 = digits[..degree_digits].parse().ok()?;
    let minutes: f64 = digits[degree_digits..degree_digits + 2].parse().ok()?;
    let seconds: f64 = if digits.len() == degree_digits + 4 {
        digits[degree_digits + 2..].parse().ok()?
    } else {
        0.0
    };
    if minutes >= 60.0 || seconds >= 60.0 || degrees > if degree_digits == 2 { 90.0 } else { 180.0 }
    {
        return None;
    }
    Some(sign * (degrees + minutes / 60.0 + seconds / 3600.0))
}

fn coordinates(raw: &str) -> Option<(f64, f64)> {
    let second_sign = raw.get(1..)?.find(['+', '-'])? + 1;
    Some((
        decimal_coordinate(&raw[..second_sign], 2)?,
        decimal_coordinate(&raw[second_sign..], 3)?,
    ))
}

fn zone_table() -> BTreeMap<String, (f64, f64)> {
    let mut zones = BTreeMap::new();
    for line in include_str!("../data/clock-zone.tab")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let mut columns = line.split('\t');
        let _country = columns.next();
        if let (Some(raw), Some(name)) = (columns.next(), columns.next()) {
            if let Some(point) = coordinates(raw) {
                zones.insert(name.to_owned(), point);
            }
        }
    }
    zones
}

fn saved_zones(prefs: &Value, local: &str) -> Vec<String> {
    let mut seen = HashSet::from([local.to_owned()]);
    let mut zones = vec![local.to_owned()];
    if let Some(saved) = prefs.get("worldClocks").and_then(Value::as_array) {
        for entry in saved {
            if let Some(zone) = entry.as_str() {
                if TimeZone::get(zone).is_ok() && seen.insert(zone.to_owned()) {
                    zones.push(zone.to_owned());
                }
            }
        }
    }
    zones
}

pub(crate) fn worldclocks(path: &Path) -> Result<Value> {
    let prefs = read_prefs(path)?;
    let local = local_zone();
    let zones = saved_zones(&prefs, &local);
    let selected = prefs
        .get("worldClockActive")
        .and_then(Value::as_str)
        .filter(|zone| zones.iter().any(|saved| saved == zone))
        .unwrap_or(&local);
    let points = zone_table();
    let now = Timestamp::now();
    let clocks: Vec<Value> = zones
        .iter()
        .map(|zone| {
            let zoned = now.to_zoned(TimeZone::get(zone).expect("validated IANA timezone"));
            let time = zoned.time();
            let coords = points.get(zone);
            json!({
                "name": zone.rsplit('/').next().unwrap_or(zone).replace('_', " "),
                "tz": zone,
                "time": format!("{:02}:{:02}", time.hour(), time.minute()),
                "date": zoned.date().to_string(),
                "local": zone == &local,
                "primary": zone == selected,
                "latitude": coords.map(|point| point.0),
                "longitude": coords.map(|point| point.1),
            })
        })
        .collect();
    Ok(Value::Array(clocks))
}

fn change_clock(path: &Path, action: &str, zone: &str) -> Result<Value> {
    if zone.is_empty() || zone.len() > 128 || TimeZone::get(zone).is_err() {
        return Err(format!("invalid timezone: {zone}").into());
    }
    let local = local_zone();
    update_prefs(path, |prefs| {
        let current = Value::Object(prefs.clone());
        let listed = saved_zones(&current, &local);
        match action {
            "worldclock-add" => {
                if zone != local && !listed.iter().any(|saved| saved == zone) {
                    let entries = prefs.entry("worldClocks").or_insert_with(|| json!([]));
                    entries
                        .as_array_mut()
                        .ok_or("worldClocks must be an array")?
                        .push(json!(zone));
                }
            }
            "worldclock-select" => {
                if !listed.iter().any(|saved| saved == zone) {
                    return Err(format!("timezone not on clock: {zone}").into());
                }
                prefs.insert("worldClockActive".to_owned(), json!(zone));
            }
            "worldclock-del" => {
                if zone == local {
                    return Err("cannot remove the system timezone".into());
                }
                if let Some(entries) = prefs.get_mut("worldClocks") {
                    entries
                        .as_array_mut()
                        .ok_or("worldClocks must be an array")?
                        .retain(|item| item.as_str() != Some(zone));
                }
                if prefs.get("worldClockActive").and_then(Value::as_str) == Some(zone) {
                    prefs.insert("worldClockActive".to_owned(), Value::Null);
                }
            }
            _ => return Err("unsupported clock command".into()),
        }
        Ok(())
    })?;
    worldclocks(path)
}

fn zone_matches(query: &str) -> String {
    let query = query.trim().to_lowercase().replace(' ', "_");
    let mut zones: Vec<_> = zone_table()
        .into_keys()
        .filter(|zone| zone.to_lowercase().contains(&query))
        .collect();
    zones.sort_unstable();
    zones.into_iter().take(8).collect::<Vec<_>>().join("\n")
}

pub(crate) fn execute_command(path: &Path, action: &str, argument: &str) -> Result<Value> {
    match action {
        "list" => worldclocks(path),
        "search" => Ok(Value::Array(
            zone_matches(argument)
                .lines()
                .map(|zone| Value::String(zone.to_owned()))
                .collect(),
        )),
        "add" => change_clock(path, "worldclock-add", argument),
        "select" => change_clock(path, "worldclock-select", argument),
        "remove" => change_clock(path, "worldclock-del", argument),
        _ => Err(format!("unsupported clock command: {action}").into()),
    }
}

fn run() -> Result<()> {
    let args: Vec<String> = env::args().collect();
    let command = args.get(1).map(String::as_str).unwrap_or("");
    if command == "--version" {
        println!("supernotch-clock 0.0.2");
        return Ok(());
    }
    let path = prefs_path()?;
    match command {
        "worldclock-list" => println!("{}", worldclocks(&path)?),
        "worldclock-zones" => println!(
            "{}",
            zone_matches(args.get(2).map(String::as_str).unwrap_or(""))
        ),
        "worldclock-add" | "worldclock-select" | "worldclock-del" => {
            let zone = args.get(2).ok_or("missing timezone argument")?;
            println!("{}", change_clock(&path, command, zone)?);
        }
        _ => return Err(format!("unknown clock command: {command}").into()),
    }
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("supernotch-clock: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zone_tab_coordinates_accept_minutes_and_seconds() {
        assert_eq!(
            coordinates("+5540+01235"),
            Some((55.0 + 40.0 / 60.0, 12.0 + 35.0 / 60.0))
        );
        let (lat, lon) = coordinates("+404251-0740023").unwrap();
        assert!((lat - 40.714167).abs() < 0.000001);
        assert!((lon + 74.006389).abs() < 0.000001);
        assert_eq!(coordinates("+5560+01235"), None);
        assert_eq!(coordinates(""), None);
    }

    #[test]
    fn selected_zone_defaults_to_the_local_zone_when_removed() {
        let local = "America/Santiago";
        let prefs = json!({"worldClocks": ["Europe/Copenhagen", "America/Santiago"]});
        assert_eq!(saved_zones(&prefs, local), vec![local, "Europe/Copenhagen"]);
    }

    #[test]
    fn zone_search_accepts_city_names_with_spaces() {
        assert!(zone_matches("New York")
            .lines()
            .any(|zone| zone == "America/New_York"));
    }

    #[test]
    fn command_api_supports_timezone_search_add_select_and_remove() {
        let directory = std::env::temp_dir().join(format!(
            "supernotch-clock-test-{}-{}",
            std::process::id(),
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos(),
        ));
        fs::create_dir_all(&directory).unwrap();
        let prefs = directory.join("prefs.json");
        unsafe {
            std::env::set_var("SUPERNOTCH_CLOCK_LOCAL_TZ", "America/Santiago");
        }

        let search = execute_command(&prefs, "search", "New York").unwrap();
        assert!(search.as_array().unwrap().contains(&json!("America/New_York")));
        let added = execute_command(&prefs, "add", "America/New_York").unwrap();
        assert_eq!(added.as_array().unwrap().len(), 2);
        let selected = execute_command(&prefs, "select", "America/New_York").unwrap();
        assert!(selected.as_array().unwrap()[1]["primary"].as_bool().unwrap());
        let removed = execute_command(&prefs, "remove", "America/New_York").unwrap();
        assert_eq!(removed.as_array().unwrap().len(), 1);

        unsafe {
            std::env::remove_var("SUPERNOTCH_CLOCK_LOCAL_TZ");
        }
        fs::remove_dir_all(directory).unwrap();
    }
}

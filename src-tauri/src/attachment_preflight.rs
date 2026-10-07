use std::time::{Duration, Instant};

use regex::Regex;

#[derive(Clone, Debug, PartialEq)]
pub(crate) struct ExportIdentity {
    vid_pid: String,
    path: String,
}

pub(crate) enum QueryError {
    Unavailable(String),
    InvalidDevice(String),
}

// A reachable USB/IP socket does not mean the selected device is importable.
// In particular, usbipd temporarily removes a device while restoring/capturing it.
pub(crate) fn selected_export(
    content: &str,
    bus_id: &str,
    expected_vid_pid: &str,
) -> Result<Option<ExportIdentity>, String> {
    let header =
        Regex::new(r"^\s*(\d+-\d+(?:\.\d+)*)\s*:\s+.+\(([0-9a-fA-F]{4}:[0-9a-fA-F]{4})\)\s*$")
            .unwrap();
    let mut lines = content.lines();
    while let Some(line) = lines.next() {
        let Some(capture) = header.captures(line) else {
            continue;
        };
        if &capture[1] != bus_id {
            continue;
        }
        if !capture[2].eq_ignore_ascii_case(expected_vid_pid) {
            return Err(format!(
                "设备 {bus_id} 的型号标识已变化，请刷新后重新选择；未提交挂载"
            ));
        }
        let path = lines
            .next()
            .and_then(|line| line.trim().strip_prefix(':'))
            .map(str::trim)
            .filter(|path| !path.is_empty())
            .ok_or_else(|| format!("设备 {bus_id} 的共享信息不完整，尚未提交挂载"))?;
        return Ok(Some(ExportIdentity {
            vid_pid: capture[2].to_ascii_lowercase(),
            path: path.to_string(),
        }));
    }
    // Empty or header-only output is a legitimate transient export list. Errors
    // from the component are checked before calling this parser.
    Ok(None)
}

pub(crate) fn wait_until_ready(
    bus_id: &str,
    read: impl FnMut(Duration) -> Result<Option<ExportIdentity>, QueryError>,
    active: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    let start = Instant::now();
    wait_with_clock(bus_id, read, active, || start.elapsed(), std::thread::sleep)
}

pub(crate) fn prepare(
    executable: &std::path::Path,
    host: &str,
    bus_id: &str,
    expected_vid_pid: &str,
    active: impl FnMut() -> Result<(), String>,
) -> Result<(), String> {
    wait_until_ready(
        bus_id,
        |timeout| {
            let output = crate::process::run(executable, &["list", "-r", host], timeout)
                .map_err(QueryError::Unavailable)?;
            let content = crate::ensure_success(output).map_err(QueryError::Unavailable)?;
            selected_export(&content, bus_id, expected_vid_pid).map_err(QueryError::InvalidDevice)
        },
        active,
    )
}

pub(crate) fn prepare_android(
    executable: &std::path::Path, host:&str, bus_id:&str, expected_vid_pid:&str,
    active:impl FnMut()->Result<(),String>,
) -> Result<(),String> {
    wait_until_ready(bus_id, |timeout| {
        let signed=crate::presence::usb_export(host).map_err(QueryError::Unavailable)?;
        if signed.bus_id!=bus_id || !signed.vid_pid.eq_ignore_ascii_case(expected_vid_pid) {
            return Err(QueryError::InvalidDevice("手机共享设备已变化，请刷新后重新选择".into()));
        }
        let output=crate::process::run(executable,&["list","-r",host],timeout).map_err(QueryError::Unavailable)?;
        let content=crate::ensure_success(output).map_err(QueryError::Unavailable)?;
        let export=selected_export(&content,bus_id,expected_vid_pid).map_err(QueryError::InvalidDevice)?;
        if export.as_ref().is_some_and(|record| record.path!=signed.path) {
            return Err(QueryError::InvalidDevice("手机 USB 记录与认证来源不一致，未提交挂载".into()));
        }
        Ok(export)
    },active)
}

fn wait_with_clock(
    bus_id: &str,
    mut read: impl FnMut(Duration) -> Result<Option<ExportIdentity>, QueryError>,
    mut active: impl FnMut() -> Result<(), String>,
    now: impl Fn() -> Duration,
    mut sleep: impl FnMut(Duration),
) -> Result<(), String> {
    let deadline = now() + Duration::from_secs(15);
    let mut pinned = None;
    let mut stable_since = None;
    let mut samples = 0;
    let mut last_error = None;
    loop {
        active()?;
        let remaining = deadline.saturating_sub(now());
        if remaining.is_zero() {
            return Err(format!(
                "设备 {bus_id} 的共享端尚未就绪，未提交挂载{}。请确认设备仍已共享",
                last_error.map_or(String::new(), |error| format!("：{error}"))
            ));
        }
        let observation = read(remaining.min(Duration::from_secs(3)));
        if now() >= deadline {
            continue;
        }
        match observation {
            Ok(Some(identity)) => {
                if pinned
                    .as_ref()
                    .is_some_and(|previous| previous != &identity)
                {
                    return Err(format!(
                        "设备 {bus_id} 在准备期间发生变化，请刷新后重新选择；未提交挂载"
                    ));
                }
                pinned = Some(identity);
                let since = *stable_since.get_or_insert_with(&now);
                samples += 1;
                last_error = None;
                // Several fresh responses over time, not a fixed sleep followed
                // by an import based on a stale UI list.
                if samples >= 3 && now().saturating_sub(since) >= Duration::from_millis(1500) {
                    active()?;
                    return Ok(());
                }
            }
            Ok(None) => {
                stable_since = None;
                samples = 0;
                last_error = None;
            }
            Err(QueryError::InvalidDevice(error)) => return Err(error),
            Err(QueryError::Unavailable(error)) => {
                stable_since = None;
                samples = 0;
                last_error = Some(error);
            }
        }
        sleep(Duration::from_millis(300).min(deadline.saturating_sub(now())));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    const EXPORT: &str = "Exportable USB devices\n======================\n    3-2 : Test phone (18d1:4ee7)\n        : USB\\VID_18D1&PID_4EE7\\test-device\n        : (Defined at Interface level) (00/00/00)\n";
    fn identity() -> ExportIdentity {
        selected_export(EXPORT, "3-2", "18d1:4ee7")
            .unwrap()
            .unwrap()
    }

    #[test]
    fn export_must_match_selection_and_have_an_instance_path() {
        assert!(selected_export(EXPORT, "3-4", "18d1:4ee7")
            .unwrap()
            .is_none());
        assert!(selected_export(EXPORT, "3-2", "1234:5678").is_err());
        assert!(selected_export("3-2 : Phone (18d1:4ee7)", "3-2", "18d1:4ee7").is_err());
        assert_eq!(
            selected_export(&EXPORT.replace('\n', "\r\n"), "3-2", "18D1:4EE7").unwrap(),
            Some(identity())
        );
    }

    #[test]
    fn waits_for_remote_reenumeration_and_restarts_stability_after_a_gap() {
        let clock = Cell::new(Duration::ZERO);
        let samples = Cell::new(0);
        wait_with_clock(
            "3-2",
            |_| {
                samples.set(samples.get() + 1);
                Ok(match samples.get() {
                    1..=4 | 8 => None,
                    _ => Some(identity()),
                })
            },
            || Ok(()),
            || clock.get(),
            |duration| clock.set(clock.get() + duration),
        )
        .unwrap();
        assert!(
            samples.get() >= 14,
            "an early export or a gap cannot trigger import"
        );
    }

    #[test]
    fn absent_or_unreadable_device_is_never_ready() {
        for failed in [false, true] {
            let clock = Cell::new(Duration::ZERO);
            let error = wait_with_clock(
                "3-2",
                |_| {
                    if failed {
                        Err(QueryError::Unavailable("query failed".into()))
                    } else {
                        Ok(None)
                    }
                },
                || Ok(()),
                || clock.get(),
                |duration| clock.set(clock.get() + duration),
            )
            .unwrap_err();
            assert!(error.contains("未提交挂载"));
            assert_eq!(clock.get(), Duration::from_secs(15));
        }
    }

    #[test]
    fn replacement_or_closing_aborts_before_import() {
        let clock = Cell::new(Duration::ZERO);
        let calls = Cell::new(0);
        let error = wait_with_clock(
            "3-2",
            |_| {
                calls.set(calls.get() + 1);
                let mut id = identity();
                if calls.get() > 1 {
                    id.path = "replacement".into();
                }
                Ok(Some(id))
            },
            || Ok(()),
            || clock.get(),
            |duration| clock.set(clock.get() + duration),
        )
        .unwrap_err();
        assert!(error.contains("发生变化"));
        assert!(wait_with_clock(
            "3-2",
            |_| panic!("must stop before querying"),
            || Err("closing".into()),
            || Duration::ZERO,
            |_| {}
        )
        .unwrap_err()
        .contains("closing"));
    }

    // Explicit opt-in hardware check. Normal cargo test never touches a USB.
    #[test]
    #[ignore = "requires explicit authorization for one real USB connection"]
    fn live_single_manual_connection() {
        let host =
            std::env::var("USBLINK_LIVE_ATTACH_HOST").expect("explicit target host required");
        let bus = std::env::var("USBLINK_LIVE_ATTACH_BUS").expect("explicit target bus required");
        let vid =
            std::env::var("USBLINK_LIVE_ATTACH_VID_PID").expect("explicit target model required");
        crate::validate_host(&host).unwrap();
        crate::validate_bus_id(&bus).unwrap();
        let executable = crate::find_executable("usbip.exe").unwrap();
        crate::require_safe_usbip(&executable).unwrap();
        crate::mesh::require_usb_peer(&host).unwrap();
        let current = crate::connections::list().unwrap();
        if let Some(device) = current.iter().find(|device| device.matches(&host, &bus)) {
            let port = std::env::var("USBLINK_LIVE_ATTACH_DISCONNECT_PORT")
                .ok()
                .and_then(|value| value.parse::<u16>().ok());
            assert_eq!(
                port,
                Some(device.port),
                "target already connected; explicit permission for its exact port is required"
            );
            crate::ensure_success(
                crate::run(&executable, &["detach", "-p", &device.port.to_string()]).unwrap(),
            )
            .unwrap();
            let deadline = Instant::now() + Duration::from_secs(5);
            while crate::connections::list()
                .unwrap()
                .iter()
                .any(|device| device.matches(&host, &bus))
            {
                assert!(
                    Instant::now() < deadline,
                    "target still mounted; no attach submitted"
                );
                std::thread::sleep(Duration::from_millis(100));
            }
        }
        let imports = Cell::new(0);
        let devices = crate::attachment::connect(
            &host,
            &[bus.clone()],
            |bus_id| {
                prepare(&executable, &host, bus_id, &vid, || Ok(())).unwrap();
                crate::mesh::require_usb_peer(&host).unwrap();
                imports.set(imports.get() + 1);
                let output = crate::ensure_success(crate::run(
                    &executable,
                    &["attach", "--once", "--terse", "-r", &host, "-b", bus_id],
                )?)?;
                let port = crate::attachment::parse_import_port(&output)?;
                println!("Single requested attach allocated port {port}");
                Ok(())
            },
            crate::connections::list_with_timeout,
        )
        .unwrap();
        assert_eq!(imports.get(), 1);
        assert!(devices.iter().any(|device| device.matches(&host, &bus)));
        // Read-only observation after confirmation: no cleanup, no retry.
        let start = Instant::now();
        while start.elapsed() < Duration::from_secs(10) {
            std::thread::sleep(Duration::from_millis(500));
            assert!(
                crate::connections::list()
                    .unwrap()
                    .iter()
                    .any(|device| device.matches(&host, &bus)),
                "mount disappeared after initial confirmation"
            );
        }
    }
}

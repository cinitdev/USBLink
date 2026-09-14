use std::collections::HashSet;
use std::thread;
use std::time::{Duration, Instant};

use crate::connections::AttachedDevice;

// usbip-win2's --terse result is the port allocated by the driver, not merely
// an acknowledgement. Keep it so an immediately lost mount can be diagnosed.
pub(crate) fn parse_import_port(output: &str) -> Result<u16, String> {
    output
        .trim()
        .parse::<u16>()
        .ok()
        .filter(|port| (1..=255).contains(port))
        .ok_or_else(|| "USB/IP 未返回有效的挂载端口，连接结果待确认；未重复提交挂载".into())
}

// Submit each requested import exactly once, then only read driver state.
// A successful CLI exit alone must not unlock the UI for another import.
pub(crate) fn connect(
    host: &str,
    bus_ids: &[String],
    import: impl FnMut(&str) -> Result<(), String>,
    read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
) -> Result<Vec<AttachedDevice>, String> {
    connect_with_timeout(
        host,
        bus_ids,
        import,
        read,
        Duration::from_secs(15),
        Duration::from_millis(250),
    )
}

fn connect_with_timeout(
    host: &str,
    bus_ids: &[String],
    mut import: impl FnMut(&str) -> Result<(), String>,
    mut read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
    timeout: Duration,
    interval: Duration,
) -> Result<Vec<AttachedDevice>, String> {
    let mut current = read(Duration::from_secs(5))
        .map_err(|error| format!("连接前无法确认本机挂载状态，未重复提交挂载：{error}"))?;
    let mut submitted = HashSet::new();
    for bus_id in bus_ids {
        if !submitted.insert(bus_id) || current.iter().any(|item| item.matches(host, bus_id)) {
            continue;
        }
        import(bus_id)?;
        let deadline = Instant::now() + timeout;
        let mut last_error = None;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                let detail =
                    last_error.map_or(String::new(), |error| format!("，最近查询错误：{error}"));
                return Err(format!("设备 {bus_id} 的连接请求已提交，但在 {} 秒内未能确认挂载{detail}。请检查设备或刷新状态后再手动重试", timeout.as_secs()));
            }
            match read(remaining.min(Duration::from_secs(2))) {
                Ok(devices) => {
                    current = devices;
                    last_error = None;
                    if current.iter().any(|item| item.matches(host, bus_id)) {
                        break;
                    }
                }
                Err(error) => last_error = Some(error),
            }
            thread::sleep(interval.min(deadline.saturating_duration_since(Instant::now())));
        }
    }
    if bus_ids
        .iter()
        .any(|id| !current.iter().any(|item| item.matches(host, id)))
    {
        return Err("部分 USB 在连接过程中断开，请查看本机挂载状态；未自动重新挂载".into());
    }
    Ok(current)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    #[test]
    fn import_receipt_requires_a_real_driver_port() {
        assert_eq!(parse_import_port("1\r\n").unwrap(), 1);
        assert_eq!(parse_import_port("255").unwrap(), 255);
        for value in ["", "0", "256", "-1", "1\n2", "error", "1 extra"] {
            assert!(parse_import_port(value).is_err(), "{value}");
        }
    }
    fn mounted(host: &str, bus: &str) -> Vec<AttachedDevice> {
        crate::connections::parse(&format!("Imported USB devices\nPort 01: device in use at High Speed(480Mbps)\n Test device (1234:5678)\n -> usbip://{host}:3240/{bus}\n")).unwrap()
    }
    #[test]
    fn one_import_waits_through_empty_and_failed_queries_until_confirmed() {
        let imports = Cell::new(0);
        let reads = Cell::new(0);
        let devices = connect_with_timeout(
            "10.0.0.2",
            &["3-2".into()],
            |_| {
                imports.set(imports.get() + 1);
                Ok(())
            },
            |_| {
                reads.set(reads.get() + 1);
                match reads.get() {
                    1 | 2 => Ok(vec![]),
                    3 => Err("driver initializing".into()),
                    _ => Ok(mounted("10.0.0.2", "3-2")),
                }
            },
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(imports.get(), 1);
        assert_eq!(reads.get(), 4);
        assert_eq!(devices.len(), 1);
    }
    #[test]
    fn timeout_does_not_retry_import_or_report_success() {
        let imports = Cell::new(0);
        let error = connect_with_timeout(
            "10.0.0.2",
            &["3-2".into()],
            |_| {
                imports.set(imports.get() + 1);
                Ok(())
            },
            |_| Ok(vec![]),
            Duration::from_millis(20),
            Duration::from_millis(1),
        )
        .err()
        .unwrap();
        assert!(error.contains("未能确认挂载"));
        assert_eq!(imports.get(), 1);
    }
    #[test]
    fn existing_mounts_and_duplicate_selections_do_not_import_again() {
        let imports = Cell::new(0);
        connect(
            "10.0.0.2",
            &["3-2".into(), "3-2".into()],
            |_| {
                imports.set(imports.get() + 1);
                Ok(())
            },
            |_| Ok(mounted("10.0.0.2", "3-2")),
        )
        .unwrap();
        assert_eq!(imports.get(), 0);
        let reads = Cell::new(0);
        connect(
            "10.0.0.2",
            &["3-2".into(), "3-2".into()],
            |_| {
                imports.set(imports.get() + 1);
                Ok(())
            },
            |_| {
                reads.set(reads.get() + 1);
                Ok(if reads.get() == 1 {
                    vec![]
                } else {
                    mounted("10.0.0.2", "3-2")
                })
            },
        )
        .unwrap();
        assert_eq!(imports.get(), 1);
    }
    #[test]
    fn a_matching_bus_on_another_host_does_not_skip_import() {
        let imports = Cell::new(0);
        let reads = Cell::new(0);
        connect(
            "10.0.0.2",
            &["3-2".into()],
            |_| {
                imports.set(imports.get() + 1);
                Ok(())
            },
            |_| {
                reads.set(reads.get() + 1);
                Ok(mounted(
                    if reads.get() == 1 {
                        "10.0.0.3"
                    } else {
                        "10.0.0.2"
                    },
                    "3-2",
                ))
            },
        )
        .unwrap();
        assert_eq!(imports.get(), 1);
    }
    #[test]
    fn six_devices_are_imported_serially_after_each_previous_confirmation() {
        let imported = Cell::new(0usize);
        let confirmed = Cell::new(0usize);
        let waiting = Cell::new(false);
        let ids: Vec<String> = (1..=6).map(|index| format!("3-{index}")).collect();
        let devices = connect_with_timeout(
            "10.0.0.2",
            &ids,
            |_| {
                assert_eq!(
                    imported.get(),
                    confirmed.get(),
                    "next import must wait for confirmation"
                );
                imported.set(imported.get() + 1);
                waiting.set(true);
                Ok(())
            },
            |_| {
                if waiting.replace(false) {
                    return Ok(vec![]);
                }
                confirmed.set(imported.get());
                Ok(ids
                    .iter()
                    .take(confirmed.get())
                    .enumerate()
                    .map(|(index, id)| {
                        let mut device = mounted("10.0.0.2", id).remove(0);
                        device.port = index as u16 + 1;
                        device
                    })
                    .collect())
            },
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(devices.len(), 6);
        assert_eq!(imported.get(), 6);
    }

    #[test]
    fn preflight_and_import_failures_are_not_silently_retried() {
        assert!(connect(
            "10.0.0.2",
            &["3-2".into()],
            |_| panic!("must not import with unknown state"),
            |_| Err("driver unavailable".into())
        )
        .is_err());
        let imports = Cell::new(0);
        assert!(connect(
            "10.0.0.2",
            &["3-2".into(), "3-3".into()],
            |_| {
                imports.set(imports.get() + 1);
                Err("device offline".into())
            },
            |_| Ok(vec![])
        )
        .is_err());
        assert_eq!(imports.get(), 1);
    }
}

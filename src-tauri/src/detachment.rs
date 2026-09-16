use crate::connections::AttachedDevice;
use serde::Deserialize;
use std::time::{Duration, Instant};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DetachTarget {
    host: String,
    bus_id: String,
    vid_pid: String,
    port: u16,
}

impl DetachTarget {
    fn matches(&self, device: &AttachedDevice) -> bool {
        device.matches_identity(&self.host, &self.bus_id, &self.vid_pid)
    }
}

pub(crate) fn disconnect_one(
    target: &DetachTarget,
    detach: impl FnOnce(u16) -> Result<(), String>,
    mut read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
    timeout: Duration,
    interval: Duration,
) -> Result<Vec<AttachedDevice>, String> {
    crate::validate_host(&target.host)?;
    crate::validate_bus_id(&target.bus_id)?;
    if !(1..=255).contains(&target.port)
        || !regex::Regex::new(r"^[0-9a-fA-F]{4}:[0-9a-fA-F]{4}$")
            .unwrap()
            .is_match(&target.vid_pid)
    {
        return Err("USB 挂载标识无效，请刷新后重试".into());
    }
    let current = read(Duration::from_secs(5))?;
    match current.iter().find(|device| device.port == target.port) {
        Some(device) if target.matches(device) => {}
        None if !current.iter().any(|device| target.matches(device)) => return Ok(current),
        _ => return Err("所选 USB 的挂载端口已变化，未执行断开；请刷新后重新选择".into()),
    }
    confirm(
        || detach(target.port),
        read,
        |devices| !devices.iter().any(|device| target.matches(device)),
        &format!("设备 {} 已断开", target.bus_id),
        timeout,
        interval,
    )
}

// Submit detach exactly once and confirm the driver has released all ports.
pub(crate) fn disconnect(
    detach: impl FnOnce() -> Result<(), String>,
    read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
    timeout: Duration,
    interval: Duration,
) -> Result<(), String> {
    confirm(
        detach,
        read,
        |devices| devices.is_empty(),
        "接收端 USB 已全部断开",
        timeout,
        interval,
    )
    .map(|_| ())
}

fn confirm(
    detach: impl FnOnce() -> Result<(), String>,
    mut read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
    complete: impl Fn(&[AttachedDevice]) -> bool,
    expected: &str,
    timeout: Duration,
    interval: Duration,
) -> Result<Vec<AttachedDevice>, String> {
    let command_error = detach().err();
    let deadline = Instant::now() + timeout;
    let mut detail = String::new();
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(format!(
                "无法确认{expected}：{detail}{}",
                command_error.map_or(String::new(), |e| format!("；{e}"))
            ));
        }
        match read(remaining.min(Duration::from_secs(2))) {
            Ok(devices) if complete(&devices) => return Ok(devices),
            Ok(devices) => detail = format!("仍有 {} 个挂载", devices.len()),
            Err(error) => detail = error,
        }
        std::thread::sleep(interval.min(deadline.saturating_duration_since(Instant::now())));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn mounts() -> Vec<AttachedDevice> {
        crate::connections::parse("Port 01: device in use at High Speed\n Phone (1234:5678)\n -> usbip://10.0.0.2:3240/3-2\n").unwrap()
    }

    fn target() -> DetachTarget {
        DetachTarget {
            host: "10.0.0.2".into(),
            bus_id: "3-2".into(),
            vid_pid: "1234:5678".into(),
            port: 1,
        }
    }

    fn other_mounts() -> Vec<AttachedDevice> {
        crate::connections::parse("Port 02: device in use at High Speed\n Phone (1234:5678)\n -> usbip://10.0.0.3:3240/3-2\nPort 03: device in use at High Speed\n Phone (1234:5678)\n -> usbip://10.0.0.2:3240/3-3\n").unwrap()
    }

    #[test]
    fn single_disconnect_confirms_only_the_selected_mount_and_preserves_others() {
        let mut ports = vec![];
        let mut reads = 0;
        let remaining = disconnect_one(
            &target(),
            |port| {
                ports.push(port);
                Ok(())
            },
            |_| {
                reads += 1;
                match reads {
                    1 | 2 => Ok([mounts(), other_mounts()].concat()),
                    3 => Err("temporary query failure".into()),
                    _ => Ok(other_mounts()),
                }
            },
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(ports, [1]);
        assert_eq!(reads, 4);
        assert_eq!(remaining.len(), 2);
        assert!(remaining[0].matches("10.0.0.3", "3-2"));
        assert!(remaining[1].matches("10.0.0.2", "3-3"));
    }

    #[test]
    fn stale_or_unreadable_mount_identity_cannot_disconnect_a_reused_port() {
        for changed in [
            DetachTarget {
                host: "10.0.0.3".into(),
                ..target()
            },
            DetachTarget {
                bus_id: "3-3".into(),
                ..target()
            },
            DetachTarget {
                vid_pid: "1234:5679".into(),
                ..target()
            },
            DetachTarget {
                port: 2,
                ..target()
            },
            DetachTarget {
                port: 0,
                ..target()
            },
            DetachTarget {
                port: 256,
                ..target()
            },
        ] {
            assert!(disconnect_one(
                &changed,
                |_| panic!("must not detach"),
                |_| Ok(mounts()),
                Duration::from_secs(1),
                Duration::ZERO
            )
            .is_err());
        }
        assert!(disconnect_one(
            &target(),
            |_| panic!("must not detach"),
            |_| Err("query failed".into()),
            Duration::from_secs(1),
            Duration::ZERO
        )
        .is_err());
        let remaining = disconnect_one(
            &target(),
            |_| panic!("already absent"),
            |_| Ok(other_mounts()),
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(remaining.len(), 2);
    }

    #[test]
    fn single_disconnect_failure_or_timeout_never_reissues_a_command() {
        let mut calls = 0;
        assert!(disconnect_one(
            &target(),
            |_| {
                calls += 1;
                Ok(())
            },
            |_| Ok(mounts()),
            Duration::from_millis(10),
            Duration::ZERO
        )
        .is_err());
        assert_eq!(calls, 1);
        let mut reads = 0;
        let remaining = disconnect_one(
            &target(),
            |_| Err("command failed".into()),
            |_| {
                reads += 1;
                if reads == 1 {
                    Ok(mounts())
                } else {
                    Ok(other_mounts())
                }
            },
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(remaining.len(), 2);
    }
    #[test]
    fn waits_for_actual_release_without_reissuing_detach() {
        let mut calls = 0;
        let mut reads = 0;
        disconnect(
            || {
                calls += 1;
                Ok(())
            },
            |_| {
                reads += 1;
                match reads {
                    1 => Ok(mounts()),
                    2 => Err("busy".into()),
                    _ => Ok(vec![]),
                }
            },
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
        assert_eq!(calls, 1);
        assert_eq!(reads, 3);
    }
    #[test]
    fn success_exit_without_driver_confirmation_is_a_failure() {
        assert!(disconnect(
            || Ok(()),
            |_| Ok(mounts()),
            Duration::from_millis(10),
            Duration::ZERO
        )
        .is_err());
        assert!(disconnect(
            || Ok(()),
            |_| Err("query failed".into()),
            Duration::from_millis(10),
            Duration::ZERO
        )
        .is_err());
    }
    #[test]
    fn failed_command_can_only_succeed_after_empty_driver_confirmation() {
        disconnect(
            || Err("already gone".into()),
            |_| Ok(vec![]),
            Duration::from_secs(1),
            Duration::ZERO,
        )
        .unwrap();
    }
}

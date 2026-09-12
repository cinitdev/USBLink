use crate::connections::AttachedDevice;
use std::time::{Duration, Instant};

// Submit detach exactly once and confirm the driver has released all ports.
pub(crate) fn disconnect(
    detach: impl FnOnce() -> Result<(), String>,
    mut read: impl FnMut(Duration) -> Result<Vec<AttachedDevice>, String>,
    timeout: Duration,
    interval: Duration,
) -> Result<(), String> {
    let command_error = detach().err();
    let deadline = Instant::now() + timeout;
    let mut detail = String::new();
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(format!(
                "无法确认接收端 USB 已全部断开：{detail}{}",
                command_error.map_or(String::new(), |e| format!("；{e}"))
            ));
        }
        match read(remaining.min(Duration::from_secs(2))) {
            Ok(devices) if devices.is_empty() => return Ok(()),
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

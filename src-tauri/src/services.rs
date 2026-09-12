use serde::{Deserialize, Serialize};
use std::ptr::null;
use std::thread;
use std::time::{Duration, Instant};
use windows_sys::Win32::Foundation::{
    GetLastError, ERROR_SERVICE_ALREADY_RUNNING, ERROR_SERVICE_CANNOT_ACCEPT_CTRL,
    ERROR_SERVICE_DOES_NOT_EXIST, ERROR_SERVICE_NOT_ACTIVE,
};
use windows_sys::Win32::System::Services::*;

struct Handle(SC_HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            CloseServiceHandle(self.0);
        }
    }
}

fn failure(action: &str, code: u32) -> String {
    format!(
        "{action}：{}",
        std::io::Error::from_raw_os_error(code as i32)
    )
}

fn open(name: &str, access: u32) -> Result<Option<Handle>, String> {
    let manager = unsafe { OpenSCManagerW(null(), null(), SC_MANAGER_CONNECT) };
    if manager.is_null() {
        return Err(failure("无法访问 Windows 服务管理器", unsafe {
            GetLastError()
        }));
    }
    let manager = Handle(manager);
    let name: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
    let service = unsafe { OpenServiceW(manager.0, name.as_ptr(), access) };
    if service.is_null() {
        let code = unsafe { GetLastError() };
        if code == ERROR_SERVICE_DOES_NOT_EXIST {
            Ok(None)
        } else {
            Err(failure("无法读取网络服务", code))
        }
    } else {
        Ok(Some(Handle(service)))
    }
}

fn query(handle: &Handle) -> Result<u32, String> {
    let mut status = SERVICE_STATUS::default();
    if unsafe { QueryServiceStatus(handle.0, &mut status) } == 0 {
        return Err(failure("无法查询网络服务状态", unsafe {
            GetLastError()
        }));
    }
    Ok(status.dwCurrentState)
}

pub(crate) fn state(name: &str) -> Result<Option<u32>, String> {
    open(name, SERVICE_QUERY_STATUS)?
        .as_ref()
        .map(query)
        .transpose()
}

#[derive(Clone, Deserialize, Serialize)]
pub(crate) struct Configuration {
    command: Vec<u16>,
    start_type: u32,
}

// Contains a pairing secret in the service command. Only transport inside a
// DPAPI-protected task; deliberately do not implement Debug.
#[derive(Clone, Deserialize, Serialize)]
pub(crate) struct Snapshot {
    pub(crate) configuration: Option<Configuration>,
    pub(crate) was_running: bool,
}

pub(crate) fn snapshot(name: &str) -> Result<Snapshot, String> {
    Ok(Snapshot {
        configuration: configuration(name)?,
        was_running: state(name)?.is_some_and(|value| value != SERVICE_STOPPED),
    })
}

pub(crate) fn configuration(name: &str) -> Result<Option<Configuration>, String> {
    let Some(handle) = open(name, SERVICE_QUERY_CONFIG)? else {
        return Ok(None);
    };
    let mut needed = 0;
    unsafe {
        QueryServiceConfigW(handle.0, std::ptr::null_mut(), 0, &mut needed);
    }
    if needed == 0 {
        return Err(failure("无法读取服务配置", unsafe {
            GetLastError()
        }));
    }
    let mut buffer = vec![0usize; (needed as usize).div_ceil(std::mem::size_of::<usize>())];
    let config = buffer.as_mut_ptr().cast::<QUERY_SERVICE_CONFIGW>();
    if unsafe { QueryServiceConfigW(handle.0, config, needed, &mut needed) } == 0 {
        return Err(failure("无法读取服务配置", unsafe {
            GetLastError()
        }));
    }
    let config = unsafe { &*config };
    let mut command = Vec::new();
    if !config.lpBinaryPathName.is_null() {
        let mut cursor = config.lpBinaryPathName;
        loop {
            let ch = unsafe { *cursor };
            command.push(ch);
            if ch == 0 {
                break;
            }
            cursor = unsafe { cursor.add(1) };
        }
    } else {
        return Err("服务命令行为空，无法安全备份配置".into());
    }
    Ok(Some(Configuration {
        command,
        start_type: config.dwStartType,
    }))
}

pub(crate) fn restore(name: &str, config: &Configuration) -> Result<(), String> {
    if config.command.len() < 2
        || config.command.last() != Some(&0)
        || config.command[..config.command.len() - 1].contains(&0)
    {
        return Err("服务备份包含无效的命令行，未执行恢复".into());
    }
    stop_and_wait(name)?;
    let handle = open(name, SERVICE_CHANGE_CONFIG)?.ok_or("原网络服务已不存在，无法恢复")?;
    let ok = unsafe {
        ChangeServiceConfigW(
            handle.0,
            SERVICE_NO_CHANGE,
            config.start_type,
            SERVICE_NO_CHANGE,
            config.command.as_ptr(),
            null(),
            std::ptr::null_mut(),
            null(),
            null(),
            null(),
            null(),
        )
    };
    if ok == 0 {
        return Err(failure("无法恢复服务配置", unsafe {
            GetLastError()
        }));
    }
    Ok(())
}

#[derive(Debug, PartialEq)]
enum StopAction {
    Done,
    Wait,
    Request,
}

fn stop_action(state: u32, requested: bool) -> StopAction {
    match state {
        SERVICE_STOPPED => StopAction::Done,
        SERVICE_START_PENDING | SERVICE_STOP_PENDING => StopAction::Wait,
        _ if requested => StopAction::Wait,
        _ => StopAction::Request,
    }
}

pub(crate) fn stop_and_wait(name: &str) -> Result<(), String> {
    let Some(handle) = open(name, SERVICE_QUERY_STATUS | SERVICE_STOP)? else {
        return Ok(());
    };
    let start = Instant::now();
    let mut requested = false;
    loop {
        match stop_action(query(&handle)?, requested) {
            StopAction::Done => return Ok(()),
            StopAction::Request => {
                let mut status = SERVICE_STATUS::default();
                if unsafe { ControlService(handle.0, SERVICE_CONTROL_STOP, &mut status) } == 0 {
                    let code = unsafe { GetLastError() };
                    // A transition can race the preceding status read. Query again
                    // rather than treating an already stopped service as failure.
                    if code != ERROR_SERVICE_NOT_ACTIVE && code != ERROR_SERVICE_CANNOT_ACCEPT_CTRL
                    {
                        return Err(failure("无法停止网络服务", code));
                    }
                } else {
                    requested = true;
                }
            }
            StopAction::Wait => {}
        }
        if start.elapsed() >= Duration::from_secs(15) {
            return Err("网络服务未能在 15 秒内停止，未继续修改配置，请稍后重试".into());
        }
        thread::sleep(Duration::from_millis(100));
    }
}

pub(crate) fn start(name: &str) -> Result<(), String> {
    let handle =
        open(name, SERVICE_QUERY_STATUS | SERVICE_START)?.ok_or("网络服务尚未安装，请修复连接")?;
    if matches!(query(&handle)?, SERVICE_RUNNING | SERVICE_START_PENDING) {
        return Ok(());
    }
    if unsafe { StartServiceW(handle.0, 0, null()) } == 0 {
        let code = unsafe { GetLastError() };
        if code != ERROR_SERVICE_ALREADY_RUNNING {
            return Err(failure("无法启动网络服务", code));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_service_can_be_queried_without_administrator_rights() {
        assert_eq!(state("USBLinkReadOnlyMissingProbe").unwrap(), None);
        assert!(snapshot("USBLinkReadOnlyMissingProbe")
            .unwrap()
            .configuration
            .is_none());
    }
    #[test]
    fn rollback_snapshot_preserves_exact_command_and_stopped_state_through_dpapi() {
        let command: Vec<u16> =
            "\"C:\\带空格路径\\easytier-core.exe\" --network-secret secret-test-value\0"
                .encode_utf16()
                .collect();
        let snapshot = Snapshot {
            configuration: Some(Configuration {
                command: command.clone(),
                start_type: SERVICE_DEMAND_START,
            }),
            was_running: false,
        };
        let encrypted = crate::mesh::protect(&serde_json::to_vec(&snapshot).unwrap()).unwrap();
        let decoded: Snapshot =
            serde_json::from_slice(&crate::mesh::unprotect(&encrypted).unwrap()).unwrap();
        assert!(!decoded.was_running);
        let configuration = decoded.configuration.unwrap();
        assert_eq!(configuration.command, command);
        assert_eq!(configuration.start_type, SERVICE_DEMAND_START);
    }
    #[test]
    fn invalid_restore_command_is_rejected_before_accessing_services() {
        for command in [vec![], vec![65], vec![0], vec![65, 0, 66, 0]] {
            let configuration = Configuration {
                command,
                start_type: SERVICE_AUTO_START,
            };
            assert!(restore("USBLinkReadOnlyMissingProbe", &configuration)
                .unwrap_err()
                .contains("无效的命令行"));
        }
    }
    #[test]
    fn pending_transitions_do_not_trigger_repeated_stop_or_early_reconfigure() {
        assert_eq!(stop_action(SERVICE_STOP_PENDING, false), StopAction::Wait);
        assert_eq!(stop_action(SERVICE_START_PENDING, false), StopAction::Wait);
        assert_eq!(stop_action(SERVICE_RUNNING, false), StopAction::Request);
        assert_eq!(stop_action(SERVICE_RUNNING, true), StopAction::Wait);
        assert_eq!(stop_action(SERVICE_STOPPED, true), StopAction::Done);
    }
}

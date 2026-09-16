use serde::{Deserialize, Serialize};
use std::env;
use std::ffi::OsStr;
use std::fs;
use std::mem::size_of;
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use uuid::Uuid;
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_CANCELLED, WAIT_FAILED, WAIT_OBJECT_0,
};
use windows_sys::Win32::System::Threading::{GetExitCodeProcess, WaitForSingleObject, INFINITE};
use windows_sys::Win32::UI::Shell::{ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW};
use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;

use crate::mesh::{self, MeshProfile};
use crate::{ensure_success, run};

const TASK_ARGUMENT: &str = "--usblink-elevated-task";

#[derive(Clone, Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PrivilegedTask {
    MeshApply {
        profile: MeshProfile,
    },
    MeshRemove,
    MeshRestart,
    MeshRestore {
        snapshot: crate::services::Snapshot,
    },
    UsbShare {
        usbipd: PathBuf,
        bus_ids: Vec<String>,
        mesh_ip: String,
    },
    UsbAccess {
        mesh_ip: String,
    },
    SessionAccess {
        mesh_ip: String,
    },
    UsbUnshare {
        usbipd: PathBuf,
        bus_id: String,
    },
    UsbUnshareMany {
        usbipd: PathBuf,
        guids: Vec<String>,
    },
}

#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct TaskResult {
    completed: bool,
    error: Option<String>,
}

fn wide(value: &OsStr) -> Vec<u16> {
    value.encode_wide().chain(std::iter::once(0)).collect()
}

fn runas(task_path: &Path) -> Result<u32, String> {
    let executable =
        env::current_exe().map_err(|error| format!("无法读取 USBLink 程序路径：{error}"))?;
    let verb = wide(OsStr::new("runas"));
    let file = wide(executable.as_os_str());
    let parameters = wide(OsStr::new(&format!(
        "{TASK_ARGUMENT} \"{}\"",
        task_path.display()
    )));
    let directory = executable.parent().map(|path| wide(path.as_os_str()));
    let mut info = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        hwnd: null_mut(),
        lpVerb: verb.as_ptr(),
        lpFile: file.as_ptr(),
        lpParameters: parameters.as_ptr(),
        lpDirectory: directory.as_ref().map_or(null(), |value| value.as_ptr()),
        nShow: SW_HIDE,
        hInstApp: null_mut(),
        lpIDList: null_mut(),
        lpClass: null(),
        hkeyClass: null_mut(),
        dwHotKey: 0,
        Anonymous: Default::default(),
        hProcess: null_mut(),
    };
    let started = unsafe { ShellExecuteExW(&mut info) };
    if started == 0 {
        let code = unsafe { GetLastError() };
        return if code == ERROR_CANCELLED {
            Err("已取消管理员授权".into())
        } else {
            Err(format!(
                "无法启动管理员辅助进程：{}",
                std::io::Error::from_raw_os_error(code as i32)
            ))
        };
    }
    if info.hProcess.is_null() {
        return Err("Windows 未返回管理员进程句柄".into());
    }
    let wait = unsafe { WaitForSingleObject(info.hProcess, INFINITE) };
    if wait == WAIT_FAILED || wait != WAIT_OBJECT_0 {
        unsafe { CloseHandle(info.hProcess) };
        return Err("等待管理员操作完成时失败".into());
    }
    let mut exit_code = 1u32;
    let read_exit = unsafe { GetExitCodeProcess(info.hProcess, &mut exit_code) };
    unsafe { CloseHandle(info.hProcess) };
    if read_exit == 0 {
        return Err("无法读取管理员操作结果".into());
    }
    Ok(exit_code)
}

pub fn execute(task: PrivilegedTask) -> Result<(), String> {
    let directory = mesh::app_root()?.join("tasks");
    fs::create_dir_all(&directory).map_err(|error| format!("无法创建管理员任务目录：{error}"))?;
    let path = directory.join(format!("{}.task", Uuid::new_v4().simple()));
    let json = serde_json::to_vec(&task).map_err(|error| format!("无法准备管理员任务：{error}"))?;
    fs::write(&path, mesh::protect(&json)?)
        .map_err(|error| format!("无法保存管理员任务：{error}"))?;
    let elevated = runas(&path);
    let result = fs::read(&path)
        .ok()
        .and_then(|data| mesh::unprotect(&data).ok())
        .and_then(|data| serde_json::from_slice::<TaskResult>(&data).ok());
    let _ = fs::remove_file(&path);
    let exit_code = elevated?;
    confirm_result(exit_code, result)
}

fn confirm_result(exit_code: u32, result: Option<TaskResult>) -> Result<(), String> {
    let result = result
        .filter(|value| value.completed)
        .ok_or("无法确认管理员操作结果，请刷新状态后再决定是否重试")?;
    if let Some(error) = result.error {
        Err(error)
    } else if exit_code != 0 {
        Err(format!("管理员操作失败，退出代码 {exit_code}"))
    } else {
        Ok(())
    }
}

pub fn task_path_from_args() -> Option<PathBuf> {
    let mut arguments = env::args_os();
    arguments.next()?;
    while let Some(argument) = arguments.next() {
        if argument == TASK_ARGUMENT {
            return arguments.next().map(PathBuf::from);
        }
    }
    None
}

pub fn execute_task_file(path: &Path) -> i32 {
    let result = (|| {
        let encrypted = fs::read(path).map_err(|error| format!("无法读取管理员任务：{error}"))?;
        let data = mesh::unprotect(&encrypted)?;
        let task: PrivilegedTask = serde_json::from_slice(&data)
            .map_err(|error| format!("管理员任务内容无效：{error}"))?;
        dispatch(task)
    })();
    let output = TaskResult {
        completed: true,
        error: result.as_ref().err().cloned(),
    };
    if let Ok(json) = serde_json::to_vec(&output) {
        if let Ok(encrypted) = mesh::protect(&json) {
            let _ = fs::write(path, encrypted);
        }
    }
    if result.is_ok() {
        0
    } else {
        1
    }
}

fn dispatch(task: PrivilegedTask) -> Result<(), String> {
    match task {
        PrivilegedTask::MeshApply { profile } => mesh::apply_service_elevated(&profile),
        PrivilegedTask::MeshRemove => mesh::remove_service_elevated(),
        PrivilegedTask::MeshRestart => mesh::restart_service_elevated(),
        PrivilegedTask::MeshRestore { snapshot } => mesh::restore_service_elevated(&snapshot),
        PrivilegedTask::UsbShare {
            usbipd,
            bus_ids,
            mesh_ip,
        } => {
            // Recheck after UAC: the UI snapshot may no longer describe this bus.
            crate::ensure_local_share_targets(&usbipd, &bus_ids)?;
            configure_usb_access(&mesh_ip)?;
            for bus_id in bus_ids {
                crate::ensure_local_share_targets(&usbipd, std::slice::from_ref(&bus_id))?;
                let output = run(&usbipd, &["bind", "--busid", &bus_id])?;
                if !output.status.success() {
                    let message = crate::text(&output);
                    if !message.to_ascii_lowercase().contains("already") {
                        return Err(message);
                    }
                }
            }
            Ok(())
        }
        PrivilegedTask::UsbAccess { mesh_ip } => configure_usb_access(&mesh_ip),
        PrivilegedTask::SessionAccess { mesh_ip } => configure_session_access(&mesh_ip),
        PrivilegedTask::UsbUnshare { usbipd, bus_id } => {
            ensure_success(run(&usbipd, &["unbind", "--busid", &bus_id])?)?;
            Ok(())
        }
        PrivilegedTask::UsbUnshareMany { usbipd, guids } => {
            crate::sharing_session::clear_elevated(&usbipd, &guids)
        }
    }
}

const SESSION_RULE: &str = "USBLink application session";

fn session_rule_matches(content: &str, mesh_ip: &str) -> bool {
    let Ok(subnet) = crate::mesh_subnet(mesh_ip) else {
        return false;
    };
    let mask_subnet = subnet.replace("/24", "/255.255.255.0");
    content.lines().any(|line| {
        let fields: Vec<_> = line.trim().split('|').collect();
        let has = |value: &str| fields.iter().any(|field| field.eq_ignore_ascii_case(value));
        has(&format!("Name={SESSION_RULE}"))
            && has("Active=TRUE")
            && has("Dir=In")
            && has("Action=Allow")
            && has("Protocol=6")
            && has("LPort=3241")
            && (has(&format!("LA4={mesh_ip}")) || has(&format!("LA4={mesh_ip}/255.255.255.255")))
            && (has(&format!("RA4={subnet}")) || has(&format!("RA4={mask_subnet}")))
    })
}

pub(crate) fn ensure_session_access(mesh_ip: &str) -> Result<(), String> {
    crate::mesh_subnet(mesh_ip)?;
    if session_access_current(mesh_ip)? {
        return Ok(());
    }
    execute(PrivilegedTask::SessionAccess {
        mesh_ip: mesh_ip.into(),
    })?;
    if !session_access_current(mesh_ip)? {
        return Err("无法确认 USBLink 会话访问规则已启用，请修复连接".into());
    }
    Ok(())
}

fn session_access_current(mesh_ip: &str) -> Result<bool, String> {
    // Firewall registry fields are stable across Windows display languages.
    let output = run(
        Path::new("reg.exe"),
        &[
            "query",
            r"HKLM\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\FirewallRules",
            "/s",
            "/f",
            SESSION_RULE,
            "/d",
        ],
    )?;
    Ok(output.status.success() && session_rule_matches(&crate::text(&output), mesh_ip))
}

fn configure_session_access(mesh_ip: &str) -> Result<(), String> {
    let remote_ip = format!("remoteip={}", crate::mesh_subnet(mesh_ip)?);
    let local_ip = format!("localip={mesh_ip}");
    let rule = format!("name={SESSION_RULE}");
    let _ = run(
        Path::new("netsh.exe"),
        &["advfirewall", "firewall", "delete", "rule", &rule],
    );
    ensure_success(run(
        Path::new("netsh.exe"),
        &[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &rule,
            "dir=in",
            "action=allow",
            "protocol=TCP",
            "localport=3241",
            &local_ip,
            &remote_ip,
            "profile=any",
            "enable=yes",
        ],
    )?)
    .map(|_| ())
}

fn configure_usb_access(mesh_ip: &str) -> Result<(), String> {
    let old_rule = "name=USBLink over Tailscale";
    let rule = "name=USBLink over EasyTier";
    let remote_ip = format!("remoteip={}", crate::mesh_subnet(mesh_ip)?);
    ensure_success(run(
        Path::new("sc.exe"),
        &["config", "usbipd", "start=", "auto"],
    )?)?;
    let start = run(Path::new("sc.exe"), &["start", "usbipd"])?;
    if !start.status.success() && !crate::text(&start).contains("1056") {
        return Err(format!(
            "无法启动 usbipd-win 后台服务：{}",
            crate::text(&start)
        ));
    }
    let _ = run(
        Path::new("netsh.exe"),
        &["advfirewall", "firewall", "delete", "rule", old_rule],
    );
    let _ = run(
        Path::new("netsh.exe"),
        &["advfirewall", "firewall", "delete", "rule", rule],
    );
    ensure_success(run(
        Path::new("netsh.exe"),
        &[
            "advfirewall",
            "firewall",
            "add",
            "rule",
            rule,
            "dir=in",
            "action=allow",
            "protocol=TCP",
            "localport=3240-3241",
            &remote_ip,
            "profile=any",
            "enable=yes",
        ],
    )?)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn session_rule_requires_enabled_inbound_tcp_and_current_mesh_scope() {
        let rule = "v2.32|Action=Allow|Active=TRUE|Dir=In|Protocol=6|LPort=3241|LA4=10.0.0.1|RA4=10.0.0.0/255.255.255.0|Name=USBLink application session|";
        assert!(session_rule_matches(rule, "10.0.0.1"));
        assert!(!session_rule_matches(rule, "10.0.1.1"));
        for (from, to) in [
            ("Active=TRUE", "Active=FALSE"),
            ("Dir=In", "Dir=Out"),
            ("LPort=3241", "LPort=3240"),
            ("Action=Allow", "Action=Block"),
            ("RA4=10.0.0.0/255.255.255.0", "RA4=*"),
        ] {
            assert!(!session_rule_matches(&rule.replace(from, to), "10.0.0.1"));
        }
    }

    #[test]
    fn missing_or_unchanged_task_file_cannot_report_success() {
        assert!(confirm_result(0, None).is_err());
        assert!(serde_json::from_str::<TaskResult>(r#"{"kind":"mesh_restart"}"#).is_err());
        assert!(confirm_result(
            0,
            Some(TaskResult {
                completed: false,
                error: None
            })
        )
        .is_err());
        assert!(confirm_result(
            0,
            Some(TaskResult {
                completed: true,
                error: None
            })
        )
        .is_ok());
        assert!(confirm_result(
            1,
            Some(TaskResult {
                completed: true,
                error: None
            })
        )
        .is_err());
    }

    #[test]
    fn privileged_tasks_do_not_embed_shell_commands() {
        let task = PrivilegedTask::UsbShare {
            usbipd: PathBuf::from(r"C:\Program Files\usbipd-win\usbipd.exe"),
            bus_ids: vec!["1-4".into(), "2-2.1".into()],
            mesh_ip: "10.126.126.1".into(),
        };
        let json = serde_json::to_string(&task).unwrap();
        assert!(json.contains("usb_share"));
        assert!(!json.contains("powershell"));
    }
}

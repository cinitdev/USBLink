use serde::Serialize;
use std::collections::HashSet;
use std::fs::{File, OpenOptions};
use std::os::windows::fs::OpenOptionsExt;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use uuid::Uuid;

use crate::{elevation, ensure_success, find_executable, operation_lock, run, LocalState};

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Status {
    pub phase: &'static str,
    pub ready: bool,
    pub problem: Option<String>,
}

pub(crate) struct Session(Mutex<Status>);

impl Session {
    fn new() -> Self {
        Self(Mutex::new(Status {
            phase: "pending",
            ready: false,
            problem: None,
        }))
    }

    pub fn status(&self) -> Status {
        self.0.lock().unwrap().clone()
    }

    fn begin_prepare(&self) -> bool {
        let mut state = self.0.lock().unwrap();
        if !matches!(state.phase, "pending" | "failed") {
            return false;
        }
        *state = Status {
            phase: "starting",
            ready: false,
            problem: None,
        };
        true
    }

    fn finish_prepare(&self, result: Result<(), String>) {
        let mut state = self.0.lock().unwrap();
        // A close request may arrive while the startup cleanup owns the operation lock.
        if state.phase != "starting" {
            return;
        }
        *state = match result {
            Ok(()) => Status {
                phase: "ready",
                ready: true,
                problem: None,
            },
            Err(error) => Status {
                phase: "failed",
                ready: false,
                problem: Some(format!(
                    "未能清理遗留 USB 共享：{error}。请允许管理员授权后重试。"
                )),
            },
        };
    }

    pub fn begin_close(&self) -> bool {
        let mut state = self.0.lock().unwrap();
        if matches!(state.phase, "closing" | "closed") {
            return false;
        }
        *state = Status {
            phase: "closing",
            ready: false,
            problem: None,
        };
        true
    }

    pub fn finish_close(&self, result: Result<(), String>) -> bool {
        let mut state = self.0.lock().unwrap();
        *state = match result {
            Ok(()) => Status {
                phase: "closed",
                ready: false,
                problem: None,
            },
            Err(error) => Status {
                phase: "failed",
                ready: false,
                problem: Some(format!(
                    "USB 共享尚未全部停止，程序暂未退出：{error}。请重试关闭或重新清理共享。"
                )),
            },
        };
        state.phase == "closed"
    }
}

pub(crate) fn session() -> &'static Session {
    static SESSION: OnceLock<Session> = OnceLock::new();
    SESSION.get_or_init(Session::new)
}

pub(crate) fn require_ready() -> Result<(), String> {
    let state = session().status();
    if state.ready {
        Ok(())
    } else {
        Err(state
            .problem
            .unwrap_or_else(|| "正在清理 USB 共享，请等待完成".into()))
    }
}

// The handle lasts for the UI process lifetime; a crashed process releases it too.
// A second window must not revoke the first window's active session shares.
pub(crate) fn lock_instance(path: &Path) -> Result<File, String> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .share_mode(0)
        .open(path)
        .map_err(|_| "USBLink 已在运行，或无法取得共享会话锁。请先关闭已打开的 USBLink。".into())
}

pub(crate) fn prepare() -> Status {
    let session = session();
    if session.begin_prepare() {
        let result = operation_lock()
            .lock()
            .map_err(|_| "USB 操作锁不可用".to_string())
            .and_then(|_guard| clear_shared_devices());
        session.finish_prepare(result);
    }
    session.status()
}

pub(crate) fn parse_bindings(content: &str) -> Result<Vec<String>, String> {
    let state: LocalState = serde_json::from_str(content.trim_start_matches('\u{feff}'))
        .map_err(|error| format!("无法读取 USB 共享授权：{error}"))?;
    let mut result = Vec::new();
    for device in state.devices {
        if let Some(guid) = device.persisted_guid {
            let guid = Uuid::parse_str(&guid)
                .map_err(|_| "USB 共享授权标识无效，无法安全停止共享")?
                .to_string();
            if !result.contains(&guid) {
                result.push(guid);
            }
        } else if device.client_ip_address.is_some() {
            return Err("发现正在使用但缺少共享授权标识的 USB，请更新 usbipd-win 后重试".into());
        }
    }
    Ok(result)
}

fn bindings(usbipd: &Path) -> Result<Vec<String>, String> {
    parse_bindings(&ensure_success(run(usbipd, &["state"])?)?)
}

pub(crate) fn clear_shared_devices() -> Result<(), String> {
    let Some(usbipd) = find_executable("usbipd.exe") else {
        return Ok(());
    };
    let guids = bindings(&usbipd)?;
    if guids.is_empty() {
        return Ok(());
    }
    elevation::execute(elevation::PrivilegedTask::UsbUnshareMany {
        usbipd: usbipd.clone(),
        guids,
    })?;
    if !bindings(&usbipd)?.is_empty() {
        return Err("仍有 USB 共享授权，无法确认共享已全部停止".into());
    }
    Ok(())
}

// GUIDs identify persisted authorizations even after unplugging or bus ID reuse.
// Verify the resulting state and keep processing after an individual failure.
fn revoke<F, G>(targets: &[String], mut read: F, mut unbind: G) -> Result<(), String>
where
    F: FnMut() -> Result<Vec<String>, String>,
    G: FnMut(&str) -> Result<(), String>,
{
    let targets = targets
        .iter()
        .map(|id| Uuid::parse_str(id).map(|id| id.to_string()))
        .collect::<Result<HashSet<_>, _>>()
        .map_err(|_| "USB 共享授权标识无效".to_string())?;
    let current = read()?;
    let mut errors = Vec::new();
    for guid in current.iter().filter(|id| targets.contains(*id)) {
        if let Err(error) = unbind(guid) {
            errors.push(error);
        }
    }
    let remaining = read()?
        .into_iter()
        .filter(|id| targets.contains(id))
        .count();
    if remaining == 0 {
        return Ok(());
    }
    Err(format!(
        "还有 {remaining} 个 USB 共享授权未撤销{}",
        if errors.is_empty() {
            String::new()
        } else {
            format!("：{}", errors.join("；"))
        }
    ))
}

pub(crate) fn clear_elevated(usbipd: &Path, guids: &[String]) -> Result<(), String> {
    revoke(
        guids,
        || bindings(usbipd),
        |guid| ensure_success(run(usbipd, &["unbind", "--guid", guid])?).map(|_| ()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    fn ids() -> Vec<String> {
        (0..6).map(|_| Uuid::new_v4().to_string()).collect()
    }

    #[test]
    fn includes_unplugged_shares_and_rejects_ambiguous_state() {
        let id = ids().remove(0);
        let json = serde_json::json!({"Devices":[
            {"BusId":null,"Description":"unplugged","InstanceId":"USB\\example","PersistedGuid":id,"ClientIPAddress":null},
            {"BusId":"3-2","Description":"local","InstanceId":"USB\\other","PersistedGuid":null,"ClientIPAddress":null}
        ]});
        assert_eq!(parse_bindings(&json.to_string()).unwrap(), vec![id]);
        assert!(parse_bindings("{}").is_err());
        let mut invalid = json;
        invalid["Devices"][0]["PersistedGuid"] = "invalid".into();
        assert!(parse_bindings(&invalid.to_string()).is_err());
        invalid["Devices"][0]["PersistedGuid"] = serde_json::Value::Null;
        invalid["Devices"][0]["ClientIPAddress"] = "10.0.0.2".into();
        assert!(parse_bindings(&invalid.to_string()).is_err());
    }

    #[test]
    fn removes_six_shares_and_never_targets_a_new_binding() {
        let targets = ids();
        let new_id = Uuid::new_v4().to_string();
        let state = RefCell::new([targets.clone(), vec![new_id.clone()]].concat());
        let mut calls = Vec::new();
        revoke(
            &targets,
            || Ok(state.borrow().clone()),
            |id| {
                calls.push(id.to_string());
                state.borrow_mut().retain(|item| item != id);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(calls.len(), 6);
        assert_eq!(*state.borrow(), vec![new_id]);
    }

    #[test]
    fn partial_failure_keeps_processing_and_retry_only_clears_remaining_shares() {
        let targets = ids();
        let state = RefCell::new(targets.clone());
        let result = revoke(
            &targets,
            || Ok(state.borrow().clone()),
            |id| {
                if id == targets[0] {
                    return Err("access denied".into());
                }
                state.borrow_mut().retain(|item| item != id);
                Ok(())
            },
        );
        assert!(result.unwrap_err().contains("1 个"));
        let mut calls = 0;
        revoke(
            &targets,
            || Ok(state.borrow().clone()),
            |id| {
                calls += 1;
                state.borrow_mut().retain(|item| item != id);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(calls, 1);
    }

    #[test]
    fn success_exit_code_without_revocation_cannot_complete_cleanup() {
        let targets = ids();
        assert!(revoke(&targets, || Ok(targets.clone()), |_| Ok(())).is_err());
        let mut reads = 0;
        assert!(revoke(
            &targets,
            || {
                reads += 1;
                if reads == 1 {
                    Ok(targets.clone())
                } else {
                    Err("query failed".into())
                }
            },
            |_| Ok(())
        )
        .is_err());
    }

    #[test]
    fn cleanup_failure_blocks_startup_and_exit_until_explicit_retry_succeeds() {
        let session = Session::new();
        assert!(!session.status().ready);
        assert!(session.begin_prepare());
        assert!(!session.begin_prepare());
        session.finish_prepare(Err("cancelled".into()));
        assert!(!session.status().ready);
        assert!(session.begin_prepare());
        session.finish_prepare(Ok(()));
        assert!(session.status().ready);
        assert!(!session.begin_prepare());
        assert!(session.begin_close());
        assert!(!session.begin_close());
        assert!(!session.status().ready);
        assert!(!session.finish_close(Err("cancelled".into())));
        assert!(session.status().problem.unwrap().contains("暂未退出"));
        assert!(session.begin_close());
        assert!(session.finish_close(Ok(())));
        assert_eq!(session.status().phase, "closed");
    }

    #[test]
    fn late_startup_result_cannot_reopen_a_closing_session() {
        let session = Session::new();
        session.begin_prepare();
        session.begin_close();
        session.finish_prepare(Ok(()));
        assert_eq!(session.status().phase, "closing");
        assert!(!session.status().ready);
    }

    #[test]
    fn only_one_ui_can_own_the_sharing_session_and_crash_releases_the_lock() {
        let path = std::env::temp_dir().join(format!("usblink-session-{}.lock", Uuid::new_v4()));
        let first = lock_instance(&path).unwrap();
        assert!(lock_instance(&path).is_err());
        drop(first);
        drop(lock_instance(&path).unwrap());
        std::fs::remove_file(path).unwrap();
    }
}

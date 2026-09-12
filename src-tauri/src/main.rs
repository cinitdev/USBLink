#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::env;
use std::fs;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::os::windows::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use tauri::Manager;

mod atomic_file;
mod attachment;
mod connections;
mod device_metadata;
mod elevation;
mod mesh;
mod peer_health;
mod process;
mod services;
mod sharing_session;

const CREATE_NO_WINDOW: u32 = 0x08000000;
const DETACH_ALL_ARGS: [&str; 2] = ["detach", "--all"];
const MIN_SAFE_USBIP_VERSION: &str = "0.9.8.0";
const KNOWN_BAD_UDE_DRIVER_VERSION: &str = "1.45.29.368";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EnvironmentStatus {
    computer_name: String,
    usbipd_installed: bool,
    usbip_installed: bool,
    easytier_embedded: bool,
    usbipd_version: Option<String>,
    usbip_version: Option<String>,
    usbip_safe: bool,
    usbip_problem: Option<String>,
    easytier_version: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UsbDevice {
    bus_id: String,
    vid_pid: String,
    name: String,
    detail: String,
    shared: bool,
    attached: bool,
    friendly_name: bool,
}

fn executable_candidates(name: &str) -> Vec<PathBuf> {
    let mut candidates: Vec<PathBuf> = env::var_os("PATH")
        .map(|paths| env::split_paths(&paths).map(|dir| dir.join(name)).collect())
        .unwrap_or_default();
    let program_files = env::var_os("ProgramFiles")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(r"C:\Program Files"));
    match name.to_ascii_lowercase().as_str() {
        "usbipd.exe" => candidates.push(program_files.join(r"usbipd-win\usbipd.exe")),
        "usbip.exe" => candidates.push(program_files.join(r"USBip\usbip.exe")),
        _ => {}
    }
    candidates
}

fn find_executable(name: &str) -> Option<PathBuf> {
    executable_candidates(name)
        .into_iter()
        .find(|path| path.is_file())
}

pub(crate) fn run(executable: &Path, args: &[&str]) -> Result<Output, String> {
    process::run(executable, args, Duration::from_secs(30))
}

pub(crate) fn text(output: &Output) -> String {
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if stdout.is_empty() {
        String::from_utf8_lossy(&output.stderr).trim().to_string()
    } else {
        stdout
    }
}

pub(crate) fn ensure_success(output: Output) -> Result<String, String> {
    let message = text(&output);
    if output.status.success() {
        Ok(message)
    } else if message.is_empty() {
        Err("操作失败，外部组件没有返回详细信息".into())
    } else {
        Err(message)
    }
}

fn version(path: Option<&Path>) -> Option<String> {
    let content = text(&run(path?, &["--version"]).ok()?);
    Regex::new(r"(?i)(\d+\.\d+(?:\.\d+){0,2})")
        .ok()?
        .captures(&content)?
        .get(1)
        .map(|value| value.as_str().to_string())
}

fn parse_version(value: &str) -> Option<[u32; 4]> {
    let mut result = [0; 4];
    let mut count = 0;
    for (index, part) in value.split('.').enumerate() {
        if index >= result.len() {
            return None;
        }
        result[index] = part.parse().ok()?;
        count += 1;
    }
    (count >= 2).then_some(result)
}

fn version_at_least(value: &str, minimum: &str) -> bool {
    parse_version(value)
        .zip(parse_version(minimum))
        .is_some_and(|(value, minimum)| value >= minimum)
}
fn usbip_versions_are_safe(client_version: Option<&str>, driver_version: Option<&str>) -> bool {
    client_version.is_some_and(|value| version_at_least(value, MIN_SAFE_USBIP_VERSION))
        && driver_version.is_some_and(|value| {
            parse_version(value)
                .zip(parse_version(KNOWN_BAD_UDE_DRIVER_VERSION))
                .is_some_and(|(value, last_bad)| value > last_bad)
        })
}

fn usbip_ude_driver_version() -> Option<String> {
    let output = run(
        Path::new("reg.exe"),
        &[
            "query",
            r"HKLM\SYSTEM\CurrentControlSet\Services\usbip2_ude",
            "/v",
            "ImagePath",
        ],
    )
    .ok()?;
    if !output.status.success() {
        return None;
    }
    let image_path = Regex::new(r"(?im)^\s*ImagePath\s+REG_\w+\s+(.+?)\s*$")
        .ok()?
        .captures(&text(&output))?
        .get(1)?
        .as_str()
        .trim()
        .trim_matches('"')
        .to_string();
    let driver_path = if image_path.to_ascii_lowercase().starts_with(r"\systemroot\") {
        let system_root = env::var_os("SystemRoot").unwrap_or_else(|| r"C:\Windows".into());
        PathBuf::from(system_root).join(&image_path[r"\SystemRoot\".len()..])
    } else {
        PathBuf::from(image_path)
    };
    let inf_path = driver_path.parent()?.join("usbip2_ude.inf");
    let content = fs::read_to_string(inf_path).ok()?;
    Regex::new(r"(?im)^\s*DriverVer\s*=\s*[^,]+,\s*([0-9.]+)\s*$")
        .ok()?
        .captures(&content)?
        .get(1)
        .map(|value| value.as_str().to_string())
}

fn usbip_safety(path: Option<&Path>) -> (bool, Option<String>) {
    let Some(path) = path else {
        return (false, None);
    };
    let client_version = version(Some(path));
    let driver_version = usbip_ude_driver_version();
    if usbip_versions_are_safe(client_version.as_deref(), driver_version.as_deref()) {
        return (true, None);
    }

    let installed = client_version.as_deref().unwrap_or("未知版本");
    let message = format!(
        "检测到 usbip-win2 {installed} 的旧版内核驱动。官方确认上一版存在可导致内存损坏和蓝屏的严重问题；请更新到 {MIN_SAFE_USBIP_VERSION} 或更高版本并重启 Windows。USBLink 已阻止远程挂载以保护系统。"
    );
    (false, Some(message))
}

fn require_safe_usbip(path: &Path) -> Result<(), String> {
    let (safe, problem) = usbip_safety(Some(path));
    if safe {
        Ok(())
    } else {
        Err(problem.unwrap_or_else(|| "无法确认 usbip-win2 驱动安全性，已阻止远程挂载".into()))
    }
}

fn operation_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

fn attach_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

fn stop_legacy_attach_attempts() {
    if let Some(executable) = find_executable("usbip.exe") {
        let _ = run(&executable, &["attach", "--stop-all"]);
    }
}

fn bus_id_regex() -> &'static Regex {
    static VALUE: OnceLock<Regex> = OnceLock::new();
    VALUE.get_or_init(|| Regex::new(r"^\d+-\d+(?:\.\d+)*$").unwrap())
}

fn validate_bus_id(bus_id: &str) -> Result<(), String> {
    if bus_id_regex().is_match(bus_id) {
        Ok(())
    } else {
        Err(format!("无效的设备编号：{bus_id}"))
    }
}

fn validate_host(host: &str) -> Result<(), String> {
    static HOST: OnceLock<Regex> = OnceLock::new();
    let valid = HOST.get_or_init(|| Regex::new(r"^[A-Za-z0-9][A-Za-z0-9.:-]{0,252}$").unwrap());
    if valid.is_match(host) {
        Ok(())
    } else {
        Err("远程电脑地址无效".into())
    }
}

pub(crate) fn mesh_subnet(mesh_ip: &str) -> Result<String, String> {
    let address = mesh_ip
        .parse::<Ipv4Addr>()
        .map_err(|_| "EasyTier 虚拟地址无效".to_string())?;
    let octets = address.octets();
    Ok(format!("{}.{}.{}.0/24", octets[0], octets[1], octets[2]))
}

fn usb_access_rule_matches(content: &str, subnet: &str) -> bool {
    content.contains("USBLink over EasyTier")
        && content.contains("3240-3241")
        && content.contains(subnet)
}

fn usb_access_is_current(mesh_ip: &str) -> Result<bool, String> {
    let subnet = mesh_subnet(mesh_ip)?;
    let output = run(
        Path::new("netsh.exe"),
        &[
            "advfirewall",
            "firewall",
            "show",
            "rule",
            "name=USBLink over EasyTier",
            "verbose",
        ],
    )?;
    Ok(output.status.success() && usb_access_rule_matches(&text(&output), &subnet))
}

fn usbip_server_reachable() -> bool {
    TcpStream::connect_timeout(
        &SocketAddr::from(([127, 0, 0, 1], 3240)),
        Duration::from_millis(900),
    )
    .is_ok()
}

fn require_usbip_server() -> Result<(), String> {
    if usbip_server_reachable() {
        Ok(())
    } else {
        Err("usbipd-win 后台服务没有监听 TCP 3240，请点击“修复共享”并允许管理员授权".into())
    }
}

fn classify(name: &str, vid_pid: &str) -> String {
    let lower = name.to_ascii_lowercase();
    let kind = if lower.contains("android")
        || lower.contains("pixel")
        || lower.contains("phone")
        || lower.contains("redmi")
        || lower.contains("xiaomi")
    {
        "Android 设备"
    } else if lower.contains("serial") || lower.contains("ch340") || lower.contains("prolific") {
        "USB 串行设备"
    } else if lower.contains("disk") || lower.contains("storage") || lower.contains("sandisk") {
        "USB 存储设备"
    } else {
        "USB 设备"
    };
    format!("{kind} · {vid_pid}")
}

fn safe_remote_name(name: &str, vid_pid: &str) -> String {
    let lower = name.to_ascii_lowercase();
    if vid_pid.eq_ignore_ascii_case("18d1:4ee7")
        && (lower.contains("nexus/pixel") || lower.contains("charging + debug"))
    {
        "Android 调试设备".into()
    } else {
        name.trim().to_string()
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct LocalState {
    devices: Vec<LocalStateDevice>,
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct LocalStateDevice {
    bus_id: Option<String>,
    description: String,
    instance_id: String,
    persisted_guid: Option<String>,
    #[serde(rename = "ClientIPAddress")]
    client_ip_address: Option<String>,
}

fn parse_local_devices(content: &str) -> Result<Vec<UsbDevice>, String> {
    let state: LocalState = serde_json::from_str(content.trim_start_matches('\u{feff}'))
        .map_err(|error| format!("无法识别 usbipd-win 的 JSON 状态，请更新共享组件：{error}"))?;
    let id = Regex::new(r"(?i)VID_([0-9a-f]{4})&PID_([0-9a-f]{4})").unwrap();
    let mut devices = Vec::new();
    for item in state.devices {
        let Some(bus_id) = item.bus_id.filter(|value| !value.is_empty()) else {
            continue;
        };
        validate_bus_id(&bus_id)?;
        let matched = id
            .captures(&item.instance_id)
            .ok_or("USB 状态缺少有效的 VID/PID")?;
        let vid_pid = format!("{}:{}", &matched[1], &matched[2]).to_ascii_lowercase();
        let attached = item.client_ip_address.is_some();
        let shared = attached || item.persisted_guid.is_some();
        let name = if item.description.trim().is_empty() {
            format!("USB 设备 {vid_pid}")
        } else {
            item.description
        };
        devices.push(UsbDevice {
            bus_id,
            detail: classify(&name, &vid_pid),
            vid_pid,
            name,
            attached,
            shared,
            friendly_name: true,
        });
    }
    Ok(devices)
}

fn query_local_devices(executable: &Path) -> Result<Vec<UsbDevice>, String> {
    parse_local_devices(&ensure_success(run(executable, &["state"])?)?)
}

fn parse_remote_devices(content: &str) -> Vec<UsbDevice> {
    let pattern = Regex::new(
        r"^\s*(\d+-\d+(?:\.\d+)*)\s*:\s+(.+?)\s+\(([0-9A-Fa-f]{4}:[0-9A-Fa-f]{4})\)\s*$",
    )
    .unwrap();
    let mut seen = HashSet::new();
    content
        .lines()
        .filter_map(|line| {
            let capture = pattern.captures(line)?;
            let bus_id = capture[1].to_string();
            if !seen.insert(bus_id.clone()) {
                return None;
            }
            let vid_pid = capture[3].to_ascii_lowercase();
            let name = safe_remote_name(&capture[2], &vid_pid);
            Some(UsbDevice {
                bus_id,
                detail: classify(&name, &vid_pid),
                vid_pid,
                name,
                shared: true,
                attached: false,
                friendly_name: false,
            })
        })
        .collect()
}

#[tauri::command]
async fn get_environment_status() -> Result<EnvironmentStatus, String> {
    tauri::async_runtime::spawn_blocking(move || get_environment_status_blocking())
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn get_environment_status_blocking() -> Result<EnvironmentStatus, String> {
    let usbipd = find_executable("usbipd.exe");
    let usbip = find_executable("usbip.exe");
    let usbip_version = version(usbip.as_deref());
    let (usbip_safe, usbip_problem) = usbip_safety(usbip.as_deref());
    Ok(EnvironmentStatus {
        computer_name: env::var("COMPUTERNAME").unwrap_or_else(|_| "这台电脑".into()),
        usbipd_installed: usbipd.is_some(),
        usbip_installed: usbip.is_some(),
        easytier_embedded: true,
        usbipd_version: version(usbipd.as_deref()),
        usbip_version,
        usbip_safe,
        usbip_problem,
        easytier_version: mesh::EASYTIER_VERSION.into(),
    })
}

#[tauri::command]
async fn list_local_devices() -> Result<Vec<UsbDevice>, String> {
    tauri::async_runtime::spawn_blocking(move || list_local_devices_blocking())
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn list_local_devices_blocking() -> Result<Vec<UsbDevice>, String> {
    let executable = find_executable("usbipd.exe").ok_or("未安装 usbipd-win，请先在设置中安装")?;
    query_local_devices(&executable)
}

#[tauri::command]
async fn share_devices(bus_ids: Vec<String>) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        share_devices_blocking(bus_ids)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn share_devices_blocking(bus_ids: Vec<String>) -> Result<(), String> {
    sharing_session::require_ready()?;
    if bus_ids.is_empty() {
        return Err("请先选择 USB 设备".into());
    }
    for bus_id in &bus_ids {
        validate_bus_id(bus_id)?;
    }
    let mesh_ip = mesh::status(false)?
        .local_ip
        .ok_or("请先在“连接”页面创建或加入 EasyTier 连接")?;
    let usbipd = find_executable("usbipd.exe").ok_or("未安装 usbipd-win")?;
    elevation::execute(elevation::PrivilegedTask::UsbShare {
        usbipd: usbipd.clone(),
        bus_ids: bus_ids.clone(),
        mesh_ip: mesh_ip.clone(),
    })?;
    let devices = query_local_devices(&usbipd)?;
    let missing = bus_ids
        .iter()
        .filter(|bus_id| {
            !devices
                .iter()
                .any(|device| device.bus_id.as_str() == bus_id.as_str() && device.shared)
        })
        .cloned()
        .collect::<Vec<_>>();
    if !missing.is_empty() {
        return Err(format!(
            "usbipd-win 没有确认这些设备已共享：{}",
            missing.join("、")
        ));
    }
    require_usbip_server()
}

#[tauri::command]
async fn ensure_usb_sharing_ready() -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        ensure_usb_sharing_ready_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn ensure_usb_sharing_ready_blocking() -> Result<bool, String> {
    sharing_session::require_ready()?;
    let usbipd = find_executable("usbipd.exe").ok_or("未安装 usbipd-win")?;
    let devices = query_local_devices(&usbipd)?;
    if !devices.iter().any(|device| device.shared) {
        return Ok(false);
    }
    let mesh_ip = mesh::status(false)?
        .local_ip
        .ok_or("EasyTier 网络尚未获得虚拟地址，暂时无法检查 USB 共享")?;
    if usb_access_is_current(&mesh_ip)? && usbip_server_reachable() {
        return Ok(true);
    }
    elevation::execute(elevation::PrivilegedTask::UsbAccess {
        mesh_ip: mesh_ip.clone(),
    })?;
    require_usbip_server()?;
    Ok(true)
}

#[tauri::command]
async fn repair_usb_sharing() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        repair_usb_sharing_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn repair_usb_sharing_blocking() -> Result<(), String> {
    sharing_session::require_ready()?;
    let usbipd = find_executable("usbipd.exe").ok_or("未安装 usbipd-win")?;
    let devices = query_local_devices(&usbipd)?;
    if !devices.iter().any(|device| device.shared) {
        return Err("当前没有已共享的 USB 设备".into());
    }
    let mesh_ip = mesh::status(false)?
        .local_ip
        .ok_or("EasyTier 网络尚未获得虚拟地址")?;
    elevation::execute(elevation::PrivilegedTask::UsbAccess { mesh_ip })?;
    require_usbip_server()
}

#[tauri::command]
async fn unshare_device(bus_id: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        unshare_device_blocking(bus_id)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn unshare_device_blocking(bus_id: String) -> Result<(), String> {
    validate_bus_id(&bus_id)?;
    let usbipd = find_executable("usbipd.exe").ok_or("未安装 usbipd-win")?;
    elevation::execute(elevation::PrivilegedTask::UsbUnshare { usbipd, bus_id })
}

#[tauri::command]
async fn get_mesh_status(include_code: bool) -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || get_mesh_status_blocking(include_code))
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn get_mesh_status_blocking(include_code: bool) -> Result<mesh::MeshStatus, String> {
    mesh::status(include_code)
}

#[tauri::command]
async fn list_mesh_peers() -> Result<Vec<mesh::MeshPeer>, String> {
    tauri::async_runtime::spawn_blocking(move || list_mesh_peers_blocking())
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn list_mesh_peers_blocking() -> Result<Vec<mesh::MeshPeer>, String> {
    Ok(mesh::status(false)?.peers)
}

#[tauri::command]
async fn create_mesh(relay: Option<String>) -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        create_mesh_blocking(relay)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn create_mesh_blocking(relay: Option<String>) -> Result<mesh::MeshStatus, String> {
    mesh::create(relay)
}

#[tauri::command]
async fn join_mesh(pairing_code: String) -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        join_mesh_blocking(pairing_code)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn join_mesh_blocking(pairing_code: String) -> Result<mesh::MeshStatus, String> {
    mesh::join(&pairing_code)
}

#[tauri::command]
async fn leave_mesh() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        leave_mesh_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn leave_mesh_blocking() -> Result<(), String> {
    mesh::leave()
}

#[tauri::command]
async fn restart_mesh() -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        restart_mesh_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn restart_mesh_blocking() -> Result<mesh::MeshStatus, String> {
    mesh::restart()
}

#[tauri::command]
async fn repair_mesh() -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        repair_mesh_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn repair_mesh_blocking() -> Result<mesh::MeshStatus, String> {
    mesh::repair()
}

#[tauri::command]
async fn ensure_mesh_service_current() -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        ensure_mesh_service_current_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn ensure_mesh_service_current_blocking() -> Result<bool, String> {
    mesh::ensure_service_current()
}

#[tauri::command]
async fn change_mesh_relay(relay: String) -> Result<mesh::MeshStatus, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        change_mesh_relay_blocking(relay)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn change_mesh_relay_blocking(relay: String) -> Result<mesh::MeshStatus, String> {
    mesh::change_relay(relay)
}

#[tauri::command]
async fn list_attached_devices() -> Result<Vec<connections::AttachedDevice>, String> {
    tauri::async_runtime::spawn_blocking(connections::list)
        .await
        .map_err(|error| format!("USB 状态查询任务失败：{error}"))?
}

#[tauri::command]
async fn list_remote_devices(host: String) -> Result<Vec<UsbDevice>, String> {
    tauri::async_runtime::spawn_blocking(move || query_remote_devices(host))
        .await
        .map_err(|error| format!("远程设备查询任务失败：{error}"))?
}

fn query_remote_devices(host: String) -> Result<Vec<UsbDevice>, String> {
    validate_host(&host)?;
    let executable = find_executable("usbip.exe").ok_or("未安装 usbip-win2，请先在设置中安装")?;
    let output = run(&executable, &["list", "-r", &host])?;
    if !output.status.success() {
        let detail = text(&output);
        return Err(if detail.is_empty() {
            format!("无法访问 {host} 的 USB 共享服务（TCP 3240）。请在那台电脑打开新版 USBLink，并确认设备显示“已共享”")
        } else {
            format!("无法访问 {host} 的 USB 共享服务（TCP 3240）。请在那台电脑打开新版 USBLink，并确认设备显示“已共享”。系统返回：{detail}")
        });
    }
    let mut devices = parse_remote_devices(&text(&output));
    device_metadata::enrich_remote_devices(&host, &mut devices);
    Ok(devices)
}

#[tauri::command]
async fn attach_devices(
    host: String,
    bus_ids: Vec<String>,
) -> Result<Vec<connections::AttachedDevice>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        attach_devices_blocking(host, bus_ids)
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn attach_devices_blocking(
    host: String,
    bus_ids: Vec<String>,
) -> Result<Vec<connections::AttachedDevice>, String> {
    validate_host(&host)?;
    if bus_ids.is_empty() {
        return Err("请先选择远程 USB 设备".into());
    }
    for bus_id in &bus_ids {
        validate_bus_id(bus_id)?;
    }
    let executable = find_executable("usbip.exe").ok_or("未安装 usbip-win2")?;
    require_safe_usbip(&executable)?;
    let _guard = attach_lock()
        .lock()
        .map_err(|_| "USB 连接状态锁异常，请重新打开 USBLink".to_string())?;
    mesh::require_usb_peer(&host)?;
    attachment::connect(
        &host,
        &bus_ids,
        |bus_id| {
            ensure_success(run(
                &executable,
                &["attach", "--once", "-r", &host, "-b", bus_id],
            )?)
            .map(|_| ())
        },
        connections::list_with_timeout,
    )
}

#[tauri::command]
async fn detach_all_devices() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = operation_lock()
            .try_lock()
            .map_err(|_| "另一个 USB 或网络操作正在进行，请稍后重试".to_string())?;
        detach_all_devices_blocking()
    })
    .await
    .map_err(|error| format!("后台操作失败：{error}"))?
}

fn detach_all_devices_blocking() -> Result<(), String> {
    let executable = find_executable("usbip.exe").ok_or("未安装 usbip-win2")?;
    ensure_success(run(&executable, &DETACH_ALL_ARGS)?)?;
    Ok(())
}

fn auto_start_command(executable: &Path) -> String {
    format!("\"{}\"", executable.display())
}

#[tauri::command]
async fn set_auto_start(enabled: bool) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || set_auto_start_blocking(enabled))
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn set_auto_start_blocking(enabled: bool) -> Result<(), String> {
    let executable = env::current_exe().map_err(|error| format!("无法读取程序路径：{error}"))?;
    let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    let mut command = Command::new("reg.exe");
    if enabled {
        command.args([
            "add",
            key,
            "/v",
            "USBLink",
            "/t",
            "REG_SZ",
            "/d",
            &auto_start_command(&executable),
            "/f",
        ]);
    } else {
        command.args(["delete", key, "/v", "USBLink", "/f"]);
    }
    let status = command
        .creation_flags(CREATE_NO_WINDOW)
        .status()
        .map_err(|error| format!("无法更新开机启动：{error}"))?;
    if status.success() {
        Ok(())
    } else {
        Err("Windows 未能保存开机启动设置".into())
    }
}

#[tauri::command]
async fn open_dependency_download(kind: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || open_dependency_download_blocking(kind))
        .await
        .map_err(|error| format!("后台操作失败：{error}"))?
}

fn open_dependency_download_blocking(kind: String) -> Result<(), String> {
    let url = match kind.as_str() {
        "usbipd" => "https://github.com/dorssel/usbipd-win/releases/latest",
        "usbip" => "https://github.com/vadimgrn/usbip-win2/releases/latest",
        "easytier" => {
            "https://github.com/EasyTier/EasyTier/tree/8428a89d2dabc94c97d370ec607c6ca142473626"
        }
        _ => return Err("未知的安装组件".into()),
    };
    Command::new("explorer.exe")
        .arg(url)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|error| format!("无法打开下载页面：{error}"))?;
    Ok(())
}

#[tauri::command]
fn get_sharing_session() -> sharing_session::Status {
    sharing_session::session().status()
}

#[tauri::command]
async fn retry_sharing_cleanup() -> Result<sharing_session::Status, String> {
    tauri::async_runtime::spawn_blocking(sharing_session::prepare)
        .await
        .map_err(|error| format!("共享清理后台操作失败：{error}"))
}

fn show_native_error(message: &str) {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
    let title: Vec<u16> = "USBLink\0".encode_utf16().collect();
    let body: Vec<u16> = message.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            body.as_ptr(),
            title.as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}

fn request_clean_exit(app: tauri::AppHandle) {
    if !sharing_session::session().begin_close() {
        return;
    }
    tauri::async_runtime::spawn_blocking(move || {
        // Wait for an in-flight bind/UAC task before taking the final snapshot.
        let result = operation_lock()
            .lock()
            .map_err(|_| "USB 操作锁不可用".to_string())
            .and_then(|_guard| sharing_session::clear_shared_devices());
        if sharing_session::session().finish_close(result) {
            app.exit(0);
        } else if let Some(problem) = sharing_session::session().status().problem {
            show_native_error(&problem);
        }
    });
}

fn main() {
    if let Some(task_path) = elevation::task_path_from_args() {
        std::process::exit(elevation::execute_task_file(&task_path));
    }
    let _instance = match mesh::app_root().and_then(|directory| {
        fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
        sharing_session::lock_instance(&directory.join("sharing-session.lock"))
    }) {
        Ok(lock) => lock,
        Err(error) => {
            show_native_error(&error);
            return;
        }
    };
    std::thread::spawn(|| {
        if let Ok(_guard) = operation_lock().lock() {
            stop_legacy_attach_attempts();
        }
    });
    let _ = device_metadata::start_server();
    tauri::Builder::default()
        .setup(|_| {
            tauri::async_runtime::spawn_blocking(sharing_session::prepare);
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if sharing_session::session().status().phase != "closed" {
                    api.prevent_close();
                    request_clean_exit(window.app_handle().clone());
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_sharing_session,
            retry_sharing_cleanup,
            get_environment_status,
            get_mesh_status,
            create_mesh,
            join_mesh,
            leave_mesh,
            restart_mesh,
            repair_mesh,
            ensure_mesh_service_current,
            change_mesh_relay,
            list_local_devices,
            share_devices,
            ensure_usb_sharing_ready,
            repair_usb_sharing,
            unshare_device,
            list_mesh_peers,
            list_remote_devices,
            list_attached_devices,
            attach_devices,
            detach_all_devices,
            set_auto_start,
            open_dependency_download
        ])
        .build(tauri::generate_context!())
        .expect("USBLink failed to start")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                if sharing_session::session().status().phase != "closed" {
                    api.prevent_exit();
                    request_clean_exit(app.clone());
                }
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_json_preserves_full_names_and_shared_attached_states() {
        let content = serde_json::json!({"Devices": [
            {"BusId":"3-2","Description":"Redmi  K40 名称包含  双空格","InstanceId":"USB\\VID_18D1&PID_4EE7\\example","PersistedGuid":null,"ClientIPAddress":"10.0.0.2"},
            {"BusId":"3-3","Description":"测试设备","InstanceId":"USB\\VID_1234&PID_5678\\example","PersistedGuid":"test-guid","ClientIPAddress":null},
            {"BusId":"3-4","Description":"未共享","InstanceId":"USB\\VID_1234&PID_5679\\example","PersistedGuid":null,"ClientIPAddress":null},
            {"BusId":null,"Description":"已拔出","InstanceId":"USB\\VID_1234&PID_5679\\example","PersistedGuid":"test-guid","ClientIPAddress":null}
        ]});
        let devices = parse_local_devices(&content.to_string()).unwrap();
        assert_eq!(devices.len(), 3);
        assert_eq!(devices[0].name, "Redmi  K40 名称包含  双空格");
        assert_eq!(devices[0].vid_pid, "18d1:4ee7");
        assert!(devices[0].shared && devices[0].attached);
        assert!(devices[1].shared && !devices[1].attached);
        assert!(!devices[2].shared && !devices[2].attached);
    }

    #[test]
    fn malformed_local_state_is_not_an_empty_success() {
        assert!(parse_local_devices("{}").is_err());
        assert!(parse_local_devices("not json").is_err());
        assert!(parse_local_devices(r#"{"Devices":[]}"#).unwrap().is_empty());
    }

    #[test]
    fn parses_remote_usbip_rows_without_duplicates() {
        let value = "Exportable USB devices\n======================\n    3-2    : Google Inc. : Nexus/Pixel Device (charging + debug) (18d1:4ee7)\n           : USB\\VID_18D1&PID_4EE7\\8579C4A7\n           : (Defined at Interface level) (00/00/00)\n           :  0 - Vendor Specific Class/?/? (ff/42/01)";
        let devices = parse_remote_devices(value);
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].bus_id, "3-2");
        assert_eq!(devices[0].name, "Android 调试设备");
        assert_eq!(devices[0].vid_pid, "18d1:4ee7");
        assert!(!devices[0].friendly_name);
    }

    #[test]
    fn rejects_shell_metacharacters_in_identifiers() {
        assert!(validate_bus_id("1-4.2").is_ok());
        assert!(validate_bus_id("1-4;calc").is_err());
        assert!(validate_host("10.126.126.2").is_ok());
        assert!(validate_host("host;calc").is_err());
    }

    #[test]
    fn scopes_usb_firewall_to_mesh_subnet() {
        assert_eq!(mesh_subnet("10.126.126.42").unwrap(), "10.126.126.0/24");
        assert!(mesh_subnet("not-an-ip").is_err());
    }

    #[test]
    fn validates_the_real_usb_firewall_rule_instead_of_a_marker_file() {
        let output =
            "Rule Name: USBLink over EasyTier\nRemoteIP: 10.126.126.0/24\nLocalPort: 3240-3241";
        assert!(usb_access_rule_matches(output, "10.126.126.0/24"));
        assert!(!usb_access_rule_matches(output, "10.20.30.0/24"));
        assert!(!usb_access_rule_matches(
            &output.replace("3240-3241", "3240"),
            "10.126.126.0/24"
        ));
    }

    #[test]
    fn detach_all_uses_supported_long_option() {
        assert_eq!(DETACH_ALL_ARGS, ["detach", "--all"]);
    }

    #[test]
    fn compares_four_part_usbip_versions() {
        assert!(!version_at_least("0.9.7.8", MIN_SAFE_USBIP_VERSION));
        assert!(version_at_least("0.9.8.0", MIN_SAFE_USBIP_VERSION));
        assert!(version_at_least("0.10.0", MIN_SAFE_USBIP_VERSION));
        assert!(!version_at_least("unknown", MIN_SAFE_USBIP_VERSION));
    }

    #[test]
    fn rejects_unsafe_or_unverified_usbip_driver_combinations() {
        assert!(!usbip_versions_are_safe(
            Some("0.9.7.8"),
            Some(KNOWN_BAD_UDE_DRIVER_VERSION)
        ));
        assert!(!usbip_versions_are_safe(
            Some("0.9.8.0"),
            Some(KNOWN_BAD_UDE_DRIVER_VERSION)
        ));
        assert!(!usbip_versions_are_safe(Some("0.9.8.0"), Some("1.44.0.0")));
        assert!(!usbip_versions_are_safe(Some("0.9.8.0"), None));
        assert!(usbip_versions_are_safe(Some("0.9.8.0"), Some("1.46.0.0")));
    }
    #[test]
    fn auto_start_quotes_paths_containing_spaces() {
        assert_eq!(
            auto_start_command(Path::new(r"C:\Program Files\USBLink\USBLink.exe")),
            r#""C:\Program Files\USBLink\USBLink.exe""#
        );
    }
}

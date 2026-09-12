use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use std::sync::{Mutex, OnceLock};
use uuid::Uuid;
use windows_sys::Win32::Foundation::LocalFree;
use windows_sys::Win32::Security::Cryptography::{
    CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
};

use crate::elevation::{self, PrivilegedTask};
use crate::{atomic_file::StagedFile, services};
use crate::{ensure_success, run};

pub const EASYTIER_VERSION: &str = "2.6.4";
pub const DEFAULT_RELAY: &str = "tcp://183.230.36.171:11010";
pub const FALLBACK_RELAY: &str = "tcp://107.172.5.203:11010";
const LEGACY_RELAY: &str = "tcp://public.easytier.top:11010";
const SERVICE_NAME: &str = "USBLinkEasyTier";
const RPC_PORTAL: &str = "127.0.0.1:15891";
const CODE_PREFIX: &str = "USBLINK1-";

struct EmbeddedAsset {
    name: &'static str,
    bytes: &'static [u8],
    sha256: &'static str,
}

const ASSETS: &[EmbeddedAsset] = &[
    EmbeddedAsset {
        name: "easytier-core.exe",
        bytes: include_bytes!("../vendor/easytier/easytier-core.exe"),
        sha256: "DA7EB2D24B5416F3D3407636949E964A0750E3F9DC53A828CB6799A57EAD445D",
    },
    EmbeddedAsset {
        name: "easytier-cli.exe",
        bytes: include_bytes!("../vendor/easytier/easytier-cli.exe"),
        sha256: "D8783E851E944B44A9B71B39FD02F227EC0A2A82B3165C55EAD5DD32DCDE53A1",
    },
    EmbeddedAsset {
        name: "wintun.dll",
        bytes: include_bytes!("../vendor/easytier/wintun.dll"),
        sha256: "E5DA8447DC2C320EDC0FC52FA01885C103DE8C118481F683643CACC3220DAFCE",
    },
    EmbeddedAsset {
        name: "Packet.dll",
        bytes: include_bytes!("../vendor/easytier/Packet.dll"),
        sha256: "C7C03A87EAC7243CCBE331554624B18803010B740E311FC8CFDDB573096EACAC",
    },
    EmbeddedAsset {
        name: "WinDivert64.sys",
        bytes: include_bytes!("../vendor/easytier/WinDivert64.sys"),
        sha256: "8DA085332782708D8767BCACE5327A6EC7283C17CFB85E40B03CD2323A90DDC2",
    },
    EmbeddedAsset {
        name: "LICENSE-EasyTier.txt",
        bytes: include_bytes!("../vendor/easytier/LICENSE-EasyTier.txt"),
        sha256: "E3A994D82E644B03A792A930F574002658412F62407F5FEE083F2555C5F23118",
    },
];

static ASSET_IO: Mutex<()> = Mutex::new(());
static ASSET_DIR: OnceLock<PathBuf> = OnceLock::new();
static PROFILE_IO: OnceLock<Mutex<()>> = OnceLock::new();

#[derive(Clone, Debug, Deserialize, Serialize)]
pub(crate) struct MeshProfile {
    version: u8,
    network_name: String,
    network_secret: String,
    relay: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeshPeer {
    pub name: String,
    pub ip: String,
    pub online: bool,
    pub usb_ready: bool,
    pub problem: Option<String>,
    pub os: String,
    pub latency: String,
    pub tunnel: String,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MeshStatus {
    pub configured: bool,
    pub running: bool,
    pub local_ip: Option<String>,
    pub network_name: Option<String>,
    pub relay: String,
    pub peer_count: usize,
    pub peers: Vec<MeshPeer>,
    pub pairing_code: Option<String>,
    pub problem: Option<String>,
    pub needs_repair: bool,
}

pub(crate) fn app_root() -> Result<PathBuf, String> {
    let local = env::var_os("LOCALAPPDATA").ok_or("Windows 未提供本地应用数据目录")?;
    Ok(PathBuf::from(local).join("USBLink"))
}

fn profile_path() -> Result<PathBuf, String> {
    Ok(app_root()?.join("mesh.dat"))
}

fn sha256(bytes: &[u8]) -> String {
    format!("{:X}", Sha256::digest(bytes))
}

fn file_matches(path: &Path, expected: &str) -> bool {
    fs::read(path)
        .map(|bytes| sha256(&bytes) == expected)
        .unwrap_or(false)
}

pub fn ensure_assets() -> Result<PathBuf, String> {
    if let Some(path) = ASSET_DIR.get() {
        return Ok(path.clone());
    }
    let _guard = ASSET_IO
        .lock()
        .map_err(|_| "内置组件初始化锁不可用".to_string())?;
    if let Some(path) = ASSET_DIR.get() {
        return Ok(path.clone());
    }
    let directory = app_root()?
        .join("EasyTier")
        .join(format!("v{EASYTIER_VERSION}"));
    fs::create_dir_all(&directory).map_err(|error| format!("无法创建 EasyTier 目录：{error}"))?;
    for asset in ASSETS {
        let destination = directory.join(asset.name);
        if file_matches(&destination, asset.sha256) {
            continue;
        }
        let temporary = directory.join(format!("{}.tmp", asset.name));
        fs::write(&temporary, asset.bytes)
            .map_err(|error| format!("无法提取 {}：{error}", asset.name))?;
        if sha256(asset.bytes) != asset.sha256 {
            let _ = fs::remove_file(&temporary);
            return Err(format!("内置组件 {} 校验失败", asset.name));
        }
        if destination.exists() {
            fs::remove_file(&destination)
                .map_err(|error| format!("无法更新 {}：{error}", asset.name))?;
        }
        fs::rename(&temporary, &destination)
            .map_err(|error| format!("无法启用 {}：{error}", asset.name))?;
    }
    fs::create_dir_all(directory.join("logs"))
        .map_err(|error| format!("无法创建 EasyTier 日志目录：{error}"))?;
    let _ = ASSET_DIR.set(directory.clone());
    Ok(directory)
}

pub(crate) fn protect(data: &[u8]) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    let ok = unsafe {
        CryptProtectData(
            &input,
            null(),
            null(),
            null(),
            null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!(
            "无法保护配对信息：{}",
            std::io::Error::last_os_error()
        ));
    }
    let encrypted =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe { LocalFree(output.pbData as *mut _) };
    Ok(encrypted)
}

pub(crate) fn unprotect(data: &[u8]) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len() as u32,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    let ok = unsafe {
        CryptUnprotectData(
            &input,
            null_mut(),
            null(),
            null(),
            null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(format!(
            "无法读取配对信息：{}",
            std::io::Error::last_os_error()
        ));
    }
    let decrypted =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe { LocalFree(output.pbData as *mut _) };
    Ok(decrypted)
}

fn save_profile(profile: &MeshProfile) -> Result<(), String> {
    let _guard = PROFILE_IO
        .get_or_init(|| Mutex::new(()))
        .lock()
        .map_err(|_| "配对信息写入锁不可用".to_string())?;
    let path = profile_path()?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| format!("无法创建配置目录：{error}"))?;
    }
    let json = serde_json::to_vec(profile).map_err(|error| format!("无法保存配对信息：{error}"))?;
    StagedFile::prepare(&path, &protect(&json)?)?.commit()
}

fn load_profile() -> Result<Option<MeshProfile>, String> {
    let _guard = PROFILE_IO
        .get_or_init(|| Mutex::new(()))
        .lock()
        .map_err(|_| "配对信息读取锁不可用".to_string())?;
    let path = profile_path()?;
    if !path.is_file() {
        return Ok(None);
    }
    let encrypted = fs::read(path).map_err(|error| format!("无法读取配对信息：{error}"))?;
    let profile: MeshProfile = serde_json::from_slice(&unprotect(&encrypted)?)
        .map_err(|error| format!("配对信息已损坏：{error}"))?;
    validate_profile(&profile)?;
    Ok(Some(profile))
}

pub(crate) fn device_metadata_token() -> Result<Option<[u8; 32]>, String> {
    let Some(profile) = load_profile()? else {
        return Ok(None);
    };
    let mut digest = Sha256::new();
    digest.update(b"USBLink device metadata v1\0");
    digest.update(profile.network_secret.as_bytes());
    let mut token = [0u8; 32];
    token.copy_from_slice(&digest.finalize());
    Ok(Some(token))
}

fn validate_relay(relay: &str) -> Result<(), String> {
    static RELAY: OnceLock<regex::Regex> = OnceLock::new();
    let pattern = RELAY.get_or_init(|| {
        regex::Regex::new(r"^(?:tcp|udp|ws|wss|wg|quic)://[A-Za-z0-9.-]+:\d{1,5}/?$").unwrap()
    });
    let port_valid = relay
        .trim_end_matches('/')
        .rsplit_once(':')
        .and_then(|(_, port)| port.parse::<u16>().ok())
        .is_some_and(|port| port != 0);
    if relay.len() <= 260 && pattern.is_match(relay) && port_valid {
        Ok(())
    } else {
        Err("中继地址格式无效".into())
    }
}

fn validate_profile(profile: &MeshProfile) -> Result<(), String> {
    static NAME: OnceLock<regex::Regex> = OnceLock::new();
    static SECRET: OnceLock<regex::Regex> = OnceLock::new();
    if profile.version != 1
        || !NAME
            .get_or_init(|| regex::Regex::new(r"^usblink-[a-f0-9]{12}$").unwrap())
            .is_match(&profile.network_name)
        || !SECRET
            .get_or_init(|| regex::Regex::new(r"^[a-f0-9]{64}$").unwrap())
            .is_match(&profile.network_secret)
    {
        return Err("配对码不是有效的 USBLink 网络".into());
    }
    validate_relay(&profile.relay)
}

fn encode_profile(profile: &MeshProfile) -> Result<String, String> {
    let json = serde_json::to_vec(profile).map_err(|error| format!("无法生成配对码：{error}"))?;
    Ok(format!("{CODE_PREFIX}{}", URL_SAFE_NO_PAD.encode(json)))
}

fn decode_profile(code: &str) -> Result<MeshProfile, String> {
    let encoded = code
        .trim()
        .strip_prefix(CODE_PREFIX)
        .ok_or("配对码格式不正确")?;
    let bytes = URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|_| "配对码格式不正确")?;
    let profile: MeshProfile = serde_json::from_slice(&bytes).map_err(|_| "配对码内容不正确")?;
    validate_profile(&profile)?;
    Ok(profile)
}

fn is_legacy_relay(relay: &str) -> bool {
    relay.eq_ignore_ascii_case(LEGACY_RELAY)
        || relay
            .split_once("://")
            .and_then(|(_, authority)| authority.rsplit_once(':'))
            .is_some_and(|(host, _)| host.eq_ignore_ascii_case("public.easytier.top"))
}

fn migrate_legacy_relay(profile: &mut MeshProfile) -> bool {
    if is_legacy_relay(&profile.relay) {
        profile.relay = DEFAULT_RELAY.into();
        true
    } else {
        false
    }
}

fn new_profile(relay: Option<String>) -> Result<MeshProfile, String> {
    let mut relay = relay
        .map(|value| value.trim().to_string())
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_RELAY.into());
    if is_legacy_relay(&relay) {
        relay = DEFAULT_RELAY.into();
    }
    validate_relay(&relay)?;
    let first = Uuid::new_v4().simple().to_string();
    let second = Uuid::new_v4().simple().to_string();
    let secret = format!("{first}{second}");
    Ok(MeshProfile {
        version: 1,
        network_name: format!("usblink-{}", &secret[..12]),
        network_secret: secret,
        relay,
    })
}

fn install_service(profile: &MeshProfile) -> Result<(), String> {
    validate_profile(profile)?;
    ensure_assets()?;
    elevation::execute(PrivilegedTask::MeshApply {
        profile: profile.clone(),
    })
}

fn service_configuration_matches(content: &str, profile: &MeshProfile) -> bool {
    let required = [
        "easytier-core.exe",
        "--network-name",
        profile.network_name.as_str(),
        "--network-secret",
        profile.network_secret.as_str(),
        "--peers",
        profile.relay.as_str(),
        FALLBACK_RELAY,
        "--tcp-whitelist 3240-3241",
        "--rpc-portal 127.0.0.1:15891",
        "--disable-ipv6 true",
    ];
    required.iter().all(|value| content.contains(value))
}

pub fn ensure_service_current() -> Result<bool, String> {
    let Some(mut profile) = load_profile()? else {
        return Ok(false);
    };
    let migrated = migrate_legacy_relay(&mut profile);
    let output = run(Path::new("sc.exe"), &["qc", SERVICE_NAME])?;
    if output.status.success() && service_configuration_matches(&crate::text(&output), &profile) {
        if migrated {
            save_profile(&profile)?;
        }
        return Ok(false);
    }
    apply_profile(&profile)?;
    Ok(true)
}

fn uninstall_service() -> Result<(), String> {
    ensure_assets()?;
    elevation::execute(PrivilegedTask::MeshRemove)
}

fn run_owned(executable: &Path, arguments: &[String]) -> Result<String, String> {
    let values = arguments.iter().map(String::as_str).collect::<Vec<_>>();
    ensure_success(run(executable, &values)?)
}

fn service_prefix(action: &str) -> Vec<String> {
    vec![
        "service".into(),
        "-n".into(),
        SERVICE_NAME.into(),
        action.into(),
    ]
}

pub(crate) fn apply_service_elevated(profile: &MeshProfile) -> Result<(), String> {
    validate_profile(profile)?;
    let previous = services::snapshot(SERVICE_NAME)?;
    services::stop_and_wait(SERVICE_NAME)?;
    restore_after_failure(
        || {
            write_service_configuration(profile)?;
            services::start(SERVICE_NAME)
        },
        || restore_service_elevated(&previous),
    )
}

pub(crate) fn restore_service_elevated(snapshot: &services::Snapshot) -> Result<(), String> {
    if let Some(configuration) = &snapshot.configuration {
        services::restore(SERVICE_NAME, configuration)?;
        if snapshot.was_running {
            services::start(SERVICE_NAME)?;
        }
        Ok(())
    } else {
        remove_service_elevated()
    }
}

// EasyTier 2.6.4's Windows installer updates an existing service in place.
fn write_service_configuration(profile: &MeshProfile) -> Result<(), String> {
    let directory = ensure_assets()?;
    let cli = directory.join("easytier-cli.exe");
    let core = directory.join("easytier-core.exe");
    let hostname = env::var("COMPUTERNAME").unwrap_or_else(|_| "USBLink-PC".into());
    let mut install = service_prefix("install");
    install.extend([
        "--display-name".into(),
        "USBLink EasyTier Network".into(),
        "--description".into(),
        "USBLink encrypted peer-to-peer network".into(),
        "--core-path".into(),
        core.to_string_lossy().into_owned(),
        "--service-work-dir".into(),
        directory.to_string_lossy().into_owned(),
        "--disable-autostart".into(),
        "false".into(),
        "--disable-restart-on-failure".into(),
        "false".into(),
        "--".into(),
        "--network-name".into(),
        profile.network_name.clone(),
        "--network-secret".into(),
        profile.network_secret.clone(),
        "--dhcp".into(),
        "true".into(),
        "--peers".into(),
        profile.relay.clone(),
        FALLBACK_RELAY.into(),
        "--hostname".into(),
        hostname,
        "--instance-name".into(),
        "usblink".into(),
        "--dev-name".into(),
        "USBLink".into(),
        "--rpc-portal".into(),
        RPC_PORTAL.into(),
        "--rpc-portal-whitelist".into(),
        "127.0.0.1/32".into(),
        "--encryption-algorithm".into(),
        "aes-256-gcm".into(),
        "--relay-network-whitelist".into(),
        profile.network_name.clone(),
        "--tcp-whitelist".into(),
        "3240-3241".into(),
        "--latency-first".into(),
        "true".into(),
        "--disable-ipv6".into(),
        "true".into(),
        "--file-log-level".into(),
        "warn".into(),
        "--file-log-dir".into(),
        directory.join("logs").to_string_lossy().into_owned(),
        "--file-log-size".into(),
        "5".into(),
        "--file-log-count".into(),
        "2".into(),
    ]);
    run_owned(&cli, &install)?;
    Ok(())
}

pub(crate) fn remove_service_elevated() -> Result<(), String> {
    let directory = ensure_assets()?;
    let cli = directory.join("easytier-cli.exe");
    if services::state(SERVICE_NAME)?.is_none() {
        return Ok(());
    }
    services::stop_and_wait(SERVICE_NAME)?;
    run_owned(&cli, &service_prefix("uninstall"))?;
    Ok(())
}

pub(crate) fn restart_service_elevated() -> Result<(), String> {
    if services::state(SERVICE_NAME)?.is_none() {
        return Err("EasyTier 网络服务尚未安装".into());
    }
    services::stop_and_wait(SERVICE_NAME)?;
    services::start(SERVICE_NAME)
}

fn restore_after_failure(
    operation: impl FnOnce() -> Result<(), String>,
    restore: impl FnOnce() -> Result<(), String>,
) -> Result<(), String> {
    match operation() {
        Ok(()) => Ok(()),
        Err(error) => match restore() {
            Ok(()) => Err(format!("{error}；已恢复原配置")),
            Err(recovery) => Err(format!("{error}；恢复原配置也失败：{recovery}，请修复连接")),
        },
    }
}

fn apply_profile(profile: &MeshProfile) -> Result<(), String> {
    validate_profile(profile)?;
    // The installed service may differ from an old (or damaged) profile file.
    let previous = services::snapshot(SERVICE_NAME)?;
    let json = serde_json::to_vec(profile).map_err(|error| error.to_string())?;
    // Prepare complete encrypted data before asking Windows to change a service.
    let staged = StagedFile::prepare(&profile_path()?, &protect(&json)?)?;
    install_service(profile)?;
    restore_after_failure(
        || {
            let _guard = PROFILE_IO
                .get_or_init(|| Mutex::new(()))
                .lock()
                .map_err(|_| "配对信息写入锁不可用".to_string())?;
            staged.commit()
        },
        || elevation::execute(PrivilegedTask::MeshRestore { snapshot: previous }),
    )
}

fn peer_values() -> Result<Vec<Value>, String> {
    let directory = ensure_assets()?;
    let cli = directory.join("easytier-cli.exe");
    let output = ensure_success(run(
        &cli,
        &["-p", RPC_PORTAL, "-o", "json", "peer", "list"],
    )?)?;
    let value: Value = serde_json::from_str(&output)
        .map_err(|error| format!("无法读取 EasyTier 状态：{error}"))?;
    if let Some(items) = value.as_array() {
        return Ok(items.clone());
    }
    if let Some(items) = value.get("result").and_then(Value::as_array) {
        return Ok(items.clone());
    }
    Err("EasyTier 返回了未知的状态格式".into())
}

fn parse_peers(values: &[Value]) -> (Option<String>, Vec<MeshPeer>, bool) {
    let mut local_ip = None;
    let mut peers = Vec::new();
    let mut relay_connected = false;
    for item in values {
        let ip = item
            .get("ipv4")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let name = item
            .get("hostname")
            .and_then(Value::as_str)
            .unwrap_or("远程电脑")
            .to_string();
        let cost = item.get("cost").and_then(Value::as_str).unwrap_or_default();
        if cost.eq_ignore_ascii_case("local") {
            local_ip = (!ip.is_empty()).then_some(ip);
            continue;
        }
        if name.to_ascii_lowercase().starts_with("publicserver") {
            relay_connected = true;
            continue;
        }
        if ip.is_empty() {
            continue;
        }
        peers.push(MeshPeer {
            name,
            ip,
            online: false,
            usb_ready: false,
            problem: Some("正在验证对方电脑是否在线".into()),
            os: "windows".into(),
            latency: item
                .get("lat_ms")
                .and_then(Value::as_str)
                .unwrap_or("-")
                .to_string(),
            tunnel: item
                .get("tunnel_proto")
                .and_then(Value::as_str)
                .unwrap_or("p2p")
                .to_string(),
        });
    }
    (local_ip, peers, relay_connected)
}

fn check_peer_health(peers: &mut [MeshPeer]) {
    // Bound parallel probes without imposing a limit on the number of peers.
    for batch in peers.chunks_mut(8) {
        std::thread::scope(|scope| {
            for peer in batch {
                scope.spawn(move || {
                    let health = crate::peer_health::probe(&peer.ip);
                    peer.online = health.online;
                    peer.usb_ready = health.usb_ready;
                    peer.problem = health.problem;
                });
            }
        });
    }
}

pub(crate) fn require_usb_peer(host: &str) -> Result<(), String> {
    if load_profile()?.is_none() {
        return Err("请先加入加密连接".into());
    }
    let (_, peers, _) = parse_peers(&peer_values()?);
    if !peers.iter().any(|peer| peer.ip == host) {
        return Err("对方电脑已离线或已离开当前连接，请等待对方上线".into());
    }
    let health = crate::peer_health::probe(host);
    if health.usb_ready {
        Ok(())
    } else {
        Err(health
            .problem
            .unwrap_or_else(|| "对方 USB 共享服务不可达".into()))
    }
}

pub fn status(include_code: bool) -> Result<MeshStatus, String> {
    ensure_assets()?;
    let profile = load_profile()?;
    let Some(mut profile) = profile else {
        return Ok(MeshStatus {
            configured: false,
            running: false,
            local_ip: None,
            network_name: None,
            relay: DEFAULT_RELAY.into(),
            peer_count: 0,
            peers: vec![],
            pairing_code: None,
            problem: None,
            needs_repair: false,
        });
    };
    migrate_legacy_relay(&mut profile);
    let peer_result = peer_values();
    let service_responding = peer_result.is_ok();
    let (local_ip, mut peers, relay_connected) = peer_result
        .map(|values| parse_peers(&values))
        .unwrap_or_default();
    check_peer_health(&mut peers);
    let problem = if !service_responding {
        Some("EasyTier 网络服务没有响应".into())
    } else if !relay_connected {
        Some("无法连接公共节点，请检查网络或更换中继".into())
    } else {
        None
    };
    Ok(MeshStatus {
        configured: true,
        running: local_ip.is_some() && relay_connected,
        local_ip,
        network_name: Some(profile.network_name.clone()),
        relay: profile.relay.clone(),
        peer_count: peers.iter().filter(|peer| peer.online).count(),
        peers,
        pairing_code: if include_code {
            Some(encode_profile(&profile)?)
        } else {
            None
        },
        problem,
        needs_repair: !service_responding || !relay_connected,
    })
}

fn starting_status(profile: &MeshProfile, pairing_code: Option<String>) -> MeshStatus {
    MeshStatus {
        configured: true,
        running: false,
        local_ip: None,
        network_name: Some(profile.network_name.clone()),
        relay: profile.relay.clone(),
        peer_count: 0,
        peers: vec![],
        pairing_code,
        problem: None,
        needs_repair: false,
    }
}

pub fn create(relay: Option<String>) -> Result<MeshStatus, String> {
    let profile = new_profile(relay)?;
    apply_profile(&profile)?;
    Ok(starting_status(&profile, Some(encode_profile(&profile)?)))
}

pub fn join(code: &str) -> Result<MeshStatus, String> {
    let mut profile = decode_profile(code)?;
    migrate_legacy_relay(&mut profile);
    apply_profile(&profile)?;
    Ok(starting_status(&profile, None))
}

pub fn leave() -> Result<(), String> {
    uninstall_service()?;
    let path = profile_path()?;
    if path.exists() {
        fs::remove_file(path).map_err(|error| format!("无法删除配对信息：{error}"))?;
    }
    Ok(())
}

pub fn restart() -> Result<MeshStatus, String> {
    let profile = load_profile()?.ok_or("尚未创建或加入连接")?;
    elevation::execute(PrivilegedTask::MeshRestart)?;
    Ok(starting_status(&profile, None))
}

pub fn repair() -> Result<MeshStatus, String> {
    let mut profile = load_profile()?.ok_or("尚未创建或加入连接")?;
    migrate_legacy_relay(&mut profile);
    apply_profile(&profile)?;
    Ok(starting_status(&profile, None))
}

pub fn change_relay(relay: String) -> Result<MeshStatus, String> {
    let relay = relay.trim().to_string();
    if is_legacy_relay(&relay) {
        return Err("这个公共节点已经失效，请使用默认节点或填写其他地址".into());
    }
    validate_relay(&relay)?;
    let mut profile = load_profile()?.ok_or("尚未创建或加入连接")?;
    profile.relay = relay;
    apply_profile(&profile)?;
    Ok(starting_status(&profile, None))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failed_profile_commit_restores_service_and_keeps_previous_profile() {
        use std::cell::Cell;
        use std::os::windows::fs::OpenOptionsExt;
        let directory = env::temp_dir().join(format!("usblink-rollback-{}", Uuid::new_v4()));
        let path = directory.join("profile.dat");
        fs::create_dir_all(&directory).unwrap();
        let previous = protect(b"previous pairing").unwrap();
        fs::write(&path, &previous).unwrap();
        let staged = StagedFile::prepare(&path, &protect(b"new pairing").unwrap()).unwrap();
        let lock = fs::OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(&path)
            .unwrap();
        let service = Cell::new("new service configuration");
        let error = restore_after_failure(
            || staged.commit(),
            || {
                service.set("previous service configuration");
                Ok(())
            },
        )
        .unwrap_err();
        drop(lock);
        assert!(error.contains("已恢复原配置"));
        assert_eq!(service.get(), "previous service configuration");
        assert_eq!(fs::read(&path).unwrap(), previous);
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 1);
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn configuration_recovery_does_not_mask_failure_or_revert_success() {
        assert!(restore_after_failure(|| Ok(()), || panic!("must not undo success")).is_ok());
        let error = restore_after_failure(
            || Err("无法启动新服务".into()),
            || Err("原服务也无法启动".into()),
        )
        .unwrap_err();
        assert!(error.contains("无法启动新服务"));
        assert!(error.contains("恢复原配置也失败：原服务也无法启动"));
        assert!(!error.contains("已恢复原配置"));
    }

    #[test]
    fn pairing_code_round_trip() {
        let profile = new_profile(None).unwrap();
        let code = encode_profile(&profile).unwrap();
        assert!(code.starts_with(CODE_PREFIX));
        let decoded = decode_profile(&code).unwrap();
        assert_eq!(decoded.network_name, profile.network_name);
        assert_eq!(decoded.network_secret, profile.network_secret);
        assert_eq!(decoded.relay, DEFAULT_RELAY);
    }

    #[test]
    fn pairing_code_rejects_invalid_content() {
        assert!(decode_profile("hello").is_err());
        assert!(decode_profile("USBLINK1-not-base64").is_err());
        assert!(validate_relay("https://example.com/").is_err());
        assert!(validate_relay("tcp://public.easytier.top:11010").is_ok());
    }

    #[test]
    fn parses_local_and_remote_peer_rows() {
        let values = serde_json::json!([
            {"ipv4":"10.0.0.1","hostname":"DESKTOP","cost":"Local","lat_ms":"-","tunnel_proto":"-"},
            {"ipv4":"","hostname":"PublicServer_a","cost":"p2p","lat_ms":"22.0","tunnel_proto":"tcp"},
            {"ipv4":"10.0.0.2","hostname":"OFFICE-PC","cost":"p2p","lat_ms":"3.5","tunnel_proto":"udp"}
        ]);
        let (local, peers, relay_connected) = parse_peers(values.as_array().unwrap());
        assert_eq!(local.as_deref(), Some("10.0.0.1"));
        assert_eq!(peers.len(), 1);
        assert_eq!(peers[0].name, "OFFICE-PC");
        assert!(!peers[0].online, "discovery alone cannot confirm presence");
        assert!(!peers[0].usb_ready);
        assert!(relay_connected);
    }

    #[test]
    fn legacy_relay_variants_are_repaired() {
        for relay in [
            "tcp://public.easytier.top:11010/",
            "udp://PUBLIC.EASYTIER.TOP:11010",
            "tcp://public.easytier.top:1000",
        ] {
            assert!(is_legacy_relay(relay));
            assert_eq!(
                new_profile(Some(relay.into())).unwrap().relay,
                DEFAULT_RELAY
            );
        }
        assert!(!is_legacy_relay(DEFAULT_RELAY));
    }

    #[test]
    fn legacy_pairing_codes_migrate_to_live_numeric_relay() {
        let mut profile = MeshProfile {
            version: 1,
            network_name: "usblink-0123456789ab".into(),
            network_secret: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .into(),
            relay: LEGACY_RELAY.into(),
        };
        assert!(migrate_legacy_relay(&mut profile));
        assert_eq!(profile.relay, DEFAULT_RELAY);
        assert!(!migrate_legacy_relay(&mut profile));
    }

    #[test]
    fn detects_outdated_easytier_service_arguments() {
        let profile = new_profile(None).unwrap();
        let current = format!(
            "easytier-core.exe --network-name {} --network-secret {} --peers {} {} --tcp-whitelist 3240-3241 --rpc-portal 127.0.0.1:15891 --disable-ipv6 true",
            profile.network_name, profile.network_secret, profile.relay, FALLBACK_RELAY
        );
        assert!(service_configuration_matches(&current, &profile));
        assert!(!service_configuration_matches(
            &current.replace("--tcp-whitelist 3240-3241", ""),
            &profile
        ));
        assert!(!service_configuration_matches(
            &current.replace(FALLBACK_RELAY, ""),
            &profile
        ));
    }

    #[test]
    fn embedded_assets_match_published_hashes() {
        for asset in ASSETS {
            assert_eq!(
                sha256(asset.bytes),
                asset.sha256,
                "{} hash mismatch",
                asset.name
            );
        }
    }

    #[test]
    fn dpapi_protects_privileged_task_data() {
        let secret = b"USBLink elevated task test";
        let encrypted = protect(secret).unwrap();
        assert_ne!(encrypted, secret);
        assert_eq!(unprotect(&encrypted).unwrap(), secret);
    }
    #[test]
    fn rejects_out_of_range_relay_ports() {
        assert!(validate_relay("tcp://example.com:0").is_err());
        assert!(validate_relay("tcp://example.com:65536").is_err());
        assert!(validate_relay("tcp://example.com:99999").is_err());
        assert!(validate_relay("tcp://example.com:65535").is_ok());
        assert!(validate_relay("udp://example.com:1/").is_ok());
        assert_eq!(
            new_profile(Some(format!("  {DEFAULT_RELAY}  ")))
                .unwrap()
                .relay,
            DEFAULT_RELAY
        );
    }
}

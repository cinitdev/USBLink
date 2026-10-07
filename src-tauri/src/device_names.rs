//! Read-only Windows naming. No device handles, ADB/fastboot commands or USB I/O.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{atomic_file::StagedFile, mesh, LocalStateDevice};

const MAX_ENTRIES: usize = 512;
const MAX_AGE_DAYS: u64 = 180;
const MAX_FILE_BYTES: u64 = 1024 * 1024;

#[derive(Default, Deserialize, Serialize, PartialEq)]
struct History {
    entries: BTreeMap<String, Remembered>,
}

#[derive(Deserialize, Serialize, PartialEq)]
struct Remembered {
    // None is a conflict tombstone: never reuse an ambiguous serial's name.
    name: Option<String>,
    instances: Vec<String>,
    day: u64,
}

#[derive(Default)]
struct Observation {
    serial: Option<String>,
    instance: String,
    unique: bool,
    exported: bool,
    model: Option<String>,
    description: String,
}

fn digest(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}

// Device-provided serials only. Never strip an interface/location suffix to
// manufacture a match. VID/PID is intentionally not part of cross-mode identity.
fn serial_key(instance: &str) -> Option<String> {
    let parts: Vec<_> = instance.split('\\').collect();
    if parts.len() != 3 || !parts[0].eq_ignore_ascii_case("USB") {
        return None;
    }
    let hardware = parts[1].to_ascii_uppercase();
    if !hardware.is_ascii()
        || hardware.len() != 17
        || !hardware.starts_with("VID_")
        || &hardware[8..13] != "&PID_"
        || !hardware[4..8]
            .bytes()
            .chain(hardware[13..].bytes())
            .all(|c| c.is_ascii_hexdigit())
    {
        return None;
    }
    let serial = parts[2];
    let normalized = serial.to_ascii_lowercase();
    if !(8..=128).contains(&serial.len())
        || !serial
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        || serial.bytes().collect::<HashSet<_>>().len() < 4
        || [
            "0123456789",
            "123456789",
            "abcdef",
            "deadbeef",
            "unknown",
            "default",
            "serial",
            "android",
            "fastboot",
            "none",
            "null",
        ]
        .iter()
        .any(|dummy| normalized.contains(dummy))
    {
        return None;
    }
    // Preserve case: changing a serial is not proof of the same physical phone.
    Some(digest(serial))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seen(serial: &str, name: &str, pid: &str) -> Observation {
        let instance = format!(r"USB\VID_18D1&PID_{pid}\{serial}");
        Observation {
            serial: serial_key(&instance),
            instance: digest(&instance),
            unique: true,
            model: specific_name(name, false),
            description: name.into(),
            exported: false,
        }
    }

    #[test]
    fn models_from_different_brands_survive_changed_pid_and_generic_boot_modes() {
        for name in [
            "Redmi K40",
            "Pixel 9 Pro",
            "SM-S918B",
            "CPH2581",
            "V2307A",
            "HONOR 90",
            "motorola edge 50",
        ] {
            let mut history = History::default();
            let normal = seen("8f79c4a7e231", name, "4EE7");
            assert!(normal.serial.is_some());
            assert_eq!(history.apply(&[normal], 100), [name]);
            for (pid, generic) in [
                ("D00D", "Android"),
                ("4EE0", "Android Bootloader Interface"),
            ] {
                let mut boot = seen("8f79c4a7e231", generic, pid);
                boot.instance = digest(&format!("different-vendor-{pid}"));
                assert_eq!(history.apply(&[boot], 101), [name]);
            }
        }
    }

    #[test]
    fn missing_changed_generated_dummy_and_unverified_serials_do_not_match() {
        for instance in [
            r"USB\VID_18D1&PID_D00D\",
            r"USB\VID_18D1&PID_D00D\6&219C9A4&0&2",
            r"USB\VID_18D1&PID_D00D&MI_00\8f79c4a7e231",
            r"USB\VID_18D1&PID_D00D\0123456789ABCDEF",
            r"USB\VID_18D1&PID_D00D\00000000",
            r"USB\VID_18D1&PID_D00D\unknown123",
            r"USB\VID_测试&PID_D00D\8f79c4a7e231",
        ] {
            assert!(serial_key(instance).is_none(), "{instance}");
        }
        let mut history = History::default();
        history.apply(&[seen("8f79c4a7e231", "Redmi K40", "4EE7")], 100);
        assert_eq!(
            history.apply(&[seen("7A09E619F238", "Android", "D00D")], 100),
            ["Android"]
        );
        let mut unverified = seen("8f79c4a7e231", "Android", "D00D");
        unverified.unique = false;
        assert_eq!(history.apply(&[unverified], 100), ["Android"]);
    }

    #[test]
    fn simultaneous_duplicates_and_later_conflicts_disable_recall() {
        let mut history = History::default();
        history.apply(&[seen("8f79c4a7e231", "Redmi K40", "4EE7")], 100);
        let boot = || seen("8f79c4a7e231", "Android", "D00D");
        assert_eq!(
            history.apply(&[boot(), boot()], 100),
            ["Android", "Android"]
        );
        assert_eq!(history.apply(&[boot()], 101), ["Android"]);
        let mut history = History::default();
        history.apply(&[seen("8f79c4a7e231", "Redmi K40", "4EE7")], 100);
        assert_eq!(
            history.apply(&[seen("8f79c4a7e231", "Pixel 9 Pro", "4EE7")], 101),
            ["Pixel 9 Pro"]
        );
        assert_eq!(history.apply(&[boot()], 101), ["Android"]);
    }

    #[test]
    fn exported_stub_requires_previously_verified_exact_instance() {
        let mut history = History::default();
        history.apply(&[seen("8f79c4a7e231", "Redmi K40", "4EE7")], 100);
        let mut exported = seen("8f79c4a7e231", "Android", "4EE7");
        exported.unique = false;
        exported.exported = true;
        assert_eq!(history.apply(&[exported], 100), ["Redmi K40"]);
        let mut other = seen("8f79c4a7e231", "Android", "D00D");
        other.unique = false;
        other.exported = true;
        assert_eq!(history.apply(&[other], 100), ["Android"]);
    }

    #[test]
    fn generic_driver_names_and_invalid_text_are_never_learned() {
        for name in [
            "Android",
            "Android ADB Interface",
            "Fastboot",
            "fastbootd",
            "Android Bootloader Interface",
            "MTP USB Device",
            "USB 3.0",
            "ADB Interface 2",
            "未知设备 1",
            "Pixel 9\nPro",
            "Xiaomi",
            "18d1:4ee7",
        ] {
            assert!(specific_name(name, false).is_none(), "{name}");
        }
        assert!(specific_name(&"A".repeat(161), true).is_none());
        assert_eq!(
            specific_name(" motorola razr ", true).as_deref(),
            Some("motorola razr")
        );
    }

    #[test]
    fn only_same_physical_devices_interfaces_are_inspected_for_child_names() {
        let hardware = "VID_18D1&PID_4EE7";
        assert!(native::same_usb_branch(
            r"USB\VID_18D1&PID_4EE7&MI_00\6&123&0",
            hardware
        ));
        assert!(!native::same_usb_branch(
            r"USB\VID_18D1&PID_4EE7\different-phone",
            hardware
        ));
        assert!(!native::same_usb_branch(
            r"USB\VID_1234&PID_5678&MI_00\x",
            hardware
        ));
    }

    #[test]
    fn history_is_bounded_expires_and_repeated_polls_do_not_change_it() {
        let mut history = History::default();
        let item = || seen("8f79c4a7e231", "Redmi K40", "4EE7");
        history.apply(&[item()], 100);
        let first = serde_json::to_vec(&history).unwrap();
        history.apply(&[item()], 100);
        assert_eq!(serde_json::to_vec(&history).unwrap(), first);
        assert_eq!(
            history.apply(&[seen("8f79c4a7e231", "Android", "D00D")], 281),
            ["Android"]
        );
        for i in 0..MAX_ENTRIES + 5 {
            history.apply(&[seen(&format!("D7B95C1F{i:04}"), "Pixel 9", "4EE7")], 281);
        }
        assert_eq!(history.entries.len(), MAX_ENTRIES);
    }

    #[test]
    fn encrypted_history_survives_restart_and_corruption_falls_back() {
        let dir = std::env::temp_dir().join(format!("usblink-names-{}", uuid::Uuid::new_v4()));
        let path = dir.join("names.dat");
        let mut history = History::default();
        history.apply(&[seen("8f79c4a7e231", "Redmi K40", "4EE7")], 100);
        history.save(&path).unwrap();
        let bytes = fs::read(&path).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("Redmi K40"));
        let mut restored = History::load(&path).unwrap();
        assert_eq!(
            restored.apply(&[seen("8f79c4a7e231", "Android", "D00D")], 101),
            ["Redmi K40"]
        );
        fs::write(&path, b"broken").unwrap();
        assert!(History::load(&path).is_none());
        fs::remove_dir_all(dir).unwrap();
    }
}

fn specific_name(value: &str, explicit_model: bool) -> Option<String> {
    let value = value.trim();
    let lower = value.to_lowercase();
    if !(2..=160).contains(&value.chars().count())
        || value.chars().any(char::is_control)
        || value.contains(['\\', '/', ':', '#'])
        || !value.chars().any(char::is_alphabetic)
        || [
            "android",
            "adb",
            "fastboot",
            "bootloader",
            "interface",
            "composite",
            "mtp",
            "usb",
            "vid_",
            "pid_",
            "unknown",
            "设备",
            "接口",
            "驱动",
            "调试",
            "便携",
            "未知",
        ]
        .iter()
        .any(|word| lower.contains(word))
        || (!explicit_model && !value.chars().any(|c| c.is_ascii_digit()))
    {
        return None;
    }
    Some(value.to_string())
}

impl History {
    fn apply(&mut self, observations: &[Observation], day: u64) -> Vec<String> {
        self.entries
            .retain(|_, entry| entry.day <= day && day - entry.day <= MAX_AGE_DAYS);
        let mut counts = BTreeMap::new();
        for item in observations {
            if let Some(key) = &item.serial {
                *counts.entry(key).or_insert(0) += 1;
            }
        }
        // Invalidate all duplicates before resolving any row, regardless of order.
        for (key, count) in counts {
            if count > 1 {
                self.entries.insert(
                    key.clone(),
                    Remembered {
                        name: None,
                        instances: vec![],
                        day,
                    },
                );
            }
        }
        let result = observations
            .iter()
            .map(|item| {
                let fallback = item.model.as_ref().unwrap_or(&item.description).clone();
                let Some(key) = &item.serial else {
                    return fallback;
                };
                let verified = item.unique
                    || (item.exported
                        && self
                            .entries
                            .get(key)
                            .is_some_and(|entry| entry.instances.contains(&item.instance)));
                if !verified {
                    return fallback;
                }
                if let Some(entry) = self.entries.get_mut(key) {
                    entry.day = day;
                    if let (Some(old), Some(current)) = (&entry.name, &item.model) {
                        if !old.eq_ignore_ascii_case(current) {
                            entry.name = None;
                        }
                    }
                    if item.unique
                        && entry.name.is_some()
                        && !entry.instances.contains(&item.instance)
                        && entry.instances.len() < 8
                    {
                        entry.instances.push(item.instance.clone());
                    }
                    return item
                        .model
                        .clone()
                        .or_else(|| entry.name.clone())
                        .unwrap_or(fallback);
                }
                if let Some(name) = &item.model {
                    self.entries.insert(
                        key.clone(),
                        Remembered {
                            name: Some(name.clone()),
                            instances: vec![item.instance.clone()],
                            day,
                        },
                    );
                }
                fallback
            })
            .collect();
        while self.entries.len() > MAX_ENTRIES {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.day)
                .map(|(key, _)| key.clone())
                .unwrap();
            self.entries.remove(&oldest);
        }
        result
    }

    fn load(path: &Path) -> Option<Self> {
        if fs::metadata(path).ok()?.len() > MAX_FILE_BYTES {
            return None;
        }
        let history: Self =
            serde_json::from_slice(&mesh::unprotect(&fs::read(path).ok()?).ok()?).ok()?;
        if history.entries.len() > MAX_ENTRIES
            || history.entries.iter().any(|(key, entry)| {
                key.len() != 64
                    || !key.bytes().all(|c| c.is_ascii_hexdigit())
                    || entry.instances.len() > 8
                    || entry
                        .instances
                        .iter()
                        .any(|id| id.len() != 64 || !id.bytes().all(|c| c.is_ascii_hexdigit()))
                    || entry
                        .name
                        .as_ref()
                        .is_some_and(|name| specific_name(name, true).is_none())
            })
        {
            return None;
        }
        Some(history)
    }

    fn save(&self, path: &Path) -> Result<(), String> {
        let data = serde_json::to_vec(self).map_err(|e| e.to_string())?;
        StagedFile::prepare(path, &mesh::protect(&data)?)?.commit()
    }
}

pub(crate) fn resolve(items: &[LocalStateDevice]) -> Vec<String> {
    static HISTORY: OnceLock<Mutex<History>> = OnceLock::new();
    let path = mesh::app_root()
        .ok()
        .map(|root| root.join("device-names.dat"));
    let store = HISTORY
        .get_or_init(|| Mutex::new(path.as_deref().and_then(History::load).unwrap_or_default()));
    let Ok(mut history) = store.lock() else {
        return items.iter().map(|item| item.description.clone()).collect();
    };
    let observations: Vec<_> = items.iter().map(native::observe).collect();
    let before = serde_json::to_vec(&*history).ok();
    let day = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        / 86400;
    let names = history.apply(&observations, day);
    if before != serde_json::to_vec(&*history).ok() {
        if let Some(path) = path {
            // Optional naming must never block USB enumeration or authorization.
            // A failed save still leaves the current session's history usable.
            let _ = history.save(&path);
        }
    }
    names
}

mod native {
    use super::*;
    use std::ptr::null_mut;
    use windows_sys::Win32::Devices::DeviceAndDriverInstallation::*;
    use windows_sys::Win32::Devices::Properties::*;
    use windows_sys::Win32::Foundation::DEVPROPKEY;

    fn string(node: u32, key: &DEVPROPKEY) -> Option<String> {
        let mut buffer = [0u16; 512];
        let mut size = std::mem::size_of_val(&buffer) as u32;
        let mut kind = 0;
        let status = unsafe {
            CM_Get_DevNode_PropertyW(
                node,
                key,
                &mut kind,
                buffer.as_mut_ptr().cast(),
                &mut size,
                0,
            )
        };
        if status != CR_SUCCESS
            || kind != DEVPROP_TYPE_STRING
            || size < 2
            || size as usize > std::mem::size_of_val(&buffer)
            || size % 2 != 0
        {
            return None;
        }
        let buffer = &buffer[..size as usize / 2];
        let end = buffer.iter().position(|&c| c == 0)?;
        String::from_utf16(&buffer[..end]).ok()
    }

    fn model(node: u32) -> Option<String> {
        for (key, explicit) in [
            (&DEVPKEY_Device_Model, true),
            (&DEVPKEY_Device_BusReportedDeviceDesc, false),
            (&DEVPKEY_Device_FriendlyName, false),
            (&DEVPKEY_Device_DeviceDesc, false),
        ] {
            if let Some(name) = string(node, key).and_then(|name| specific_name(&name, explicit)) {
                return Some(name);
            }
        }
        None
    }

    fn instance(node: u32) -> Option<String> {
        let mut buffer = [0u16; 512];
        if unsafe { CM_Get_Device_IDW(node, buffer.as_mut_ptr(), buffer.len() as u32, 0) }
            != CR_SUCCESS
        {
            return None;
        }
        let end = buffer.iter().position(|&c| c == 0)?;
        String::from_utf16(&buffer[..end]).ok()
    }

    // MTP/WPD often supplies the model on a child interface. Never cross into a
    // different USB device under a hub, nor look at unrelated historical nodes.
    fn child_model(root: u32, hardware: &str) -> Option<String> {
        let mut pending = vec![(root, 0)];
        let mut visited = HashSet::new();
        let mut names = Vec::new();
        while let Some((node, depth)) = pending.pop() {
            if !visited.insert(node) || visited.len() > 64 {
                return None;
            }
            if node != root {
                let id = instance(node)?.to_ascii_uppercase();
                if id.starts_with("USB\\") && !same_usb_branch(&id, hardware) {
                    continue;
                }
                if let Some(name) = model(node) {
                    names.push(name);
                }
            }
            if depth >= 4 {
                continue;
            }
            let mut child = 0;
            if unsafe { CM_Get_Child(&mut child, node, 0) } != CR_SUCCESS {
                continue;
            }
            for _ in 0..64 {
                pending.push((child, depth + 1));
                let mut sibling = 0;
                if unsafe { CM_Get_Sibling(&mut sibling, child, 0) } != CR_SUCCESS {
                    break;
                }
                child = sibling;
            }
            if pending.len() > 64 {
                return None;
            }
        }
        let first = names.first()?;
        names
            .iter()
            .all(|name| name.eq_ignore_ascii_case(first))
            .then(|| first.clone())
    }

    pub(super) fn same_usb_branch(id: &str, hardware: &str) -> bool {
        id.strip_prefix("USB\\")
            .and_then(|id| id.split('\\').next())
            .is_some_and(|value| value.starts_with(&format!("{hardware}&MI_")))
    }

    pub(super) fn observe(item: &LocalStateDevice) -> Observation {
        let mut observation = Observation {
            serial: serial_key(&item.instance_id),
            instance: digest(&item.instance_id),
            description: item.description.clone(),
            model: specific_name(&item.description, false),
            exported: item
                .stub_instance_id
                .as_ref()
                .is_some_and(|id| !id.is_empty()),
            ..Observation::default()
        };
        // While exported, the original path can be absent or occupied by an
        // imported copy. Only reuse an instance verified before the export.
        if observation.exported {
            return observation;
        }
        let id: Vec<u16> = item.instance_id.encode_utf16().chain(Some(0)).collect();
        let mut node = 0;
        if unsafe { CM_Locate_DevNodeW(&mut node, id.as_ptr(), CM_LOCATE_DEVNODE_NORMAL) }
            != CR_SUCCESS
        {
            return observation;
        }
        let mut capabilities = 0u32;
        let mut length = std::mem::size_of_val(&capabilities) as u32;
        observation.unique = unsafe {
            CM_Get_DevNode_Registry_PropertyW(
                node,
                CM_DRP_CAPABILITIES,
                null_mut(),
                (&mut capabilities as *mut u32).cast(),
                &mut length,
                0,
            )
        } == CR_SUCCESS
            && length == 4
            && capabilities & CM_DEVCAP_UNIQUEID != 0;
        let hardware = item
            .instance_id
            .split('\\')
            .nth(1)
            .unwrap_or("")
            .to_ascii_uppercase();
        observation.model = model(node)
            .or_else(|| child_model(node, &hardware))
            .or(observation.model);
        observation
    }
}

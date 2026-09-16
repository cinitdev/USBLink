use regex::Regex;
use serde::Serialize;
use std::collections::HashSet;
use std::time::Duration;

use crate::{classify, ensure_success, find_executable, safe_remote_name, UsbDevice};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct AttachedDevice {
    pub(crate) host: String,
    pub(crate) port: u16,
    #[serde(flatten)]
    device: UsbDevice,
}

impl AttachedDevice {
    pub(crate) fn matches(&self, host: &str, bus_id: &str) -> bool {
        self.host.eq_ignore_ascii_case(host) && self.device.bus_id == bus_id
    }

    pub(crate) fn matches_identity(&self, host: &str, bus_id: &str, vid_pid: &str) -> bool {
        self.matches(host, bus_id) && self.device.vid_pid.eq_ignore_ascii_case(vid_pid)
    }
}

pub(crate) fn list() -> Result<Vec<AttachedDevice>, String> {
    list_with_timeout(Duration::from_secs(30))
}

pub(crate) fn list_with_timeout(timeout: Duration) -> Result<Vec<AttachedDevice>, String> {
    let executable = find_executable("usbip.exe").ok_or("未安装 usbip-win2")?;
    let content = ensure_success(crate::process::run(&executable, &["port"], timeout)?)?;
    parse(&content)
}

// Format verified against usbip-win2 v.0.9.8.0 userspace/usbip/port.cpp.
pub(crate) fn parse(content: &str) -> Result<Vec<AttachedDevice>, String> {
    let invalid = || "无法识别 USB/IP 已挂载端口输出，连接状态待确认".to_string();
    let header = Regex::new(r"(?im)^\s*Port\s+(\d+):\s*device in use at[^\r\n]*").unwrap();
    let product = Regex::new(r"(?m)^\s*(.+?)\s+\(([0-9a-fA-F]{4}:[0-9a-fA-F]{4})\)\s*$").unwrap();
    let source = Regex::new(r"(?m)^\s*->\s*usbip://(.+):(\d+)/(\d+-\d+(?:\.\d+)*)\s*$").unwrap();
    let headers: Vec<_> = header.captures_iter(content).collect();
    let preamble_end = headers
        .first()
        .map_or(content.len(), |capture| capture.get(0).unwrap().start());
    if content[..preamble_end].lines().any(|line| {
        let line = line.trim();
        !line.is_empty() && line != "Imported USB devices" && !line.chars().all(|c| c == '=')
    }) {
        return Err(invalid());
    }

    let mut ports = HashSet::new();
    let mut devices = Vec::new();
    for (index, capture) in headers.iter().enumerate() {
        let port = capture[1].parse::<u16>().map_err(|_| invalid())?;
        if !(1..=255).contains(&port) || !ports.insert(port) {
            return Err(invalid());
        }
        let start = capture.get(0).unwrap().end();
        let end = headers
            .get(index + 1)
            .map_or(content.len(), |next| next.get(0).unwrap().start());
        let block = &content[start..end];
        let product = product.captures(block).ok_or_else(invalid)?;
        let source = source.captures(block).ok_or_else(invalid)?;
        let host = source[1].trim_matches(['[', ']']).to_string();
        crate::validate_host(&host)?;
        let service = source[2].parse::<u16>().map_err(|_| invalid())?;
        if service == 0 {
            return Err(invalid());
        }
        let vid_pid = product[2].to_ascii_lowercase();
        let name = safe_remote_name(&product[1], &vid_pid);
        devices.push(AttachedDevice {
            host,
            port,
            device: UsbDevice {
                bus_id: source[3].to_string(),
                detail: classify(&name, &vid_pid),
                vid_pid,
                name,
                shared: true,
                attached: true,
                friendly_name: false,
            },
        });
    }
    Ok(devices)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "Imported USB devices\n====================\nPort 01: device in use at High Speed(480Mbps)\n         Google Inc. : Nexus/Pixel Device (charging + debug) (18d1:4ee7)\n           -> usbip://10.126.126.2:3240/3-2\n           -> remote bus/dev: 003/002\n           -> serial: example\n           -> mode: auto";

    #[test]
    fn parses_official_port_format() {
        let devices = parse(SAMPLE).unwrap();
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].host, "10.126.126.2");
        assert_eq!(devices[0].port, 1);
        assert_eq!(devices[0].device.bus_id, "3-2");
        assert_eq!(devices[0].device.vid_pid, "18d1:4ee7");
        assert!(devices[0].device.attached);
        assert_eq!(devices[0].device.name, "Android 调试设备");
    }

    #[test]
    fn distinguishes_identical_bus_ids_on_different_hosts() {
        let second = SAMPLE
            .replace("Port 01", "Port 02")
            .replace("10.126.126.2", "10.126.126.3");
        let second = second.split("Port 02").nth(1).unwrap();
        let devices = parse(&format!("{SAMPLE}\nPort 02{second}")).unwrap();
        assert_eq!(devices.len(), 2);
        assert_ne!(devices[0].host, devices[1].host);
    }

    #[test]
    fn empty_success_means_no_imported_devices() {
        assert!(parse("").unwrap().is_empty());
        assert!(parse("Imported USB devices\n====================\n")
            .unwrap()
            .is_empty());
    }

    #[test]
    fn unrecognized_or_incomplete_output_is_not_reported_as_disconnected() {
        assert!(parse("[error] could not open driver").is_err());
        assert!(parse("Port 01: device in use at High Speed(480Mbps)").is_err());
        assert!(parse(&SAMPLE.replace("-> usbip://", "-> invalid://")).is_err());
        assert!(parse(&SAMPLE.replace("Port 01", "Port 256")).is_err());
    }

    #[test]
    fn accepts_crlf_hub_bus_ids_and_ipv6_hosts() {
        let sample = SAMPLE
            .replace("10.126.126.2", "[fd00::2]")
            .replace("/3-2\n", "/3-2.1\n")
            .replace('\n', "\r\n");
        let devices = parse(&sample).unwrap();
        assert_eq!(devices[0].host, "fd00::2");
        assert_eq!(devices[0].device.bus_id, "3-2.1");
    }
}

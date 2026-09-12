use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::thread;
use std::time::Duration;

use crate::{find_executable, query_local_devices, validate_bus_id, UsbDevice};

const MAGIC: &[u8; 8] = b"USBLINK2";
const TOKEN_LENGTH: usize = 32;
const MAX_RESPONSE_LENGTH: usize = 64 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_millis(900);
const IO_TIMEOUT: Duration = Duration::from_secs(2);
pub(crate) const PORT: u16 = 3241;

#[derive(Clone, Debug, Deserialize, Serialize)]
struct DeviceName {
    bus_id: String,
    vid_pid: String,
    name: String,
}

pub(crate) fn start_server() -> Result<(), String> {
    let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT));
    let listener = TcpListener::bind(address)
        .map_err(|error| format!("failed to start device name service: {error}"))?;
    thread::Builder::new()
        .name("usblink-device-names".into())
        .spawn(move || {
            for connection in listener.incoming() {
                let Ok(stream) = connection else {
                    break;
                };
                let _ = handle_connection(stream);
            }
        })
        .map_err(|error| format!("failed to start device name thread: {error}"))?;
    Ok(())
}

fn handle_connection(mut stream: TcpStream) -> Result<(), String> {
    let Some(expected_token) = crate::mesh::device_metadata_token()? else {
        return Ok(());
    };
    handle_authenticated_connection(&mut stream, &expected_token, &local_shared_device_names())
}

fn handle_authenticated_connection(
    stream: &mut TcpStream,
    expected_token: &[u8; TOKEN_LENGTH],
    names: &[DeviceName],
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|error| error.to_string())?;

    let mut request = [0u8; MAGIC.len() + TOKEN_LENGTH];
    stream
        .read_exact(&mut request)
        .map_err(|error| error.to_string())?;
    if &request[..MAGIC.len()] != MAGIC || !tokens_match(&request[MAGIC.len()..], expected_token) {
        return Ok(());
    }

    let payload = serde_json::to_vec(names)
        .map_err(|error| format!("failed to encode device names: {error}"))?;
    if payload.len() > MAX_RESPONSE_LENGTH {
        return Ok(());
    }
    stream
        .write_all(&(payload.len() as u32).to_be_bytes())
        .and_then(|_| stream.write_all(&payload))
        .map_err(|error| error.to_string())
}

fn tokens_match(received: &[u8], expected: &[u8; TOKEN_LENGTH]) -> bool {
    received.len() == expected.len()
        && received
            .iter()
            .zip(expected)
            .fold(0u8, |difference, (left, right)| difference | (left ^ right))
            == 0
}

fn local_shared_device_names() -> Vec<DeviceName> {
    let Some(usbipd) = find_executable("usbipd.exe") else {
        return Vec::new();
    };
    query_local_devices(&usbipd)
        .unwrap_or_default()
        .into_iter()
        .filter(|device| device.shared)
        .map(|device| DeviceName {
            bus_id: device.bus_id,
            vid_pid: device.vid_pid,
            name: device.name,
        })
        .collect()
}

fn fetch(host: &str) -> Option<Vec<DeviceName>> {
    let token = crate::mesh::device_metadata_token().ok()??;
    let addresses = (host, PORT).to_socket_addrs().ok()?;
    fetch_from_addresses(addresses, &token)
}

fn fetch_from_addresses(
    addresses: impl IntoIterator<Item = SocketAddr>,
    token: &[u8; TOKEN_LENGTH],
) -> Option<Vec<DeviceName>> {
    let mut stream = addresses
        .into_iter()
        .filter_map(|address| TcpStream::connect_timeout(&address, CONNECT_TIMEOUT).ok())
        .next()?;
    stream.set_read_timeout(Some(IO_TIMEOUT)).ok()?;
    stream.set_write_timeout(Some(IO_TIMEOUT)).ok()?;
    stream.write_all(MAGIC).ok()?;
    stream.write_all(token).ok()?;

    let mut length = [0u8; 4];
    stream.read_exact(&mut length).ok()?;
    let length = u32::from_be_bytes(length) as usize;
    if length > MAX_RESPONSE_LENGTH {
        return None;
    }
    let mut payload = vec![0u8; length];
    stream.read_exact(&mut payload).ok()?;
    let mut names: Vec<DeviceName> = serde_json::from_slice(&payload).ok()?;
    names.retain(valid_device_name);
    Some(names)
}

fn valid_device_name(device: &DeviceName) -> bool {
    validate_bus_id(&device.bus_id).is_ok()
        && device.vid_pid.len() == 9
        && device.name.chars().count() <= 160
        && !device.name.trim().is_empty()
        && !device.name.chars().any(char::is_control)
}

fn apply_source_names(devices: &mut [UsbDevice], names: &[DeviceName]) {
    for device in devices {
        let source = names.iter().find(|source| {
            source.bus_id == device.bus_id && source.vid_pid.eq_ignore_ascii_case(&device.vid_pid)
        });
        if let Some(source) = source {
            device.name = source.name.trim().to_string();
            device.detail = crate::classify(&device.name, &device.vid_pid);
            device.friendly_name = true;
        }
    }
}

pub(crate) fn enrich_remote_devices(host: &str, devices: &mut [UsbDevice]) {
    if let Some(names) = fetch(host) {
        apply_source_names(devices, &names);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_name_replaces_the_generic_usb_database_name() {
        let mut devices = vec![UsbDevice {
            bus_id: "3-2".into(),
            vid_pid: "18d1:4ee7".into(),
            name: "Android 调试设备".into(),
            detail: "Android 设备 · 18d1:4ee7".into(),
            shared: true,
            attached: false,
            friendly_name: false,
        }];
        let names = vec![DeviceName {
            bus_id: "3-2".into(),
            vid_pid: "18D1:4EE7".into(),
            name: "Redmi K40".into(),
        }];

        apply_source_names(&mut devices, &names);

        assert_eq!(devices[0].name, "Redmi K40");
        assert_eq!(devices[0].detail, "Android 设备 · 18d1:4ee7");
    }

    #[test]
    fn metadata_does_not_rename_a_different_device() {
        let mut devices = vec![UsbDevice {
            bus_id: "3-2".into(),
            vid_pid: "18d1:4ee7".into(),
            name: "Android 调试设备".into(),
            detail: "Android 设备 · 18d1:4ee7".into(),
            shared: true,
            attached: false,
            friendly_name: false,
        }];
        let names = vec![DeviceName {
            bus_id: "3-2".into(),
            vid_pid: "2717:ff48".into(),
            name: "Other phone".into(),
        }];

        apply_source_names(&mut devices, &names);

        assert_eq!(devices[0].name, "Android 调试设备");
    }

    #[test]
    fn authenticated_metadata_protocol_round_trip() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let token = [0x5a; TOKEN_LENGTH];
        let expected = vec![DeviceName {
            bus_id: "3-2".into(),
            vid_pid: "18d1:4ee7".into(),
            name: "Redmi K40".into(),
        }];
        let server_names = expected.clone();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            handle_authenticated_connection(&mut stream, &token, &server_names).unwrap();
        });

        let received = fetch_from_addresses([address], &token).unwrap();

        server.join().unwrap();
        assert_eq!(received.len(), 1);
        assert_eq!(received[0].bus_id, expected[0].bus_id);
        assert_eq!(received[0].vid_pid, expected[0].vid_pid);
        assert_eq!(received[0].name, expected[0].name);
    }
}

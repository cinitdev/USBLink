use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::{
    atomic::{AtomicUsize, Ordering},
    Arc, Mutex,
};
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
    static STARTED: Mutex<bool> = Mutex::new(false);
    let mut started = STARTED.lock().map_err(|_| "应用会话服务锁不可用")?;
    if *started {
        return Ok(());
    }
    let address = SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT));
    let listener = TcpListener::bind(address)
        .map_err(|error| format!("无法启动 USBLink 会话服务（TCP 3241）：{error}"))?;
    let active = Arc::new(AtomicUsize::new(0));
    thread::Builder::new()
        .name("usblink-device-names".into())
        .spawn(move || {
            for connection in listener.incoming() {
                let Ok(stream) = connection else {
                    break;
                };
                dispatch_connection(stream, &active, handle_connection);
            }
        })
        .map_err(|error| format!("failed to start device name thread: {error}"))?;
    *started = true;
    Ok(())
}

struct ConnectionPermit(Arc<AtomicUsize>);
impl Drop for ConnectionPermit {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

fn dispatch_connection(
    stream: TcpStream,
    active: &Arc<AtomicUsize>,
    handler: impl FnOnce(TcpStream) -> Result<(), String> + Send + 'static,
) {
    if active
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |count| {
            (count < 16).then_some(count + 1)
        })
        .is_err()
    {
        return;
    }
    let permit = ConnectionPermit(active.clone());
    let _ = thread::Builder::new()
        .name("usblink-session-request".into())
        .spawn(move || {
            let _permit = permit;
            let _ = handler(stream);
        });
}

fn handle_connection(mut stream: TcpStream) -> Result<(), String> {
    stream
        .set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|e| e.to_string())?;
    stream
        .set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|e| e.to_string())?;
    let mut magic = [0; 8];
    stream.read_exact(&mut magic).map_err(|e| e.to_string())?;
    if &magic == crate::presence::MAGIC {
        if let Some(key) = crate::mesh::presence_token()? {
            return crate::presence::respond(&mut stream, &key, crate::presence::Phase::current);
        }
        return Ok(());
    }
    if &magic != MAGIC {
        return Ok(());
    }
    let Some(expected_token) = crate::mesh::device_metadata_token()? else {
        return Ok(());
    };
    handle_authenticated_connection(&mut stream, &expected_token, local_shared_device_names)
}

fn handle_authenticated_connection(
    stream: &mut TcpStream,
    expected_token: &[u8; TOKEN_LENGTH],
    names: impl FnOnce() -> Vec<DeviceName>,
) -> Result<(), String> {
    stream
        .set_read_timeout(Some(IO_TIMEOUT))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(IO_TIMEOUT))
        .map_err(|error| error.to_string())?;

    let mut request = [0u8; TOKEN_LENGTH];
    stream
        .read_exact(&mut request)
        .map_err(|error| error.to_string())?;
    if !tokens_match(&request, expected_token) {
        return Ok(());
    }
    let payload = serde_json::to_vec(&names())
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
    // Only one worker may run usbipd; other slots remain available for presence.
    static NAME_QUERY: Mutex<()> = Mutex::new(());
    let Ok(_guard) = NAME_QUERY.try_lock() else {
        return Vec::new();
    };
    if !crate::sharing_session::session().status().ready {
        return Vec::new();
    }
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
    fn unauthenticated_name_request_never_queries_usb_devices() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut magic = [0; 8];
            stream.read_exact(&mut magic).unwrap();
            handle_authenticated_connection(&mut stream, &[7; 32], || {
                panic!("must authenticate before running usbipd")
            })
            .unwrap();
        });
        assert!(fetch_from_addresses([address], &[8; 32]).is_none());
        server.join().unwrap();
    }

    #[test]
    fn slow_names_do_not_block_authenticated_presence_on_the_same_listener() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let (entered_tx, entered_rx) = std::sync::mpsc::channel();
        let (release_tx, release_rx) = std::sync::mpsc::channel();
        let active = Arc::new(AtomicUsize::new(0));
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().unwrap();
            dispatch_connection(stream, &active, move |mut stream| {
                let mut magic = [0; 8];
                stream.read_exact(&mut magic).unwrap();
                handle_authenticated_connection(&mut stream, &[7; 32], || {
                    entered_tx.send(()).unwrap();
                    release_rx.recv_timeout(Duration::from_secs(5)).unwrap();
                    vec![]
                })
            });
            let (stream, _) = listener.accept().unwrap();
            dispatch_connection(stream, &active, |mut stream| {
                stream.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
                let mut magic = [0; 8];
                stream.read_exact(&mut magic).unwrap();
                crate::presence::respond(&mut stream, &[7; 32], || crate::presence::Phase::Ready)
            });
        });
        let client = thread::spawn(move || fetch_from_addresses([address], &[7; 32]));
        entered_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(
            crate::presence::query(address, &[7; 32]),
            Some(crate::presence::Phase::Ready)
        );
        release_tx.send(()).unwrap();
        assert!(client.join().unwrap().is_some());
        server.join().unwrap();
    }

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
            let mut magic = [0; 8];
            stream.read_exact(&mut magic).unwrap();
            assert_eq!(&magic, MAGIC);
            handle_authenticated_connection(&mut stream, &token, || server_names).unwrap();
        });

        let received = fetch_from_addresses([address], &token).unwrap();

        server.join().unwrap();
        assert_eq!(received.len(), 1);
        assert_eq!(received[0].bus_id, expected[0].bus_id);
        assert_eq!(received[0].vid_pid, expected[0].vid_pid);
        assert_eq!(received[0].name, expected[0].name);
    }
}

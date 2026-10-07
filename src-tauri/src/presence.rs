use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;
use uuid::Uuid;

pub(crate) const MAGIC: &[u8; 8] = b"USBLINK3";
const REQUEST: &[u8] = b"USBLink presence request v1\0";
const RESPONSE: &[u8] = b"USBLink presence response v1\0";
type Auth = Hmac<Sha256>;

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Phase {
    Preparing = 0,
    Ready = 1,
    Closing = 2,
    Failed = 3,
    AndroidOff = 4,
    AndroidReady = 5,
    AndroidPreparing = 6,
    AndroidFailed = 7,
    AndroidUsbOff = 8,
    AndroidUsbReady = 9,
    AndroidUsbPreparing = 10,
    AndroidUsbFailed = 11,
}

impl Phase {
    pub(crate) fn current() -> Self {
        match crate::sharing_session::session().status().phase {
            "ready" => Self::Ready,
            "closing" | "closed" => Self::Closing,
            "failed" => Self::Failed,
            _ => Self::Preparing,
        }
    }
}

fn authenticator(key: &[u8; 32], domain: &[u8], nonce: &[u8; 16], payload: &[u8]) -> Auth {
    let mut mac = Auth::new_from_slice(key).expect("HMAC accepts a 32-byte key");
    mac.update(domain);
    mac.update(nonce);
    mac.update(payload);
    mac
}

// The listener has already consumed MAGIC. No USB commands run on this path.
pub(crate) fn respond(
    stream: &mut TcpStream,
    key: &[u8; 32],
    phase: impl FnOnce() -> Phase,
) -> Result<(), String> {
    let mut nonce = [0; 16];
    let mut tag = [0; 32];
    stream
        .read_exact(&mut nonce)
        .and_then(|_| stream.read_exact(&mut tag))
        .map_err(|e| e.to_string())?;
    authenticator(key, REQUEST, &nonce, &[])
        .verify_slice(&tag)
        .map_err(|_| "会话请求认证失败".to_string())?;
    let payload = [phase() as u8];
    let tag = authenticator(key, RESPONSE, &nonce, &payload)
        .finalize()
        .into_bytes();
    stream
        .write_all(&payload)
        .and_then(|_| stream.write_all(&tag))
        .map_err(|e| e.to_string())
}

pub(crate) fn query(address: SocketAddr, key: &[u8; 32]) -> Option<Phase> {
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_millis(900)).ok()?;
    stream.set_read_timeout(Some(Duration::from_secs(2))).ok()?;
    stream
        .set_write_timeout(Some(Duration::from_secs(2)))
        .ok()?;
    let nonce = *Uuid::new_v4().as_bytes();
    let tag = authenticator(key, REQUEST, &nonce, &[])
        .finalize()
        .into_bytes();
    stream.write_all(MAGIC).ok()?;
    stream.write_all(&nonce).ok()?;
    stream.write_all(&tag).ok()?;
    let mut payload = [0];
    let mut tag = [0; 32];
    stream.read_exact(&mut payload).ok()?;
    stream.read_exact(&mut tag).ok()?;
    verify_response(key, &nonce, payload[0], &tag)
}

fn verify_response(key: &[u8; 32], nonce: &[u8; 16], phase: u8, tag: &[u8]) -> Option<Phase> {
    authenticator(key, RESPONSE, nonce, &[phase])
        .verify_slice(tag)
        .ok()?;
    match phase {
        0 => Some(Phase::Preparing),
        1 => Some(Phase::Ready),
        2 => Some(Phase::Closing),
        3 => Some(Phase::Failed),
        4 => Some(Phase::AndroidOff),
        5 => Some(Phase::AndroidReady),
        6 => Some(Phase::AndroidPreparing),
        7 => Some(Phase::AndroidFailed),
        8 => Some(Phase::AndroidUsbOff),
        9 => Some(Phase::AndroidUsbReady),
        10 => Some(Phase::AndroidUsbPreparing),
        11 => Some(Phase::AndroidUsbFailed),
        _ => None,
    }
}

#[derive(Clone, Debug, serde::Deserialize, PartialEq)]
pub(crate) struct UsbExport {
    pub bus_id: String,
    pub vid_pid: String,
    pub path: String,
    pub name: String,
}

// The nonce binds this record to a fresh request, independently of the raw USB/IP list.
pub(crate) fn usb_export(host: &str) -> Result<UsbExport, String> {
    let key = crate::mesh::presence_token()?.ok_or("没有可用的配对认证")?;
    let ip = host.parse::<std::net::Ipv4Addr>().map_err(|_| "手机地址无效")?;
    query_usb_export((ip, 3241).into(), &key).ok_or_else(|| "手机 USB 共享信息未通过实时认证，请检查手机共享开关并刷新".into())
}

fn query_usb_export(address: SocketAddr, key: &[u8;32]) -> Option<UsbExport> {
    let mut stream=TcpStream::connect_timeout(&address,Duration::from_millis(900)).ok()?;
    stream.set_read_timeout(Some(Duration::from_secs(2))).ok()?;
    stream.set_write_timeout(Some(Duration::from_secs(2))).ok()?;
    let nonce=*Uuid::new_v4().as_bytes();
    let auth=authenticator(key,b"USBLink USB export request v1\0",&nonce,&[]).finalize().into_bytes();
    stream.write_all(b"USBLINK4").ok()?;
    stream.write_all(&nonce).ok()?;
    stream.write_all(&auth).ok()?;
    let mut size=[0;4];stream.read_exact(&mut size).ok()?;
    let size=u32::from_be_bytes(size) as usize;
    if size==0 || size>2048{return None;}
    let mut payload=vec![0;size];let mut tag=[0;32];
    stream.read_exact(&mut payload).ok()?;stream.read_exact(&mut tag).ok()?;
    authenticator(key,b"USBLink USB export response v1\0",&nonce,&payload).verify_slice(&tag).ok()?;
    let value:UsbExport=serde_json::from_slice(&payload).ok()?;
    if !valid_usb_export(&value){return None;}
    Some(value)
}

fn valid_usb_export(value:&UsbExport)->bool {
    value.bus_id=="99-1" && value.vid_pid=="18d1:4ee7"
        && value.path.strip_prefix("/usblink/experimental/adb/").is_some_and(|id| Uuid::parse_str(id).is_ok())
        && !value.name.trim().is_empty() && value.name.chars().count()<=160 && !value.name.chars().any(char::is_control)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn android_test_key() -> [u8; 32] {
        use sha2::Digest;
        let mut hash = Sha256::new();
        hash.update(b"USBLink application presence v1\0");
        hash.update("0123456789abcdef".repeat(4).as_bytes());
        hash.finalize().into()
    }

    #[test]
    fn android_and_windows_share_wire_vectors() {
        let key = android_test_key();
        let nonce = std::array::from_fn(|i| i as u8);
        let hex = |bytes: &[u8]| bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
        assert_eq!(
            hex(&key),
            "7e9a8a4e70233fa17d8b20a9cac62f594b4993e08b93b86ef7cf0ecfac3911e9"
        );
        assert_eq!(
            hex(&authenticator(&key, REQUEST, &nonce, &[])
                .finalize()
                .into_bytes()),
            "ed02256e94319d33347f2bfd42b3c26fff1fbb340ed2694fe1a2e07ed6f5c9eb"
        );
        let tag = authenticator(&key, RESPONSE, &nonce, &[5])
            .finalize()
            .into_bytes();
        assert_eq!(
            hex(&tag),
            "d07cecc77d74e12e65581e052bfc3f85543aaf511f3fb2a7c5fbb4de4a416af0"
        );
        assert_eq!(
            verify_response(&key, &nonce, 5, &tag),
            Some(Phase::AndroidReady)
        );
        assert_eq!(verify_response(&key, &nonce, 1, &tag), None);
    }

    #[test]
    #[ignore = "requires mobile experimental module host classes; loopback only"]
    fn android_usb_metadata_java_interoperability() {
        use std::io::{BufRead,BufReader};
        use std::process::{Child,Command,Stdio};
        struct Fixture(Child);
        impl Drop for Fixture {fn drop(&mut self){let _=self.0.kill();let _=self.0.wait();}}
        let classes=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../mobile/build/usb-module/test-classes");
        let mut fixture=Fixture(Command::new("java").args(["-cp",classes.to_str().unwrap(),"io.usblink.mobile.PresenceTestMain","--serve-usb"])
            .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit()).spawn().unwrap());
        let (sender,receiver)=std::sync::mpsc::channel();let output=fixture.0.stdout.take().unwrap();
        std::thread::spawn(move||{for line in BufReader::new(output).lines(){if sender.send(line.unwrap()).is_err(){break;}}});
        let port:u16=receiver.recv_timeout(Duration::from_secs(5)).unwrap().trim().parse().unwrap();
        let address=([127,0,0,1],port).into();let key=android_test_key();
        assert_eq!(query(address,&key),Some(Phase::AndroidUsbReady));
        let record=query_usb_export(address,&key).unwrap();
        assert_eq!(record.bus_id,"99-1");assert!(record.name.contains("测试手机"));
        assert_eq!(query_usb_export(address,&[0;32]),None);
        fixture.0.stdin.as_mut().unwrap().write_all(b"off\n").unwrap();
        assert_eq!(receiver.recv_timeout(Duration::from_secs(3)).unwrap(),"off");
        assert_eq!(query(address,&key),Some(Phase::AndroidUsbOff));
        assert_eq!(query_usb_export(address,&key),None);
    }

    #[test]
    #[ignore = "requires JDK and mobile/build/test-classes from mobile/scripts/build.ps1"]
    fn android_java_server_interoperates_with_windows_client() {
        use std::io::{BufRead, BufReader};
        use std::process::{Child, Command, Stdio};
        struct Fixture(Child);
        impl Drop for Fixture {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let classes =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../mobile/build/test-classes");
        let mut fixture = Fixture(
            Command::new("java")
                .args([
                    "-cp",
                    classes.to_str().unwrap(),
                    "io.usblink.mobile.PresenceTestMain",
                    "--serve",
                ])
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::inherit())
                .spawn()
                .unwrap(),
        );
        let (sender, receiver) = std::sync::mpsc::channel();
        let output = fixture.0.stdout.take().unwrap();
        std::thread::spawn(move || {
            for line in BufReader::new(output).lines() {
                if sender.send(line.unwrap()).is_err() {
                    break;
                }
            }
        });
        let port: u16 = receiver
            .recv_timeout(Duration::from_secs(8))
            .unwrap()
            .trim()
            .parse()
            .unwrap();
        let address = ([127, 0, 0, 1], port).into();
        assert_eq!(
            query(address, &android_test_key()),
            Some(Phase::AndroidReady)
        );
        assert_eq!(query(address, &[0; 32]), None);
        fixture
            .0
            .stdin
            .as_mut()
            .unwrap()
            .write_all(b"off\n")
            .unwrap();
        assert_eq!(
            receiver.recv_timeout(Duration::from_secs(3)).unwrap(),
            "off"
        );
        assert_eq!(query(address, &android_test_key()), Some(Phase::AndroidOff));
        fixture.0.kill().unwrap();
        fixture.0.wait().unwrap();
        assert_eq!(query(address, &android_test_key()), None);
    }

    #[test]
    fn real_socket_authenticates_each_phase_and_rejects_other_pairings() {
        for phase in [
            Phase::Ready,
            Phase::Preparing,
            Phase::Closing,
            Phase::Failed,
            Phase::AndroidOff,
            Phase::AndroidReady,
            Phase::AndroidPreparing,
            Phase::AndroidFailed,
            Phase::AndroidUsbOff,
            Phase::AndroidUsbReady,
            Phase::AndroidUsbPreparing,
            Phase::AndroidUsbFailed,
        ] {
            for same_key in [true, false] {
                let listener = TcpListener::bind("127.0.0.1:0").unwrap();
                let address = listener.local_addr().unwrap();
                let server = std::thread::spawn(move || {
                    let (mut stream, _) = listener.accept().unwrap();
                    let mut magic = [0; 8];
                    stream.read_exact(&mut magic).unwrap();
                    assert_eq!(&magic, MAGIC);
                    let result = respond(&mut stream, &[7; 32], || phase);
                    assert_eq!(result.is_ok(), same_key);
                });
                assert_eq!(
                    query(address, &[if same_key { 7 } else { 8 }; 32]),
                    same_key.then_some(phase)
                );
                server.join().unwrap();
            }
        }
    }

    #[test]
    fn rejects_replayed_modified_and_reflected_responses() {
        let key = [7; 32];
        let nonce = [1; 16];
        let tag = authenticator(&key, RESPONSE, &nonce, &[1])
            .finalize()
            .into_bytes();
        assert_eq!(verify_response(&key, &nonce, 1, &tag), Some(Phase::Ready));
        assert_eq!(verify_response(&key, &[2; 16], 1, &tag), None);
        assert_eq!(verify_response(&key, &nonce, 0, &tag), None);
        let reflected = authenticator(&key, REQUEST, &nonce, &[])
            .finalize()
            .into_bytes();
        assert_eq!(verify_response(&key, &nonce, 1, &reflected), None);
    }
}

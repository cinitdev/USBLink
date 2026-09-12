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
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    #[test]
    fn real_socket_authenticates_each_phase_and_rejects_other_pairings() {
        for phase in [
            Phase::Ready,
            Phase::Preparing,
            Phase::Closing,
            Phase::Failed,
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

use std::net::SocketAddrV4;
use windows_sys::Win32::NetworkManagement::IpHelper::{GetTcpTable, MIB_TCPROW_LH, MIB_TCPTABLE};

#[derive(Clone, Copy, Debug, PartialEq)]
#[repr(u8)]
pub(crate) enum State {
    Unknown = 0,
    Present = 1,
    Gone = 2,
}

// Read the kernel TCP table, including connections owned by the USB/IP driver.
// Never run a USB command or infer disconnection from an idle byte counter.
pub(crate) fn query(local: SocketAddrV4, remote: SocketAddrV4) -> State {
    let mut bytes = 0;
    let first = unsafe { GetTcpTable(std::ptr::null_mut(), &mut bytes, 0) };
    if first != 122 { return State::Unknown; } // ERROR_INSUFFICIENT_BUFFER
    for _ in 0..3 {
        if !(4..=4 * 1024 * 1024).contains(&bytes) { return State::Unknown; }
        // DWORD alignment is required for MIB_TCPTABLE/MIB_TCPROW_LH.
        let mut storage = vec![0u32; (bytes as usize + 3) / 4];
        let capacity = storage.len() * 4;
        let result = unsafe { GetTcpTable(storage.as_mut_ptr().cast(), &mut bytes, 0) };
        if result == 122 { continue; } // Only repeat a read when the table grew.
        if result != 0 || bytes as usize > capacity { return State::Unknown; }
        let offset = std::mem::offset_of!(MIB_TCPTABLE, table);
        let count = storage[0] as usize;
        let stride = std::mem::size_of::<MIB_TCPROW_LH>();
        if (bytes as usize) < offset || count > (bytes as usize - offset) / stride {
            return State::Unknown;
        }
        let rows = (0..count).map(|index| unsafe {
            std::ptr::read_unaligned(storage.as_ptr().cast::<u8>().add(offset + index * stride).cast::<MIB_TCPROW_LH>())
        });
        return classify(rows, local, remote);
    }
    State::Unknown
}

fn classify(rows: impl Iterator<Item = MIB_TCPROW_LH>, local: SocketAddrV4, remote: SocketAddrV4) -> State {
    let mut unknown = false;
    for row in rows {
        if row.dwLocalAddr.to_ne_bytes() != local.ip().octets()
            || row.dwRemoteAddr.to_ne_bytes() != remote.ip().octets()
            || u16::from_be(row.dwLocalPort as u16) != local.port()
            || u16::from_be(row.dwRemotePort as u16) != remote.port() { continue; }
        match unsafe { row.Anonymous.dwState } {
            3..=5 => return State::Present, // SYN_SENT, SYN_RCVD, ESTABLISHED
            1 | 6..=12 => {} // Already closed or closing, cannot carry USB/IP.
            _ => unknown = true,
        }
    }
    if unknown { State::Unknown } else { State::Gone }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Shutdown, TcpListener, TcpStream};

    #[test]
    fn exact_tuple_and_closed_states() {
        let local = "10.2.3.4:51001".parse::<SocketAddrV4>().unwrap();
        let remote = "10.2.3.5:3240".parse::<SocketAddrV4>().unwrap();
        let mut row = MIB_TCPROW_LH::default();
        row.dwLocalAddr = u32::from_ne_bytes(local.ip().octets());
        row.dwRemoteAddr = u32::from_ne_bytes(remote.ip().octets());
        row.dwLocalPort = local.port().to_be() as u32;
        row.dwRemotePort = remote.port().to_be() as u32;
        for state in [1, 6, 7, 8, 9, 10, 11, 12] {
            row.Anonymous.dwState = state;
            assert_eq!(classify([row].into_iter(), local, remote), State::Gone);
        }
        row.Anonymous.dwState = 5;
        assert_eq!(classify([row].into_iter(), local, remote), State::Present);
        for (a,b) in [("10.2.3.4:51002","10.2.3.5:3240"), ("10.2.3.4:51001","10.2.3.6:3240"),
                      ("10.2.3.9:51001","10.2.3.5:3240"), ("10.2.3.4:51001","10.2.3.5:3242")] {
            assert_eq!(classify([row].into_iter(), a.parse().unwrap(), b.parse().unwrap()), State::Gone);
        }
        row.Anonymous.dwState = 100;
        assert_eq!(classify([row].into_iter(), local, remote), State::Unknown);
    }

    #[test]
    fn windows_observes_idle_socket_then_local_close_without_peer_close() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_peer, _) = listener.accept().unwrap();
        let local = match client.local_addr().unwrap() { std::net::SocketAddr::V4(a) => a, _ => unreachable!() };
        let remote = match client.peer_addr().unwrap() { std::net::SocketAddr::V4(a) => a, _ => unreachable!() };
        assert_eq!(query(local, remote), State::Present);
        client.shutdown(Shutdown::Both).unwrap();
        drop(client);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while query(local, remote) == State::Present && std::time::Instant::now() < deadline {
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert_eq!(query(local, remote), State::Gone);
    }
}

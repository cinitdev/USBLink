use std::mem::size_of;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;
use windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE;
use windows_sys::Win32::NetworkManagement::IpHelper::{
    IcmpCloseHandle, IcmpCreateFile, IcmpSendEcho, ICMP_ECHO_REPLY,
};

#[derive(Debug, PartialEq)]
pub(crate) struct Health {
    pub(crate) online: bool,
    pub(crate) usb_ready: bool,
    pub(crate) problem: Option<String>,
}

fn classify(usb_ready: bool, echo_reply: bool) -> Health {
    Health {
        online: usb_ready || echo_reply,
        usb_ready,
        problem: if usb_ready {
            None
        } else if echo_reply {
            Some("电脑在线，但 USB 共享服务不可达，请检查对方的 usbipd-win 和共享设置".into())
        } else {
            Some("对方电脑离线或网络不可达，请确认对方已开机并加入同一连接".into())
        },
    }
}

fn tcp_reachable(address: SocketAddr) -> bool {
    TcpStream::connect_timeout(&address, Duration::from_millis(800)).is_ok()
}

fn echo_succeeded(count: u32, status: u32, address: u32, expected: u32) -> bool {
    count > 0 && status == 0 && address == expected
}

fn echo(ip: Ipv4Addr) -> bool {
    let handle = unsafe { IcmpCreateFile() };
    if handle.is_null() || handle == INVALID_HANDLE_VALUE {
        return false;
    }
    let request = b"USBLink presence";
    // The address is stored in network byte order in the Win32 DWORD.
    let address = u32::from_ne_bytes(ip.octets());
    let bytes = size_of::<ICMP_ECHO_REPLY>() + request.len() + 8;
    let mut buffer = vec![0u64; bytes.div_ceil(size_of::<u64>())];
    let count = unsafe {
        let count = IcmpSendEcho(
            handle,
            address,
            request.as_ptr().cast(),
            request.len() as u16,
            std::ptr::null(),
            buffer.as_mut_ptr().cast(),
            (buffer.len() * size_of::<u64>()) as u32,
            800,
        );
        IcmpCloseHandle(handle);
        count
    };
    let reply = unsafe { &*buffer.as_ptr().cast::<ICMP_ECHO_REPLY>() };
    echo_succeeded(count, reply.Status, reply.Address, address)
}

pub(crate) fn probe(host: &str) -> Health {
    let Ok(ip) = host.parse::<Ipv4Addr>() else {
        return classify(false, false);
    };
    let usb_ready = tcp_reachable(SocketAddr::from((ip, 3240)));
    // A successful TCP handshake is also an online signal when ICMP is blocked.
    classify(usb_ready, !usb_ready && echo(ip))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    #[test]
    fn separates_online_computer_from_available_usb_service() {
        assert!(classify(true, false).online);
        assert!(classify(true, false).usb_ready);
        assert!(classify(false, true).online);
        assert!(!classify(false, true).usb_ready);
        assert!(classify(false, true).problem.unwrap().contains("电脑在线"));
        assert!(!classify(false, false).online);
        assert!(classify(false, false)
            .problem
            .unwrap()
            .contains("离线或网络不可达"));
    }
    #[test]
    fn icmp_error_replies_and_other_hosts_cannot_report_online() {
        assert!(!echo_succeeded(0, 0, 1, 1));
        assert!(!echo_succeeded(1, 11003, 1, 1));
        assert!(!echo_succeeded(1, 0, 2, 1));
        assert!(echo_succeeded(1, 0, 1, 1));
    }
    #[test]
    fn native_icmp_confirms_loopback_presence() {
        assert!(echo(Ipv4Addr::LOCALHOST));
    }
    #[test]
    fn probes_real_listener_and_detects_its_shutdown() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        assert!(tcp_reachable(address));
        drop(listener);
        assert!(!tcp_reachable(address));
    }
}

use crate::presence::{self, Phase};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

#[derive(Debug, PartialEq)]
pub(crate) struct Health {
    pub(crate) online: bool,
    pub(crate) usb_ready: bool,
    pub(crate) problem: Option<String>,
}

fn classify(phase: Option<Phase>, usb_reachable: bool) -> Health {
    let online = matches!(phase, Some(Phase::Ready | Phase::Preparing | Phase::Failed));
    let usb_ready = phase == Some(Phase::Ready) && usb_reachable;
    let problem = match phase {
        None => {
            Some("对方 USBLink 未运行或不可达，请双方更新并打开 USBLink，允许会话访问规则后重试")
        }
        Some(Phase::Closing) => Some("对方 USBLink 正在退出，已停止接受新连接"),
        Some(Phase::Preparing) => Some("对方 USBLink 在线，正在清理上次的 USB 会话"),
        Some(Phase::Failed) => Some("对方 USBLink 在线，但 USB 会话未就绪，请对方处理清理错误"),
        Some(Phase::Ready) if !usb_reachable => {
            Some("对方 USBLink 在线，但 USB 共享服务不可达，请对方检查共享设置")
        }
        Some(Phase::Ready) => None,
    }
    .map(str::to_string);
    Health {
        online,
        usb_ready,
        problem,
    }
}

fn probe_addresses(app: SocketAddr, usb: SocketAddr, key: &[u8; 32]) -> Health {
    let phase = presence::query(app, key);
    let usb_reachable = phase == Some(Phase::Ready)
        && TcpStream::connect_timeout(&usb, Duration::from_millis(800)).is_ok();
    classify(phase, usb_reachable)
}

pub(crate) fn probe(host: &str) -> Health {
    let Ok(ip) = host.parse::<Ipv4Addr>() else {
        return classify(None, false);
    };
    let Some(key) = crate::mesh::presence_token().ok().flatten() else {
        return classify(None, false);
    };
    probe_addresses(
        (ip, crate::device_metadata::PORT).into(),
        (ip, 3240).into(),
        &key,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::Read, net::TcpListener, thread};

    #[test]
    fn background_usb_service_does_not_keep_a_closed_application_online() {
        let usb = TcpListener::bind("127.0.0.1:0").unwrap();
        let app = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = app.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = app.accept().unwrap();
            let mut magic = [0; 8];
            stream.read_exact(&mut magic).unwrap();
            presence::respond(&mut stream, &[7; 32], || Phase::Ready).unwrap();
        });
        let health = probe_addresses(address, usb.local_addr().unwrap(), &[7; 32]);
        assert!(health.online && health.usb_ready);
        server.join().unwrap();
        let health = probe_addresses(address, usb.local_addr().unwrap(), &[7; 32]);
        assert!(!health.online && !health.usb_ready);
    }

    #[test]
    fn receiving_only_application_is_online_and_closing_application_is_unavailable() {
        assert!(classify(Some(Phase::Ready), false).online);
        assert!(!classify(Some(Phase::Ready), false).usb_ready);
        assert!(!classify(Some(Phase::Closing), true).online);
        assert!(!classify(None, true).online);
        for phase in [Phase::Preparing, Phase::Failed] {
            assert!(classify(Some(phase), true).online);
            assert!(!classify(Some(phase), true).usb_ready);
        }
    }
}

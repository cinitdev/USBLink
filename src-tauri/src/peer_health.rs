use crate::presence::{self, Phase};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

#[derive(Debug, PartialEq)]
pub(crate) struct Health {
    pub(crate) online: bool,
    pub(crate) usb_ready: bool,
    pub(crate) usb_kind: &'static str,
    pub(crate) os: &'static str,
    pub(crate) adb_ready: bool,
    pub(crate) adb_state: &'static str,
    pub(crate) problem: Option<String>,
}

fn classify(phase: Option<Phase>, transport_reachable: bool) -> Health {
    let android = matches!(
        phase,
        Some(
            Phase::AndroidOff
                | Phase::AndroidReady
                | Phase::AndroidPreparing
                | Phase::AndroidFailed
                | Phase::AndroidUsbOff
                | Phase::AndroidUsbReady
                | Phase::AndroidUsbPreparing
                | Phase::AndroidUsbFailed
        )
    );
    let online = phase.is_some() && phase != Some(Phase::Closing);
    let usb_ready = matches!(phase,Some(Phase::Ready | Phase::AndroidUsbReady)) && transport_reachable;
    let usb_kind = if matches!(phase,Some(Phase::AndroidUsbOff | Phase::AndroidUsbReady | Phase::AndroidUsbPreparing | Phase::AndroidUsbFailed)){"android-adb-experimental"}else{"usbip"};
    let adb_ready = phase == Some(Phase::AndroidReady) && transport_reachable;
    let adb_state = match phase {
        Some(Phase::AndroidOff) => "off",
        Some(Phase::AndroidReady) if transport_reachable => "ready",
        Some(Phase::AndroidPreparing) => "preparing",
        Some(Phase::AndroidReady | Phase::AndroidFailed) => "error",
        _ => "unavailable",
    };
    let problem = match phase {
        None => {
            Some("对方 USBLink 未运行或不可达，请双方更新并打开 USBLink，允许会话访问规则后重试")
        }
        Some(Phase::Closing) => Some("对方 USBLink 正在退出，已停止接受新连接"),
        Some(Phase::Preparing) => Some("对方 USBLink 在线，正在清理上次的 USB 会话"),
        Some(Phase::Failed) => Some("对方 USBLink 在线，但 USB 会话未就绪，请对方处理清理错误"),
        Some(Phase::Ready) if !transport_reachable => {
            Some("对方 USBLink 在线，但 USB 共享服务不可达，请对方检查共享设置")
        }
        Some(Phase::Ready) => None,
        Some(Phase::AndroidOff | Phase::AndroidPreparing) => None,
        Some(Phase::AndroidReady) if !transport_reachable => {
            Some("手机模块在线，但 ADB 转发端口不可达，请检查手机共享状态")
        }
        Some(Phase::AndroidReady) => None,
        Some(Phase::AndroidFailed) => {
            Some("手机模块在线，但共享遇到问题，请在手机 WebUI 查看并处理错误")
        }
        Some(Phase::AndroidUsbOff) => Some("手机在线，USB ADB 共享已关闭，请在手机 WebUI 开启"),
        Some(Phase::AndroidUsbPreparing) => Some("手机在线，正在准备 USB ADB 共享"),
        Some(Phase::AndroidUsbFailed) => Some("手机 USB ADB 共享遇到问题，请在手机 WebUI 查看错误"),
        Some(Phase::AndroidUsbReady) if !transport_reachable => Some("手机在线，但 USB 共享端口不可达"),
        Some(Phase::AndroidUsbReady) => None,
    }
    .map(str::to_string);
    Health {
        online,
        usb_ready,
        usb_kind,
        os: if android {
            "android"
        } else if phase.is_some() {
            "windows"
        } else {
            "unknown"
        },
        adb_ready,
        adb_state,
        problem,
    }
}

fn probe_addresses(app: SocketAddr, usb: SocketAddr, adb: SocketAddr, key: &[u8; 32]) -> Health {
    let phase = presence::query(app, key);
    let transport = match phase {
        Some(Phase::Ready | Phase::AndroidUsbReady) => Some(usb),
        Some(Phase::AndroidReady) => Some(adb),
        _ => None,
    };
    let reachable = transport.is_some_and(|address| {
        TcpStream::connect_timeout(&address, Duration::from_millis(800)).is_ok()
    });
    classify(phase, reachable)
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
        (ip, 3242).into(),
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
        let health = probe_addresses(
            address,
            usb.local_addr().unwrap(),
            usb.local_addr().unwrap(),
            &[7; 32],
        );
        assert!(health.online && health.usb_ready);
        server.join().unwrap();
        let health = probe_addresses(
            address,
            usb.local_addr().unwrap(),
            usb.local_addr().unwrap(),
            &[7; 32],
        );
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

    #[test]
    fn only_authenticated_android_usb_ready_authorizes_usb_import() {
        for phase in [Phase::AndroidUsbOff,Phase::AndroidUsbPreparing,Phase::AndroidUsbReady,Phase::AndroidUsbFailed] {
            let health=classify(Some(phase),true);
            assert_eq!(health.os,"android");assert_eq!(health.usb_kind,"android-adb-experimental");
            assert!(health.online);assert!(!health.adb_ready);
            assert_eq!(health.usb_ready,phase==Phase::AndroidUsbReady);
        }
        assert!(!classify(Some(Phase::AndroidUsbReady),false).usb_ready);
    }

    #[test]
    fn android_presence_never_authorizes_usbip_or_confuses_off_with_offline() {
        for phase in [
            Phase::AndroidOff,
            Phase::AndroidReady,
            Phase::AndroidPreparing,
            Phase::AndroidFailed,
        ] {
            let health = classify(Some(phase), true);
            assert!(health.online);
            assert_eq!(health.os, "android");
            assert!(!health.usb_ready);
            assert_eq!(health.adb_ready, phase == Phase::AndroidReady);
        }
        assert_eq!(classify(Some(Phase::AndroidOff), false).adb_state, "off");
        assert_eq!(classify(Some(Phase::AndroidOff), false).problem, None);
        assert!(!classify(Some(Phase::AndroidReady), false).adb_ready);
        assert!(
            !classify(None, true).online,
            "Open ADB alone cannot prove application presence"
        );
    }
}

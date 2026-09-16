use std::ptr::{null, null_mut};
use windows_sys::Win32::Devices::DeviceAndDriverInstallation::{
    CM_Get_DevNode_Registry_PropertyW, CM_Get_Parent, CM_Locate_DevNodeW, CM_DRP_SERVICE,
    CM_LOCATE_DEVNODE_NORMAL, CR_NO_SUCH_DEVNODE, CR_NO_SUCH_VALUE, CR_SUCCESS,
};

// A remote USB keeps the real device's VID/PID and serial, but its ancestors
// belong to usbip-win2's virtual host controller. Never match by product name.
pub(crate) fn is_imported(instance_id: &str) -> Result<Option<bool>, String> {
    let id: Vec<u16> = instance_id.encode_utf16().chain(Some(0)).collect();
    let mut node = 0;
    let status = unsafe { CM_Locate_DevNodeW(&mut node, id.as_ptr(), CM_LOCATE_DEVNODE_NORMAL) };
    if status == CR_NO_SUCH_DEVNODE {
        return Ok(None); // Disappeared since usbipd's snapshot; not shareable.
    }
    check(status)?;
    let mut root = 0;
    check(unsafe { CM_Locate_DevNodeW(&mut root, null(), CM_LOCATE_DEVNODE_NORMAL) })?;
    walk(node, root, service, |node| {
        let mut parent = 0;
        check(unsafe { CM_Get_Parent(&mut parent, node, 0) })?;
        Ok(parent)
    })
    .map(Some)
}

fn check(status: u32) -> Result<(), String> {
    if status == CR_SUCCESS {
        Ok(())
    } else {
        Err(format!(
            "无法确认 USB 设备来源，请刷新后重试（Windows 设备树错误 {status}）"
        ))
    }
}

fn service(node: u32) -> Result<String, String> {
    let mut buffer = [0u16; 512];
    let mut length = std::mem::size_of_val(&buffer) as u32;
    let status = unsafe {
        CM_Get_DevNode_Registry_PropertyW(
            node,
            CM_DRP_SERVICE,
            null_mut(),
            buffer.as_mut_ptr().cast(),
            &mut length,
            0,
        )
    };
    if status == CR_NO_SUCH_VALUE {
        return Ok(String::new()); // Composite device/interface without a service.
    }
    check(status)?;
    let end = buffer
        .iter()
        .position(|&value| value == 0)
        .unwrap_or(buffer.len());
    String::from_utf16(&buffer[..end]).map_err(|_| "USB 设备驱动名称无效，无法确认来源".into())
}

fn walk(
    mut node: u32,
    root: u32,
    mut service: impl FnMut(u32) -> Result<String, String>,
    mut parent: impl FnMut(u32) -> Result<u32, String>,
) -> Result<bool, String> {
    for _ in 0..64 {
        if node == root {
            return Ok(false);
        }
        if service(node)?.eq_ignore_ascii_case("usbip2_ude") {
            return Ok(true);
        }
        node = parent(node)?;
    }
    Err("USB 设备父节点异常，无法确认来源，请刷新后重试".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checks_ancestors_and_keeps_physical_devices_including_exported_stubs() {
        for leaf_service in ["WinUSB", "usbccgp", "VBoxUSB", ""] {
            let classify = |controller: &str| {
                walk(
                    3,
                    0,
                    |node| Ok(if node == 1 { controller } else { leaf_service }.into()),
                    |node| Ok(node - 1),
                )
            };
            assert!(classify("USBIP2_UDE").unwrap());
            assert!(!classify("USBXHCI").unwrap());
        }
    }

    #[test]
    fn failed_or_cyclic_ancestry_never_becomes_a_local_device() {
        assert!(walk(2, 0, |_| Err("access failed".into()), |_| Ok(0)).is_err());
        assert!(walk(2, 0, |_| Ok("WinUSB".into()), |_| Err("removed".into())).is_err());
        assert!(walk(2, 0, |_| Ok(String::new()), |node| Ok(node)).is_err());
    }
}

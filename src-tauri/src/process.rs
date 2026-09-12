use std::io::Read;
use std::os::windows::io::AsRawHandle;
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};
use std::thread;
use std::time::{Duration, Instant};
use windows_sys::Win32::Foundation::{GetLastError, ERROR_BROKEN_PIPE};
use windows_sys::Win32::System::Pipes::PeekNamedPipe;

// Drain both pipes while waiting: large stderr/stdout must not deadlock a query.
pub(crate) fn run(executable: &Path, args: &[&str], timeout: Duration) -> Result<Output, String> {
    let mut command = Command::new(executable);
    command.args(args).creation_flags(crate::CREATE_NO_WINDOW);
    run_command(&mut command, timeout).map_err(|error| {
        format!(
            "{}：{error}",
            executable
                .file_name()
                .unwrap_or(executable.as_os_str())
                .to_string_lossy()
        )
    })
}

fn drain_available(
    pipe: &mut (impl Read + AsRawHandle),
    bytes: &mut Vec<u8>,
) -> Result<(), String> {
    // Read only bytes already buffered. EOF can be held open indefinitely by a
    // grandchild, so neither a reader thread join nor read_to_end is bounded.
    let mut available = 0;
    let ok = unsafe {
        PeekNamedPipe(
            pipe.as_raw_handle(),
            std::ptr::null_mut(),
            0,
            std::ptr::null_mut(),
            &mut available,
            std::ptr::null_mut(),
        )
    };
    if ok == 0 {
        let error = unsafe { GetLastError() };
        return if error == ERROR_BROKEN_PIPE {
            Ok(())
        } else {
            Err(format!(
                "无法读取组件输出：{}",
                std::io::Error::from_raw_os_error(error as i32)
            ))
        };
    }
    // Bound each iteration so a continuously writing child cannot starve the timeout.
    let mut buffer = [0u8; 65536];
    let count = (available as usize).min(buffer.len());
    if count > 0 {
        let read = pipe
            .read(&mut buffer[..count])
            .map_err(|error| error.to_string())?;
        bytes.extend_from_slice(&buffer[..read]);
    }
    Ok(())
}

fn run_command(command: &mut Command, timeout: Duration) -> Result<Output, String> {
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("无法启动组件：{error}"))?;
    let mut stdout = child.stdout.take().expect("piped stdout");
    let mut stderr = child.stderr.take().expect("piped stderr");
    let mut out = Vec::new();
    let mut err = Vec::new();
    let start = Instant::now();
    let status = loop {
        if let Err(error) = drain_available(&mut stdout, &mut out)
            .and_then(|_| drain_available(&mut stderr, &mut err))
        {
            break Err(error);
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                // The process may have written its last bytes after the previous drain.
                if let Err(error) = drain_available(&mut stdout, &mut out)
                    .and_then(|_| drain_available(&mut stderr, &mut err))
                {
                    break Err(error);
                }
                break Ok(status);
            }
            Ok(None) if start.elapsed() < timeout => thread::sleep(Duration::from_millis(20)),
            Ok(None) => {
                break Err(format!(
                    "操作超时（{} 秒），请检查组件或网络后手动重试",
                    timeout.as_secs()
                ))
            }
            Err(error) => break Err(format!("无法等待组件：{error}")),
        }
    };
    if status.is_err() {
        let _ = child.kill();
        let _ = child.wait();
    }
    Ok(Output {
        status: status?,
        stdout: out,
        stderr: err,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn helper(mode: &str) -> Command {
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args(["--exact", "process::tests::child_process", "--nocapture"])
            .env("USBLINK_PROCESS_TEST", mode)
            .creation_flags(crate::CREATE_NO_WINDOW);
        command
    }

    #[test]
    fn child_process() {
        match std::env::var("USBLINK_PROCESS_TEST").as_deref() {
            Ok("sleep") => thread::sleep(Duration::from_secs(5)),
            Ok("descendant") => {
                let mut child = helper("sleep").spawn().unwrap();
                let _ = child.wait();
            }
            Ok("output") => {
                std::io::stdout()
                    .write_all(&vec![b'x'; 128 * 1024])
                    .unwrap();
                std::io::stderr()
                    .write_all(&vec![b'y'; 128 * 1024])
                    .unwrap();
            }
            Ok("fail") => std::process::exit(7),
            _ => {}
        }
    }

    #[test]
    fn terminates_timed_out_components() {
        let start = Instant::now();
        assert!(
            run_command(&mut helper("sleep"), Duration::from_millis(150))
                .unwrap_err()
                .contains("超时")
        );
        assert!(start.elapsed() < Duration::from_secs(3));
    }

    #[test]
    fn drains_large_output_without_deadlock() {
        let output = run_command(&mut helper("output"), Duration::from_secs(5)).unwrap();
        assert!(output.status.success());
        assert!(output.stdout.len() >= 128 * 1024);
        assert!(output.stderr.len() >= 128 * 1024);
    }

    #[test]
    fn preserves_component_failure_status() {
        let output = run_command(&mut helper("fail"), Duration::from_secs(5)).unwrap();
        assert_eq!(output.status.code(), Some(7));
    }
    #[test]
    fn timeout_does_not_wait_for_inherited_output_handles() {
        let start = Instant::now();
        assert!(run_command(&mut helper("descendant"), Duration::from_millis(150)).is_err());
        assert!(
            start.elapsed() < Duration::from_secs(3),
            "inherited output handles defeated the timeout"
        );
    }
}

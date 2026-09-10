use std::io::Read;
use std::os::windows::process::CommandExt;
use std::path::Path;
use std::process::{Command, Output, Stdio};
use std::thread;
use std::time::{Duration, Instant};

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

fn run_command(command: &mut Command, timeout: Duration) -> Result<Output, String> {
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("无法启动组件：{error}"))?;
    let mut stdout = child.stdout.take().expect("piped stdout");
    let mut stderr = child.stderr.take().expect("piped stderr");
    let out = thread::spawn(move || {
        let mut bytes = Vec::new();
        stdout.read_to_end(&mut bytes).map(|_| bytes)
    });
    let err = thread::spawn(move || {
        let mut bytes = Vec::new();
        stderr.read_to_end(&mut bytes).map(|_| bytes)
    });
    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
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
    let stdout = out.join().map_err(|_| "组件输出读取任务失败".to_string());
    let stderr = err.join().map_err(|_| "组件错误读取任务失败".to_string());
    Ok(Output {
        status: status?,
        stdout: stdout?.map_err(|error| format!("无法读取组件输出：{error}"))?,
        stderr: stderr?.map_err(|error| format!("无法读取组件错误：{error}"))?,
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
}

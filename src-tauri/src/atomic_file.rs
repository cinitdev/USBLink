use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use uuid::Uuid;

pub(crate) struct StagedFile {
    temporary: PathBuf,
    destination: PathBuf,
}

impl StagedFile {
    pub(crate) fn prepare(destination: &Path, bytes: &[u8]) -> Result<Self, String> {
        let parent = destination.parent().ok_or("配置路径没有父目录")?;
        fs::create_dir_all(parent).map_err(|error| format!("无法创建配置目录：{error}"))?;
        let staged = Self {
            temporary: parent.join(format!("{}.tmp", Uuid::new_v4().simple())),
            destination: destination.to_owned(),
        };
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&staged.temporary)
            .map_err(|error| format!("无法准备配置文件：{error}"))?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|error| format!("无法完整写入配置：{error}"))?;
        Ok(staged)
    }

    pub(crate) fn commit(self) -> Result<(), String> {
        fs::rename(&self.temporary, &self.destination)
            .map_err(|error| format!("无法替换配置文件，原文件已保留：{error}"))
    }
}

impl Drop for StagedFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.temporary);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::windows::fs::OpenOptionsExt;

    #[test]
    fn staged_profile_does_not_replace_live_data_until_commit() {
        let directory = std::env::temp_dir().join(format!("usblink-atomic-{}", Uuid::new_v4()));
        let path = directory.join("profile.dat");
        fs::create_dir_all(&directory).unwrap();
        fs::write(&path, b"old encrypted data").unwrap();
        let staged = StagedFile::prepare(&path, b"new encrypted data").unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"old encrypted data");
        staged.commit().unwrap();
        assert_eq!(fs::read(&path).unwrap(), b"new encrypted data");
        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_commit_keeps_old_file_and_cleans_temporary_file() {
        let directory = std::env::temp_dir().join(format!("usblink-atomic-{}", Uuid::new_v4()));
        let path = directory.join("profile.dat");
        fs::create_dir_all(&directory).unwrap();
        fs::write(&path, b"old").unwrap();
        let staged = StagedFile::prepare(&path, b"new").unwrap();
        let lock = OpenOptions::new()
            .read(true)
            .share_mode(0)
            .open(&path)
            .unwrap();
        assert!(staged.commit().is_err());
        drop(lock);
        assert_eq!(fs::read(&path).unwrap(), b"old");
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 1);
        fs::remove_dir_all(directory).unwrap();
    }
}

import React from 'react';
import { ShieldCheckmark24Regular } from '@fluentui/react-icons';

export function AdbRootControl({ value, stale, disabled, busy, run }) {
  if (!value) return null;
  const changing = value.state === 'changing' || busy === 'set-adb-root';
  const label = stale ? '权限待确认' : changing ? '正在切换' : value.active === true ? 'Root · UID 0' : value.active === false ? '普通权限 · UID 2000' : 'adbd 尚未就绪';
  return <>
    <div className="section-heading"><h2>ADB 权限</h2><span>重启后保留偏好</span></div>
    <section className="group adb-root-panel" aria-label="ADB Root 权限设置">
      <div className="row"><span className="row-icon"><ShieldCheckmark24Regular /></span><div className="row-content"><strong>ADB Root 权限</strong><span>{label}</span></div><button className="switch" role="switch" aria-label="ADB Root 权限" aria-checked={!!value.enabled} aria-busy={changing} disabled={disabled || changing} onClick={() => run('set-adb-root', { enabled: !value.enabled }, 'ADB Root 偏好已保存，请查看实际权限状态。')}><span /></button></div>
      <div className="panel-body"><p className="muted">默认开启。开关表示保存的偏好，是否生效以上方实际 UID 为准；需要 ROM 支持 adbd Root。</p><p className="quiet-note">切换只重启系统 adbd，不重启手机。会影响 USB 与远程调试连接，电脑需要手动重新连接设备；保留系统 RSA 授权与 SELinux 配置。</p>{value.problem && <p className={['error', 'conflict'].includes(value.state) ? 'field-error' : 'quiet-note'} role="status">{value.problem}</p>}</div>
    </section>
  </>;
}

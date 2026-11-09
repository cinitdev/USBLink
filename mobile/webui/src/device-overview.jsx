import React from 'react';
import { Phone24Regular, Desktop24Regular, UsbPlug24Regular, ChevronRight20Regular } from '@fluentui/react-icons';

// The saved preference and verified runtime state intentionally remain separate.
export function DeviceOverview({ device, sharing, stale, disabled, busy, busyMessage, usbMode, sessions, statusLabel, onConnections, onToggle, enableBlocked = '' }) {
  const connectionLabel = stale ? '连接状态待同步' : sessions.length ? sessions.length + ' 个远程会话' : sharing.active ? '等待电脑连接' : sharing.state === 'error' ? '共享已暂停' : sharing.enabled ? '共享准备中' : '暂无远程连接';
  return <>
    <section className="device-overview" aria-label="本机设备概览">
      <div className="device-heading">
        <div><p className="device-eyebrow">本机设备</p><h2>{device?.model || '等待手机信息'}</h2><p className="device-meta">{device?.modelCode && device.modelCode !== device.model ? device.modelCode : 'Android 手机'}</p></div>
        <span className="device-illustration"><Phone24Regular aria-hidden="true" /></span>
      </div>
      <div className="device-statistics">
        <div><strong>{device?.androidVersion || '—'}</strong><span>Android 版本</span></div>
        <div><strong>{stale || !device ? '—' : sessions.length}</strong><span>远程会话</span></div>
        <button className="device-arrow" onClick={onConnections} aria-label="查看远程连接"><ChevronRight20Regular /></button>
      </div>
      <div className={'device-runtime ' + (stale || sharing.state === 'error' ? 'is-warning' : '')}><i /><span>{stale ? '状态未确认' : statusLabel}</span><span className="transport-label">{usbMode ? 'USB ADB' : '系统 ADB'}</span></div>
    </section>
    <section className="group sharing-controls" aria-label="共享控制">
      <div className="row sharing-control">
        <span className="row-icon"><UsbPlug24Regular /></span>
        <div className="row-content"><strong>{usbMode ? '共享这台手机' : '共享本机调试'}</strong><span>{busy === 'set-sharing' ? busyMessage : sharing.enabled ? '已保存开启偏好 · 运行状态见上方' : enableBlocked || '开启即联网，关闭保留配对码'}</span></div>
        <button className="switch" role="switch" aria-checked={!!sharing.enabled} aria-busy={busy === 'set-sharing'} aria-label={usbMode ? '共享这台手机' : '共享本机调试'} disabled={disabled || (!sharing.enabled && !!enableBlocked)} onClick={onToggle}><span /></button>
      </div>
      <button className="session-shortcut" onClick={onConnections}><span className="row-icon"><Desktop24Regular /></span><span className="row-content"><strong>远程连接</strong><span>{connectionLabel}</span></span><ChevronRight20Regular /></button>
    </section>
  </>;
}

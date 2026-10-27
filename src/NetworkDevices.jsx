import { useMemo, useState } from 'react';
import { Desktop20Regular, Phone20Regular, Search20Regular, Dismiss16Regular, Checkmark16Regular } from '@fluentui/react-icons';
import { networkDeviceRows } from './network-devices.mjs';
import { peerStatusLabel } from './peer-state.mjs';

export function NetworkDevices({ peers, selectedIp, select, mounts, mountsKnown, disabled }) {
  const [query, setQuery] = useState('');
  const rows = useMemo(() => networkDeviceRows(peers, mounts, query), [peers, mounts, query]);
  return <section className="network-devices" aria-label="同一网络的设备">
    <header><div><h2>同一网络的设备 <span className="badge">{peers.length}</span></h2><p>切换查看来源不会断开已连接的 USB，也无需重新配对。</p></div>
      <div className="peer-search"><Search20Regular /><input type="search" aria-label="搜索网络设备" placeholder="搜索名称或地址" value={query} onChange={event => setQuery(event.target.value)} />{query && <button className="icon-button" aria-label="清除设备搜索" onClick={() => setQuery('')}><Dismiss16Regular /></button>}</div>
    </header>
    <div className="network-device-list">{rows.map(peer => {
      const Icon = peer.os === 'android' ? Phone20Regular : Desktop20Regular;
      const selected = peer.ip === selectedIp;
      return <button key={peer.ip} type="button" className={`network-device-row ${selected ? 'active' : ''}`} aria-label={`查看 ${peer.name} ${peer.ip} 的设备`} aria-pressed={selected} disabled={disabled} onClick={() => select(peer.ip)}>
        <Icon /><span className="peer-name"><strong>{peer.name}</strong><small>{peer.ip}</small></span>
        <span className="peer-state"><span><i className={`status-dot ${peer.online ? 'online' : 'offline'}`} />{peerStatusLabel(peer)}</span><small>{peer.mountedCount ? `${mountsKnown && peer.online && peer.usbReady !== false ? '已连接' : '保留本机挂载'} ${peer.mountedCount} 个 USB${mountsKnown ? '' : ' · 状态待确认'}` : '尚未连接 USB'}</small></span>
        <span className="peer-view">{selected ? <><Checkmark16Regular />正在查看</> : '查看设备'}</span>
      </button>;
    })}</div>
    {!rows.length && <p className="network-empty">{query ? '没有匹配的设备，请尝试其他名称或地址。' : '等待设备加入同一网络；手机需要开启共享，电脑需要打开 USBLink。'}</p>}
    {!!query && <p className="network-search-count">找到 {rows.length} / {peers.length} 台设备</p>}
  </section>;
}

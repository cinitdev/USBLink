import { useMemo, useState } from 'react';
import { Desktop20Regular, Phone20Regular, Search20Regular, Dismiss16Regular, ChevronDown16Regular } from '@fluentui/react-icons';
import { networkDeviceRows } from './network-devices.mjs';
import { peerStatusLabel } from './peer-state.mjs';

export function NetworkDevices({ peers, selectedIp, select, mounts, mountsKnown, disabled, children }) {
  const [query, setQuery] = useState('');
  const [collapsedIp, setCollapsedIp] = useState(null);
  const rows = useMemo(() => networkDeviceRows(peers, mounts, query), [peers, mounts, query]);
  return <section className="cx-members" aria-label="同一网络的设备">
    <header className="cx-members-toolbar"><p>选择一台设备，查看它共享的 USB</p><div className="cx-search"><Search20Regular /><input type="search" aria-label="搜索网络设备" placeholder="搜索名称或地址" value={query} onChange={event => setQuery(event.target.value)} />{query && <button className="icon-button" aria-label="清除设备搜索" onClick={() => setQuery('')}><Dismiss16Regular /></button>}</div></header>
    <div className="cx-peer-list">{rows.map(peer => {
      const Icon = peer.os === 'android' ? Phone20Regular : Desktop20Regular;
      const expanded = peer.ip === selectedIp && peer.ip !== collapsedIp;
      const status = !peer.online ? '离线' : peer.os === 'android' ? peer.usbReady || peer.adbReady ? '共享就绪' : '共享未就绪' : peer.usbReady === false ? '共享未就绪' : '在线';
      return <article key={peer.ip} className={`cx-peer ${expanded ? 'expanded' : ''}`}>
        <button type="button" className="cx-peer-toggle" aria-label={`查看 ${peer.name} ${peer.ip} 的设备`} aria-expanded={expanded} disabled={disabled} onClick={() => { setCollapsedIp(expanded ? peer.ip : null); if (peer.ip !== selectedIp) select(peer.ip); }}>
          <span className="cx-peer-icon"><Icon /></span>
          <span className="cx-peer-copy"><strong>{peer.name}</strong><small>{peer.os === 'android' ? 'Android 手机' : peer.os === 'windows' ? 'Windows 电脑' : '网络设备'}<span>·</span>{peer.ip}</small></span>
          <span className="cx-peer-connection">{peer.mountedCount > 0 ? `${mountsKnown && peer.online && peer.usbReady !== false ? '已连接' : '保留挂载'} ${peer.mountedCount} 个 USB` : ''}</span>
          <span className={`cx-peer-status ${peer.online ? 'online' : ''}`} title={peerStatusLabel(peer)}><i className={`status-dot ${peer.online ? 'online' : ''}`} />{status}</span>
          <ChevronDown16Regular className="cx-peer-chevron" />
        </button>
        {expanded && <div className="cx-peer-content">{children}</div>}
      </article>;
    })}</div>
    {!rows.length && <div className="cx-empty"><Search20Regular /><strong>{query ? '没有找到匹配的设备' : '等待设备加入'}</strong><p>{query ? '试试其他名称或网络地址' : '使用同一个配对码加入网络，并在来源设备上开启共享。'}</p>{query && <button className="cx-button" onClick={() => setQuery('')}>清除搜索</button>}</div>}
    <footer className="cx-list-footnote">{query ? `找到 ${rows.length} / ${peers.length} 台设备` : '设备数量不限 · 切换设备保留已有连接'}</footer>
  </section>;
}

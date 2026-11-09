import { useId, useState } from 'react';
import {
  Add20Regular, ArrowClockwise20Regular, Checkmark16Regular, Copy20Regular,
  Dismiss16Regular, ErrorCircle20Regular, Key20Regular, NetworkCheck20Regular,
  PlugConnected20Regular, PlugDisconnected20Regular, Power20Regular,
  Settings20Regular, UsbPlug20Regular,
} from '@fluentui/react-icons';
import { NetworkDevices } from './NetworkDevices.jsx';
import { canUsePeer, mountPresence } from './peer-state.mjs';
import { sameDevice } from './usb-state.mjs';

function Choice({ checked, mixed, disabled, label, onChange }) {
  return <button type="button" role="checkbox" aria-checked={mixed ? 'mixed' : checked} aria-label={label} disabled={disabled} className={`check ${checked || mixed ? 'checked' : ''}`} onClick={onChange}>{mixed ? <span aria-hidden="true">−</span> : checked && <Checkmark16Regular />}</button>;
}

function StateLabel({ children, ready = false, pending = false }) {
  return <span className={`cx-state ${ready ? 'ready' : ''} ${pending ? 'pending' : ''}`}>{pending ? <ArrowClockwise20Regular className="spin" /> : <i className={`status-dot ${ready ? 'online' : ''}`} />}{children}</span>;
}

function Empty({ icon: Icon = UsbPlug20Regular, title, children }) {
  return <div className="cx-empty"><span><Icon /></span><strong>{title}</strong><p>{children}</p></div>;
}

function RemoteDevices({ devices, selected, setSelected, toggle, activeBusIds, busy, loading, attached, host, attach, stopDevice, detachingDevice, unavailable, connectionKnown, peerProblem }) {
  const available = devices.filter(device => !device.attached);
  const all = available.length > 0 && available.every(device => selected.has(device.busId));
  return <section className="cx-usb-list" aria-label="共享的 USB 设备" aria-busy={loading}>
    <header><span>共享的 USB <b>{devices.length}</b></span>{available.length > 1 && <label><Choice label="全选共享设备" checked={all} mixed={!all && selected.size > 0} disabled={unavailable || !!busy} onChange={() => setSelected(all ? new Set() : new Set(available.map(device => device.busId)))} />全选</label>}</header>
    {devices.length ? devices.map(device => {
      const mount = attached.devices.find(item => sameDevice(item, { ...device, host }));
      const disconnecting = !!mount && !!detachingDevice && mount.port === detachingDevice.port && sameDevice(mount, detachingDevice);
      const starting = busy === 'attach' && activeBusIds.includes(device.busId);
      const selectable = !unavailable && !busy && !device.attached;
      const connected = device.attached && connectionKnown;
      const status = starting ? '正在连接' : disconnecting ? '正在断开' : connected ? '已连接' : peerProblem || (unavailable ? '状态待确认' : '可连接');
      return <div className={`cx-usb-row ${selected.has(device.busId) && !device.attached ? 'selected' : ''}`} key={device.busId} aria-busy={starting || disconnecting}>
        <Choice checked={selected.has(device.busId) && !device.attached} label={`选择 ${device.name}`} disabled={!selectable} onChange={() => toggle(device.busId)} />
        <span className="cx-usb-icon"><UsbPlug20Regular /></span>
        <div className="cx-device-copy"><strong>{device.name}</strong><small>USB {device.busId}<span>·</span>{device.vidPid}</small></div>
        <StateLabel ready={connected || selectable} pending={starting || disconnecting}>{status}</StateLabel>
        {mount ? <button className="cx-button" disabled={!!busy} aria-label={`断开 ${device.name}`} onClick={() => stopDevice(mount)}>{disconnecting ? '正在断开…' : '断开'}</button> : <button className="cx-button accent" disabled={!selectable} aria-label={`连接 ${device.name}`} onClick={() => attach([device.busId])}>{starting ? '正在连接…' : '连接设备'}</button>}
      </div>;
    }) : <Empty icon={loading ? ArrowClockwise20Regular : UsbPlug20Regular} title={loading ? '正在读取共享设备' : peerProblem || (unavailable ? '设备状态待确认' : '还没有共享的 USB')}>{loading ? '列表就绪后会自动显示' : peerProblem ? '对方恢复在线后会自动更新' : '请在这台手机或电脑上开启共享'}</Empty>}
  </section>;
}

function MountedDevices({ snapshot, stopAll, stopDevice, detachingDevice, busy, peers, networkReady, browse }) {
  const known = snapshot.ready && !snapshot.problem;
  return <section className="cx-mounted" aria-label="已连接到本机的 USB">
    <header className="cx-section-header"><div><h2>连接到这台电脑</h2><p>来自所有设备的 USB 连接</p></div>{snapshot.devices.length > 0 && <button className="cx-button" disabled={!!busy} onClick={stopAll}><PlugDisconnected20Regular />{busy === 'detach' && !detachingDevice ? '正在断开全部…' : '断开全部'}</button>}</header>
    {snapshot.problem && <p className="cx-notice error" role="status"><ErrorCircle20Regular />无法读取挂载状态，以下为上次结果：{snapshot.problem}</p>}
    {snapshot.devices.length ? <div className="cx-mount-list">{snapshot.devices.map(device => {
      const source = peers.find(peer => peer.ip === device.host);
      const disconnecting = !!detachingDevice && detachingDevice.port === device.port && sameDevice(detachingDevice, device);
      const presence = disconnecting ? '正在断开' : !known ? '状态待确认' : mountPresence(device, peers, networkReady);
      return <div className="cx-mount-row" key={`${device.host}|${device.port}`} aria-busy={disconnecting}>
        <span className="cx-mount-icon"><UsbPlug20Regular /></span>
        <div className="cx-device-copy"><strong>{device.name}</strong><small>来自 {source?.name || device.host}{source?.name && <><span>·</span>{device.host}</>}</small><small>USB {device.busId}<span>·</span>本机端口 {device.port}</small></div>
        <StateLabel ready={presence === '已连接'} pending={disconnecting}>{presence}</StateLabel>
        <button className="cx-button" disabled={!!busy} onClick={() => stopDevice(device)} aria-label={`断开 ${device.name}（${device.host}，端口 ${device.port}）`}><PlugDisconnected20Regular />{disconnecting ? '正在断开…' : '断开'}</button>
      </div>;
    })}</div> : <Empty icon={PlugConnected20Regular} title={known ? '还没有连接 USB 设备' : '正在确认 USB 连接'}>{known ? <><span>选择同一网络内的设备，连接后会显示在这里。</span><button className="cx-button accent" onClick={browse}>浏览网络设备</button></> : '读取到驱动状态后会自动更新'}</Empty>}
  </section>;
}

export function ConnectionPage({ activeBusIds, joinProblem, hidePairingCode, openingDownload, environment, mesh, createNetwork, joinNetwork, joinCode, setJoinCode, pairingCode, revealAndCopyCode, revealPairingCode, restartNetwork, repairNetwork, leaveNetwork, peers, selectedPeer, setSelectedPeerIp, devices, attached, remoteLoading, remoteProblem, selected, setSelected, toggle, busy, refresh, attach, stopAll, stopDevice, detachingDevice, openDownload, PairingSetup, AndroidPeerPanel, DependencyEmpty }) {
  const [view, setView] = useState('network');
  const [panel, setPanel] = useState('');
  const tabId = useId();
  const onlineCount = peers.filter(peer => peer.online).length;
  const usbPhone = selectedPeer?.os === 'android' && selectedPeer?.usbKind === 'android-adb-experimental';
  const androidPeer = selectedPeer?.os === 'android' && !usbPhone;
  const unavailable = !mesh.running || !canUsePeer(selectedPeer) || !!remoteProblem || !attached.ready || !!attached.problem;
  const peerProblem = selectedPeer && (!selectedPeer.online ? '对方离线' : selectedPeer.usbReady === false ? '共享未就绪' : '');
  const tabs = [{ id: 'network', label: '网络设备', count: peers.length }, { id: 'mounted', label: '已连接', count: attached.devices.length }];
  const togglePanel = name => { hidePairingCode(); setPanel(current => current === name ? '' : name); };
  const mounted = <MountedDevices snapshot={attached} stopAll={stopAll} stopDevice={stopDevice} detachingDevice={detachingDevice} busy={busy} peers={peers} networkReady={mesh.running} browse={() => setView('network')} />;
  let sourceContent;
  if (androidPeer) sourceContent = <AndroidPeerPanel peer={selectedPeer} networkReady={mesh.running} />;
  else if (!environment.usbipInstalled || !environment.usbipSafe) sourceContent = <DependencyEmpty icon={ErrorCircle20Regular} title={!environment.usbipInstalled ? '需要安装远程 USB 驱动' : '远程 USB 驱动需要更新'} text={environment.usbipProblem || '安装 usbip-win2 后即可连接共享设备。'} action={openingDownload === 'usbip' ? '正在打开…' : '打开驱动安装页面'} disabled={!!openingDownload} onClick={() => openDownload('usbip')} />;
  else sourceContent = <>
    {(remoteProblem || attached.problem || (selectedPeer && !canUsePeer(selectedPeer))) && <p className="cx-notice error" role="status"><ErrorCircle20Regular />{remoteProblem || attached.problem || selectedPeer.problem || '对方应用或共享服务未就绪，请在来源设备上检查共享状态。'}</p>}
    <RemoteDevices devices={devices} selected={selected} setSelected={setSelected} toggle={toggle} activeBusIds={activeBusIds} busy={busy} loading={remoteLoading} attached={attached} host={selectedPeer?.ip} attach={attach} stopDevice={stopDevice} detachingDevice={detachingDevice} unavailable={unavailable} connectionKnown={attached.ready && !attached.problem && mesh.running && canUsePeer(selectedPeer)} peerProblem={peerProblem} />
    {usbPhone && <p className="cx-transport-note">USB ADB 共享 · 首次连接需在手机授权 · 暂不支持 MTP 文件传输</p>}
  </>;

  return <div className="cx-page">
    <header className="cx-page-header"><div><h1>连接</h1><p>手机和电脑，都在同一个网络里。</p></div><div className="cx-header-actions"><button className="cx-button" onClick={refresh} disabled={!!busy} aria-label="刷新连接状态"><ArrowClockwise20Regular className={remoteLoading ? 'spin' : ''} />刷新</button>{mesh.configured && <button className="cx-button accent" aria-expanded={panel === 'invite'} onClick={() => togglePanel('invite')}><Add20Regular />添加设备</button>}</div></header>
    {mesh.configured && <section className={`cx-network-bar ${mesh.problem ? 'problem' : ''}`} aria-label="当前网络"><span className="cx-network-icon"><NetworkCheck20Regular /></span><div><strong>{mesh.problem ? '网络需要处理' : mesh.running ? '我的设备网络' : '正在准备网络'}</strong><p>{mesh.localIp || '等待分配地址'}<span>·</span>{onlineCount} 台设备在线</p></div><button className="cx-button" aria-label="管理网络" aria-expanded={panel === 'manage'} onClick={() => togglePanel('manage')}><Settings20Regular />管理网络</button></section>}
    <div className="cx-scroll">
      {mesh.problem && <p className="cx-notice error" role="status"><ErrorCircle20Regular />{mesh.problem}{mesh.needsRepair && <button className="cx-button" disabled={!!busy} onClick={repairNetwork}>{busy === 'mesh-repair' ? '正在修复…' : '修复网络'}</button>}</p>}
      {mesh.configured && panel && <section className="cx-panel" aria-label={panel === 'invite' ? '添加设备' : '网络管理'}><header><h2>{panel === 'invite' ? '让更多设备加入' : '网络管理'}</h2><button className="icon-button" aria-label="收起网络面板" onClick={() => togglePanel(panel)}><Dismiss16Regular /></button></header>
        <p>在手机模块或另一台电脑上选择“加入网络”，填入同一个配对码。请只分享给信任的人。</p>
        {!pairingCode && <div className="cx-invite-actions"><button className="cx-button" disabled={!!busy} onClick={revealPairingCode}><Key20Regular />显示配对码</button><button className="cx-button accent" disabled={!!busy} onClick={revealAndCopyCode}><Copy20Regular />复制配对码</button>{busy === 'mesh-code' && <span role="status">正在读取配对码…</span>}</div>}
        {panel === 'manage' && <><p>切换查看设备会保留现有连接。退出网络会忘记配对，需要重新加入。</p><div className="cx-manage-actions"><button className="cx-button" onClick={restartNetwork} disabled={!!busy}><Power20Regular />{busy === 'mesh-restart' ? '正在重启…' : '重新启动网络'}</button><button className="cx-button danger" onClick={leaveNetwork} disabled={!!busy}>退出网络</button></div></>}
      </section>}
      {pairingCode && <div className="cx-code"><Key20Regular /><input aria-label="当前配对码" readOnly value={pairingCode} /><button className="cx-button" disabled={!!busy} onClick={revealAndCopyCode}><Copy20Regular />复制配对码</button><button className="text-action" onClick={hidePairingCode}>隐藏</button></div>}
      {!mesh.configured ? <div className="cx-onboarding"><PairingSetup joinProblem={joinProblem} createNetwork={createNetwork} joinNetwork={joinNetwork} joinCode={joinCode} setJoinCode={setJoinCode} busy={busy} />{(attached.devices.length > 0 || attached.problem) && mounted}</div> : <section className="cx-workspace">
        <div className="cx-tabs" role="tablist" aria-label="连接视图">{tabs.map((tab, index) => <button key={tab.id} id={`${tabId}-${tab.id}`} role="tab" aria-selected={view === tab.id} aria-controls={`${tabId}-panel`} tabIndex={view === tab.id ? 0 : -1} onClick={() => setView(tab.id)} onKeyDown={event => { const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : null; if (next !== null) { event.preventDefault(); setView(tabs[next].id); document.getElementById(`${tabId}-${tabs[next].id}`)?.focus(); } }}>{tab.label}<span>{tab.count}</span>{tab.id === 'mounted' && attached.problem && <ErrorCircle20Regular aria-label="挂载状态待确认" />}</button>)}</div>
        <div role="tabpanel" id={`${tabId}-panel`} aria-labelledby={`${tabId}-${view}`}>
          <div hidden={view !== 'network'}><NetworkDevices key={mesh.networkName} peers={peers} selectedIp={selectedPeer?.ip} select={setSelectedPeerIp} mounts={attached.devices} mountsKnown={attached.ready && !attached.problem} disabled={!!busy}>{sourceContent}</NetworkDevices></div>
          {view === 'mounted' && mounted}
        </div>
      </section>}
    </div>
    {mesh.configured && view === 'network' && !androidPeer && (selected.size > 0 || busy === 'attach') && <footer className="cx-selection-bar"><div><strong>{busy === 'attach' ? `正在连接 ${activeBusIds.length} 个设备` : `已选择 ${selected.size} 个设备`}</strong><span>{selectedPeer?.name}{unavailable && ' · 等待连接条件就绪'}</span></div><button className="text-action" disabled={!!busy} onClick={() => setSelected(new Set())}>取消选择</button><button className="cx-button accent" disabled={unavailable || !environment.usbipSafe || !selected.size || !selectedPeer || !!busy} onClick={() => attach()}><PlugConnected20Regular />{busy === 'attach' ? '正在连接…' : '连接所选设备'}</button></footer>}
  </div>;
}

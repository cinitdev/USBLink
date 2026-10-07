import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Phone24Regular, Link24Regular, Settings24Regular, UsbPlug24Regular, ArrowSync20Regular, ShieldCheckmark24Regular, Desktop24Regular, Copy20Regular, Checkmark20Regular, Dismiss20Regular, ChevronRight20Regular, Info20Regular } from '@fluentui/react-icons';
import { createClient } from './api.mjs';
import { createRequestFlow, createStatusPolling, pollDelay } from './request-flow.mjs';
import './styles.css';

const client = createClient({ preview: new URLSearchParams(window.location.search).get('preview') === '1', scope: window, usbPreview: new URLSearchParams(window.location.search).get('usb') === '1' });
const pages = [{ id: 'devices', label: '设备', icon: Phone24Regular }, { id: 'connections', label: '连接', icon: Link24Regular }, { id: 'settings', label: '设置', icon: Settings24Regular }];
const statusLabels = { off: '共享已关闭', starting: '正在启动调试服务', waiting_system: '等待系统启动就绪', waiting_network: '等待设备网络', sharing: '正在共享', error: '共享遇到问题' };

function CopyField({ label, value, secret = false }) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { setCopied(false); setError(''); }, [value]);
  useEffect(() => { if (!copied) return; const timer = setTimeout(() => setCopied(false), 2000); return () => clearTimeout(timer); }, [copied]);
  const copy = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable');
      await navigator.clipboard.writeText(value); setCopied(true); setError('');
    } catch { setError('当前环境不支持复制，请长按下方内容选择复制。'); }
  };
  return <div className="copy-group"><div className="field-label">{label}</div><div className="copy-field"><code className={secret ? 'secret' : ''} tabIndex="0">{value}</code><button className="icon-button" type="button" onClick={copy} aria-label={`复制${label}`}>{copied ? <Checkmark20Regular /> : <Copy20Regular />}</button></div>{error && <p className="field-error" role="status">{error}</p>}</div>;
}
function Badge({ active, children, warning = false }) { return <span className={`badge ${active ? 'active' : ''} ${warning ? 'warning' : ''}`}><i />{children}</span>; }
function SectionTitle({ children, detail }) { return <div className="section-heading"><h2>{children}</h2>{detail && <span>{detail}</span>}</div>; }
function Row({ icon: Icon, title, detail, children }) { return <div className="row"><span className="row-icon"><Icon /></span><div className="row-content"><strong>{title}</strong><span>{detail}</span></div>{children}</div>; }

function SyncStatus({ observed, stale, busy, unavailable }) {
  const [stamp, setStamp] = useState(observed.current);
  useEffect(() => { const timer = setInterval(() => { if (!document.hidden) setStamp(observed.current); }, 1000); return () => clearInterval(timer); }, [observed]);
  const label = unavailable ? '服务未连接' : busy ? '正在处理操作' : stale ? '正在重新同步' : stamp ? '自动同步' : '正在同步';
  return <div className={`sync-status ${stale || unavailable ? 'is-stale' : ''}`}><span className="sync-dot" /><span>{label}</span>{stamp > 0 && !stale && !unavailable && <time dateTime={new Date(stamp).toISOString()}>{new Date(stamp).toLocaleTimeString('zh-CN', { hour12: false })}</time>}</div>;
}

function App() {
  const [page, setPage] = useState('devices');
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState('');
  const [pendingEnabled, setPendingEnabled] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [stale, setStale] = useState(false);
  const [notice, setNotice] = useState('');
  const [joinCode, setJoinCode] = useState('');
  const [networkCode, setNetworkCode] = useState('');
  const [relay, setRelay] = useState('');
  const [relayEdited, setRelayEdited] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const pageRef = useRef(page);
  pageRef.current = page;
  const flow = useRef(null);
  const lastStatus = useRef(null);
  const lastStatusJson = useRef('');
  const failures = useRef(0);
  const manualRefresh = useRef(false);
  const wake = useRef(() => {});
  const observed = useRef(0);
  const refresh = useCallback(async () => {
    if (manualRefresh.current) return;
    manualRefresh.current = true; setRefreshing(true);
    try { await flow.current?.read(); }
    finally { manualRefresh.current = false; setRefreshing(false); }
  }, []);
  useEffect(() => {
    let polling;
    const requests = createRequestFlow((action, payload) => client.request(action, payload), {
      onStatus(next) {
        const json = JSON.stringify(next);
        // A heartbeat with unchanged data must not redraw every row and icon.
        if (json !== lastStatusJson.current) { lastStatusJson.current = json; setStatus(next); }
        lastStatus.current = next; failures.current = 0; setStale(false); setError(''); setLoading(false);
        observed.current = Date.now(); polling?.confirmed();
      },
      onReadError(cause) { failures.current++; setError(cause.message); setStale(true); setLoading(false); },
      onPending(action, payload) {
        setBusy(action);
        if (action) { setPendingEnabled(payload.enabled === true); setActionError(''); setNotice(''); }
      },
      onActionError(cause) { setActionError(cause.message); setStale(true); },
      beforeAction: () => document.hidden ? Promise.resolve() : new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0))),
    });
    flow.current = requests;
    polling = createStatusPolling({
      read: requests.read, delay: () => pollDelay(lastStatus.current, failures.current),
      visible: () => !document.hidden, available: () => client.available,
      expire: () => setStale(true),
    });
    wake.current = () => polling.wake(2000);
    const onVisible = () => {
      if (!document.hidden) polling.wake();
      else { polling.suspend(); setNetworkCode(''); setJoinCode(''); }
    };
    // Missing native bridge remains an explicit error instead of an endless loading page.
    if (client.available) polling.start(); else void requests.read();
    document.addEventListener('visibilitychange', onVisible); window.addEventListener('focus', onVisible); window.addEventListener('pageshow', onVisible);
    return () => { polling.stop(); requests.dispose(); document.removeEventListener('visibilitychange', onVisible); window.removeEventListener('focus', onVisible); window.removeEventListener('pageshow', onVisible); };
  }, []);
  useEffect(() => { if (!relayEdited) setRelay(status?.settings?.relay || ''); }, [status?.settings?.relay, relayEdited]);
  useEffect(() => { if (page !== 'connections') { setNetworkCode(''); setJoinCode(''); setConfirmLeave(false); } }, [page]);
  useLayoutEffect(() => { window.scrollTo({ top: 0, left: 0, behavior: 'instant' }); }, [page]);

  const run = async (action, payload = {}, success = '') => {
    const result = await flow.current?.run(action, payload);
    if (result) setNotice(success);
    wake.current();
    return result;
  };
  const unavailable = !client.available;
  const disabled = !!busy || !status || stale || unavailable;
  const s = status;
  const sharing = s?.sharing || {};
  const debug = s?.debug || {};
  const usbMode = debug.mode === 'usb-adb-experimental';
  const mesh = s?.mesh || {};
  const sessions = s?.sessions || [];
  const showGuide = () => { setGuideOpen(true); setPage('connections'); };
  const busyMessage = busy === 'set-sharing' ? pendingEnabled ? '正在开启共享…' : '正在关闭共享并恢复调试配置…' : ({ 'create-mesh': '正在创建网络…', 'join-mesh': '正在加入网络…', 'leave-mesh': '正在退出网络…', 'pairing-code': '正在读取网络配对码…', 'open-debug-settings': '正在打开系统设置…', 'set-relay': '正在保存中继地址…', disconnect: '正在断开会话…' }[busy] || '');

  return <div className="app-shell">
    <header className="topbar"><div className="brand"><span className="brand-mark"><UsbPlug24Regular /></span><span>USBLink<small>手机端</small></span></div><div className="topbar-status"><SyncStatus observed={observed} stale={stale} busy={busy} unavailable={unavailable} /><button className={`icon-button refresh ${loading || refreshing ? 'working' : ''}`} onClick={refresh} disabled={!!busy || loading || refreshing} aria-label="立即刷新状态" title="状态自动同步，也可立即刷新"><ArrowSync20Regular /></button></div></header>
    {client.preview && <div className="preview-banner"><Info20Regular /><span>交互预览 · 示例数据，不会操作手机</span></div>}
    <main>
      <div className="page-heading"><div><p className="eyebrow">{page === 'devices' ? 'YOUR DEVICE' : page === 'connections' ? 'STAY CONNECTED' : 'PREFERENCES'}</p><h1>{pages.find(item => item.id === page).label}</h1></div><span className="module-label">APatch / KSU</span></div>
      {error && <div className="message error" role="alert"><Info20Regular /><div><strong>{unavailable ? '请在模块管理器中打开' : '暂时无法确认服务状态'}</strong><p>{error}</p>{!unavailable && <button className="text-button" disabled={!!busy} onClick={refresh}>重新读取状态</button>}</div></div>}
      {actionError && <div className="message error" role="alert"><Info20Regular /><div><strong>操作未完成</strong><p>{actionError}</p></div><button className="icon-button" aria-label="关闭操作错误" onClick={() => setActionError('')}><Dismiss20Regular /></button></div>}
      {busyMessage && <div className="operation-status" role="status"><span className="progress-ring" />{busyMessage}<span>可以切换页面</span></div>}
      {notice && <div className="message notice" role="status"><Checkmark20Regular /><span>{notice}</span><button className="icon-button" aria-label="关闭提示" onClick={() => setNotice('')}><Dismiss20Regular /></button></div>}
      {loading && !status ? <div className="loading-state"><ArrowSync20Regular /><p>正在读取手机服务…</p></div> : <>
        {page === 'devices' && <>
          <section className={`sharing-panel ${sharing.active && !stale ? 'is-active' : ''}`} aria-label="共享控制"><div className="sharing-top"><span className="hero-icon"><Phone24Regular /></span><Badge active={sharing.active && !stale} warning={stale || sharing.state === 'error'}>{stale ? '状态未确认' : statusLabels[sharing.state] || '服务不可用'}</Badge></div><h2>{s?.device?.model || '本机设备'}</h2><p className="device-meta">{s ? `Android ${s.device?.androidVersion || '—'}${s.device?.modelCode && s.device.modelCode !== s.device.model ? ` · ${s.device.modelCode}` : ''}` : '等待连接手机后台服务'}</p><div className="sharing-control"><div><strong>{usbMode ? '共享这台手机' : '共享本机调试'}</strong><p>{busy === 'set-sharing' ? busyMessage : sharing.enabled ? '已允许 · 实际连接状态见上方' : '开启后，已授权电脑可连接调试'}</p></div><button className="switch" role="switch" aria-checked={!!sharing.enabled} aria-busy={busy === 'set-sharing'} aria-label="共享本机调试" disabled={disabled} onClick={() => run('set-sharing', { enabled: !sharing.enabled }, sharing.enabled ? '共享已关闭，现有会话已断开。' : '已保存开启状态，请查看下方准备情况。')}><span /></button></div></section>
          <button className="session-shortcut" onClick={() => setPage('connections')}><Desktop24Regular /><span>{stale ? '连接状态待同步' : sessions.length ? `${sessions.length} 台电脑已连接` : sharing.active ? '等待电脑连接' : sharing.state === 'error' ? '共享已暂停，请检查提示' : sharing.enabled ? '共享准备中' : '远程连接已关闭'}</span><ChevronRight20Regular /></button>
          <p className="quiet-note"><ShieldCheckmark24Regular /><span>开关状态自动保存，无需重启手机。切换模式会重启系统 adbd，现有调试连接可能短暂断开。</span></p>
          {sharing.problem && <div className={`message ${sharing.state === 'error' ? 'error' : 'notice'}`} role="status"><Info20Regular /><div><p>{sharing.problem}</p>{sharing.state === 'error' && <button className="text-button" disabled={!!busy || unavailable} onClick={() => run('set-sharing', { enabled: false }, '共享已关闭，调试配置已恢复。')}>关闭共享并重试恢复</button>}</div></div>}
          <SectionTitle detail={usbMode ? 'USB ADB · 实验版' : undefined}>连接准备</SectionTitle><section className="group">
            <Row icon={Link24Regular} title="设备网络" detail={stale ? '状态待同步' : mesh.configured ? mesh.running ? mesh.localIp || '正在分配地址' : mesh.problem || '正在建立加密连接' : '先与电脑加入同一个 USBLink 网络'}><button className="row-link" onClick={() => setPage('connections')} aria-label="设置设备网络">{stale ? '查看' : mesh.running ? '已连接' : '设置'}<ChevronRight20Regular /></button></Row>
            <Row icon={Desktop24Regular} title="电脑发现" detail={stale ? '状态待同步' : s?.appSession?.problem || (s?.appSession?.ready ? `已就绪 · 电脑端 ${usbMode ? '0.3.11' : '0.3.10'} 或以上` : '加入设备网络后，电脑可发现本机')} />
            <Row icon={Settings24Regular} title="系统 USB 调试" detail={stale ? '状态待同步' : debug.enabled ? '调试服务已就绪 · 首次连接需在手机授权' : ['waiting_system', 'starting'].includes(sharing.state) ? '系统调试服务正在准备，请稍候' : '请开启 USB 调试，不需要插线，也不用开启无线调试'}><button className="row-link" disabled={disabled} onClick={() => run('open-debug-settings', {}, client.preview ? '预览不会打开系统设置。' : '')}>打开<ChevronRight20Regular /></button></Row>
          </section>
          <SectionTitle detail="首次连接需要授权">电脑连接</SectionTitle><section className="instruction-panel"><div className="instruction-title"><Desktop24Regular /><strong>让电脑直接调试这部手机</strong></div><p>{usbMode ? '电脑点击连接，现有 ADB 通过 USB 接口发现手机。仅含 ADB，不含 MTP 文件传输。' : '安装 APK、运行 Android Studio，使用电脑现有的 ADB。'}</p><button className="primary-button full" onClick={showGuide}>查看连接步骤<ChevronRight20Regular /></button></section>
          <p className="quiet-note feature-note"><Info20Regular /><span>当前仅共享本机 ADB，暂不支持 MTP、U 盘和外接手机。</span></p>
        </>}
        {page === 'connections' && <>
          <SectionTitle detail={mesh.configured ? '已保存网络配置' : '第一步'}>设备网络</SectionTitle>
          {mesh.configured ? <section className="group network-panel"><Row icon={Link24Regular} title={mesh.networkName || '我的设备网络'} detail={mesh.localIp || mesh.problem || '等待网络地址'}><Badge active={mesh.running && !stale}>{stale ? '待同步' : mesh.running ? '已连接' : '未就绪'}</Badge></Row><div className="panel-body"><p className="muted">电脑需加入同一个 USBLink 网络，才能访问手机的调试地址。</p><div className="button-row"><button className="secondary-button" disabled={disabled} onClick={async () => { if (networkCode) { setNetworkCode(''); return; } const result = await run('pairing-code'); if (result?.code && pageRef.current === 'connections' && !document.hidden) setNetworkCode(result.code); }}>{networkCode ? '隐藏网络配对码' : '显示网络配对码'}</button><button className="text-button danger" disabled={disabled} onClick={() => setConfirmLeave(true)}>退出网络</button></div>{networkCode && <><CopyField label="网络配对码 · 仅分享给自己的电脑" value={networkCode} secret /><p className="quiet-note">此码只用于加入设备网络；首次调试仍需在手机确认电脑 RSA 授权。</p></>}{confirmLeave && <div className="confirm-box"><p>退出后会断开当前远程会话，并清除本机的网络配置。</p><div className="button-row"><button className="secondary-button" disabled={!!busy} onClick={() => setConfirmLeave(false)}>取消</button><button className="danger-button" disabled={disabled} onClick={async () => { const result = await run('leave-mesh', {}, '已退出设备网络。'); if (result) { setNetworkCode(''); setConfirmLeave(false); } }}>确认退出</button></div></div>}</div></section> : <section className="setup-panel"><h3>把手机和电脑连接起来</h3><p>创建一个网络，让电脑加入；已有电脑网络时，直接输入它的配对码。</p><button className="primary-button full" disabled={disabled} onClick={() => run('create-mesh', {}, '网络已创建，可以向电脑提供网络配对码。')}>{busy === 'create-mesh' ? '正在创建…' : '创建我的网络'}</button><div className="or-divider"><span>或加入已有网络</span></div><form onSubmit={async e => { e.preventDefault(); if (!joinCode.trim()) return; const result = await run('join-mesh', { code: joinCode.trim() }, '已保存网络配置，正在连接。'); if (result) setJoinCode(''); }}><label htmlFor="network-code">电脑上的 USBLink 网络配对码</label><textarea id="network-code" value={joinCode} onChange={e => setJoinCode(e.target.value)} placeholder="粘贴网络配对码" rows="2" maxLength="7000" autoComplete="off" autoCapitalize="off" spellCheck="false" disabled={disabled} /><button className="secondary-button full" type="submit" disabled={disabled || !joinCode.trim()}>{busy === 'join-mesh' ? '正在加入…' : '加入网络'}</button></form></section>}
          {mesh.problem && <div className="message error"><Info20Regular /><p>{mesh.problem}</p></div>}
          <SectionTitle detail={stale ? '上次记录 · 待同步' : `${sessions.length} 个会话`}>当前连接</SectionTitle><section className="group">{s?.sessions?.length ? s.sessions.map(session => <Row key={session.id} icon={Desktop24Regular} title={mesh.peers?.find(peer => peer.ip === session.peer)?.name || session.peer || '远程电脑'} detail={stale ? '上次连接记录 · 等待状态同步' : usbMode ? 'USB ADB 挂载 · 调试权限由系统授权决定' : 'ADB 传输会话 · 不代表已获授权'}><button className="text-button danger" disabled={disabled} onClick={() => run('disconnect', { id: session.id }, '已断开指定会话。')}>断开</button></Row>) : <div className="empty-state"><Desktop24Regular /><strong>暂无远程会话</strong><p>成功连接后，电脑会显示在这里。</p></div>}</section>
          <details className="connection-help" open={guideOpen} onToggle={event => setGuideOpen(event.currentTarget.open)}><summary><Info20Regular />首次连接指南<ChevronRight20Regular /></summary><section className="steps-panel"><ol className="steps">
            <li><span className="step-number">1</span><div><strong>开启系统 USB 调试</strong><p>在开发者选项打开「USB 调试」，用于系统电脑授权。不需要插 USB 线，也不用开启无线调试。</p><button className="text-button" disabled={disabled} onClick={() => run('open-debug-settings', {}, client.preview ? '预览不会打开系统设置。' : '')}>打开开发者设置<ChevronRight20Regular /></button></div></li>
            <li><span className="step-number">2</span><div><strong>在设备页开启共享</strong><p>网络就绪后，模块会限制端口访问并启动系统 TCP 调试。切换时已有 ADB 连接可能短暂断开。</p><button className="secondary-button" onClick={() => setPage('devices')}>前往共享开关</button></div></li>
            <li><span className="step-number">3</span><div><strong>电脑连接，手机确认授权</strong>{usbMode ? <><p>电脑更新到 USBLink 0.3.11，在「连接」页选择本机，点击「连接设备」。解锁手机，核对并允许 RSA 调试授权。无需执行 adb connect。</p><p className="inline-hint">设备管理器显示 Android ADB Interface；MTP 便携设备尚未实现。掉线后需手动重新连接。</p></> : <><p>使用电脑现有 ADB 执行下方命令，首次连接需在手机允许 RSA 调试授权。</p>{debug.connectAddress && sharing.active && !stale ? <CopyField label="电脑连接命令" value={`adb connect ${debug.connectAddress}`} /> : <p className="inline-hint">共享就绪后显示连接命令。</p>}</>}</div></li>
          </ol><p className="quiet-note">连接并完成授权后，在电脑现有 ADB 或 Android Studio 中查看设备。网络中断后需由电脑手动重新连接。</p></section></details>

          {(mesh.peers || []).length > 0 && <><SectionTitle>同一网络的设备</SectionTitle><section className="group">{mesh.peers.map((peer, index) => <Row key={peer.ip || index} icon={Desktop24Regular} title={peer.name || '网络设备'} detail={`${peer.ip || '地址未知'}${peer.latency != null ? ` · ${peer.latency} ms` : ''}`} />)}</section><p className="quiet-note">网络可达不代表对方已经连接或获得 ADB 授权。</p></>}
        </>}
        {page === 'settings' && <>
          <SectionTitle>服务与设备</SectionTitle><section className="group"><Row icon={Phone24Regular} title={s?.device?.model || '等待手机信息'} detail={s ? `${s.device?.modelCode || 'Android'} · Android ${s.device?.androidVersion || '—'}` : '请从模块管理器打开'} /><Row icon={ShieldCheckmark24Regular} title="后台服务" detail="DEX JAR · app_process"><Badge active={!!s && !stale}>{stale ? '状态未知' : s ? '可响应' : '未连接'}</Badge></Row><Row icon={UsbPlug24Regular} title="USBLink 手机版" detail={`版本 ${s?.version || '—'} · APatch / KernelSU 模块`} /></section>
          <SectionTitle>网络中继</SectionTitle><section className="instruction-panel"><p className="muted">用于建立异地设备网络。修改后会更新网络连接，现有远程会话可能中断。</p><form onSubmit={async e => { e.preventDefault(); const value = relay.trim(); if (!/^(tcp|udp):\/\/[^\s/:]+:\d{1,5}$/.test(value)) { setNotice('请输入 tcp://地址:端口 或 udp://地址:端口。'); return; } const result = await run('set-relay', { relay: value }, '中继设置已保存。'); if (result) setRelayEdited(false); }}><label htmlFor="relay">中继地址</label><input id="relay" type="text" inputMode="url" value={relay} autoCapitalize="off" autoComplete="off" spellCheck="false" disabled={disabled} onChange={e => { setRelay(e.target.value); setRelayEdited(true); }} /><button className="secondary-button full" disabled={disabled || !relayEdited || !relay.trim()}>{busy === 'set-relay' ? '正在保存…' : '保存中继地址'}</button></form></section>
          <SectionTitle>共享行为</SectionTitle><section className="group"><Row icon={ArrowSync20Regular} title="自动同步状态" detail="共享时每 2 秒、关闭时每 5 秒静默更新；返回页面立即同步。" /><Row icon={Checkmark20Regular} title="记住共享开关" detail="状态保存在手机后台；关闭后不会被守护程序自动打开。" /><Row icon={ShieldCheckmark24Regular} title="系统授权决定调试权限" detail="网络配对不代替 Android 的 RSA 调试授权。请只向自己的电脑提供配对信息。" /></section><div className="about-note"><span className="brand-mark small"><UsbPlug24Regular /></span><strong>USBLink</strong><p>让手机和电脑，直接连接。</p><small>{usbMode ? 'USB ADB 实验版：只验证了 K40 / Android 12，暂不支持 MTP、Fastboot 或外接 USB。' : '本版本共享手机系统 adbd，电脑使用现有 ADB。'}关闭共享会撤销远程连接，并恢复原有调试端口配置。</small></div>
        </>}
      </>}
      <div className="page-bottom" />
    </main>
    <nav className="bottom-nav" aria-label="主导航">{pages.map(({ id, label, icon: Icon }) => <button key={id} className={page === id ? 'selected' : ''} onClick={() => setPage(id)} aria-current={page === id ? 'page' : undefined}><span><Icon /></span>{label}</button>)}</nav>
  </div>;
}

const root = import.meta.hot?.data.root ?? createRoot(document.getElementById('root'));
if (import.meta.hot) import.meta.hot.data.root = root;
root.render(<App />);

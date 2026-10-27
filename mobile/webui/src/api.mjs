const ACTIONS = new Set(['status', 'set-sharing', 'create-mesh', 'join-mesh', 'leave-mesh', 'pairing-code', 'open-debug-settings', 'set-relay', 'disconnect', 'history', 'clear-history', 'set-adb-root']);
let counter = 0;

export function makeCommand(action, payload = {}) {
  if (!ACTIONS.has(action)) throw new Error('不支持的操作');
  const data = JSON.stringify(payload);
  if (data.length > 8192) throw new Error('输入内容过长');
  const bytes = new TextEncoder().encode(data);
  const encoded = btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''));
  // Only the allow-listed action and base64 alphabet enter the shell command.
  return `/data/adb/modules/usblink-mobile/bin/usblinkctl ${action} '${encoded}'`;
}

export function hasBridge(scope = globalThis) {
  return typeof scope.ksu?.exec === 'function';
}

export function callNative(action, payload = {}, scope = globalThis, timeoutMs = action === 'status' ? 6000 : 30000) {
  if (!hasBridge(scope)) return Promise.reject(new Error('请从 APatch／KernelSU 的模块 WebUI 打开；Magisk 请使用已获 Root 授权的 KsuWebUI。普通浏览器无法控制手机服务。'));
  return new Promise((resolve, reject) => {
    const callbackName = `__usblink_${Date.now()}_${++counter}`;
    let settled = false;
    const timer = setTimeout(() => {
      settled = true;
      // A late native callback is harmless. A command timeout does not prove it failed.
      scope[callbackName] = () => { delete scope[callbackName]; };
      reject(new Error(action === 'status' ? '状态读取超时，正在自动重新同步。' : '服务响应超时，操作结果尚未确认。请等待状态同步后检查。'));
    }, timeoutMs);
    scope[callbackName] = (errno, stdout, stderr) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      delete scope[callbackName];
      try {
        let result;
        try { result = JSON.parse(String(stdout || '').trim()); }
        catch { throw new Error(Number(errno) !== 0 ? '后台服务未能执行操作，请确认模块已启用、WebUI 宿主已获 Root 授权。Magisk 用户请检查 KsuWebUI 的授权。' : '后台返回了无法识别的结果，请检查模块版本。'); }
        if (Number(errno) !== 0 || result?.ok !== true) throw new Error(typeof result?.error === 'string' ? result.error : '操作失败，请刷新后重试。');
        resolve(result.data);
      } catch (error) { reject(error); }
    };
    try { scope.ksu.exec(makeCommand(action, payload), '{}', callbackName); }
    catch (error) { clearTimeout(timer); delete scope[callbackName]; settled = true; reject(new Error(`无法调用模块服务：${error.message}`)); }
  });
}

function previewStatus(usb = false) {
  return {
    version: usb ? '0.3.12-experimental-preview' : '0.2.8-preview', device: { model: 'Redmi K40', name: 'Redmi K40', modelCode: 'M2012K11AC', androidVersion: '12', sdk: 31, root: true },
    adbRoot: { enabled: true, active: true, supported: true, state: 'root', problem: '' },
    sharing: { enabled: false, active: false, state: 'off', problem: '' },
    debug: { mode: usb ? 'usb-adb-experimental' : 'system-tcp', enabled: false, connectAddress: '' },
    appSession: { ready: false, problem: '' },
    mesh: { configured: false, running: false, paused: true, localIp: '', networkName: '', peers: [], problem: '' },
    sessions: [], settings: { relay: 'tcp://183.230.36.171:11010' }, features: { externalUsb: false },
    history: { entries: [], count: 0, epoch: 'preview', hasMore: false, limit: 200, problem: '' },
  };
}

export function createClient({ preview = false, scope = globalThis, usbPreview = false, disconnectPreview = false, historyPreview = false, rootPreview = '', networkPreview = '' } = {}) {
  let demo = previewStatus(usbPreview);
  if (preview && rootPreview === 'unsupported') Object.assign(demo.adbRoot, { active: false, supported: false, state: 'unsupported', problem: '当前 ROM 的 adbd 不支持 Root；手机有 Root 权限不代表 adbd 支持。' });
  let history = preview && historyPreview ? Array.from({ length: 25 }, (_, index) => ({ id: 25 - index, at: Date.now() - index * 60000, peer: '10.126.126.2', kind: ['shell', 'app', 'push', 'sync'][index % 4], command: ['shell,v2,raw:getprop ro.product.model', 'abb_exec:package install-create -S 57006306', 'SEND /data/local/tmp/app-release.apk', 'sync:'][index % 4] })) : [];
  const historyPage = before => { const entries = history.filter(item => !before || item.id < before).slice(0, 20); return { ...demo.history, entries, count: history.length, hasMore: !!entries.length && entries.at(-1).id > history.at(-1).id }; };
  if (preview && disconnectPreview) demo.lastDisconnect = { at: Date.now(), code: 'ADB_CLOSED', message: '手机系统 adbd 已结束调试连接', intentional: false, bytes: 67108864 };
  const syncPreview = () => {
    Object.assign(demo.mesh, { running: demo.sharing.enabled && demo.mesh.configured, paused: !demo.sharing.enabled,
      localIp: demo.sharing.enabled && demo.mesh.configured ? '10.126.126.1' : '', peers: [] });
    const active = demo.sharing.enabled && demo.mesh.running;
    Object.assign(demo.sharing, { active, state: !demo.sharing.enabled ? 'off' : !demo.mesh.running ? 'waiting_network' : 'sharing' });
    demo.debug.enabled = active;
    demo.appSession.ready = demo.mesh.running;
    demo.debug.connectAddress = active ? `${demo.mesh.localIp}:${usbPreview ? 3240 : 3242}` : '';
    demo.mesh.statusDegraded = active && networkPreview === 'degraded';
    demo.mesh.problem = demo.mesh.statusDegraded ? '网络状态查询暂不可用；本机接口与路由正常，共享保持运行' : '';
    if (demo.mesh.statusDegraded) demo.mesh.running = false;
  };
  return {
    preview,
    available: preview || hasBridge(scope),
    async request(action, payload = {}) {
      if (!preview) return callNative(action, payload, scope);
      makeCommand(action, payload);
      await new Promise(resolve => setTimeout(resolve, 250));
      if (action === 'set-sharing') {
        demo.sharing.enabled = payload.enabled === true;
        if (!demo.sharing.enabled) demo.sessions = [];
      }
      if (action === 'set-adb-root') {
        if (typeof payload.enabled !== 'boolean') throw new Error('ADB Root 开关值无效');
        Object.assign(demo.adbRoot, { enabled: payload.enabled, active: payload.enabled && demo.adbRoot.supported, state: payload.enabled ? demo.adbRoot.supported ? 'root' : 'unsupported' : 'shell', problem: payload.enabled && !demo.adbRoot.supported ? '当前 ROM 的 adbd 不支持 Root；保持原有调试权限。' : '' });
        demo.sessions = [];
      }
      if (action === 'create-mesh' || action === 'join-mesh') Object.assign(demo.mesh, { configured: true, networkName: '我的设备网络', peers: [] });
      if (action === 'leave-mesh') {
        Object.assign(demo.mesh, { configured: false, running: false, localIp: '', networkName: '', peers: [] });
        demo.sessions = [];
        demo.sharing.enabled = false;
      }
      if (action === 'pairing-code') return { code: 'USBLink-DEMO-ONLY-NOT-A-REAL-PAIRING-CODE' };
      if (action === 'set-relay') demo.settings.relay = payload.relay;
      if (action === 'disconnect') demo.sessions = demo.sessions.filter(session => session.id !== payload.id);
      if (action === 'history') { if (payload.epoch !== demo.history.epoch || !(payload.before > 0)) throw new Error('历史记录已更新，请返回最新记录'); return structuredClone(historyPage(payload.before)); }
      if (action === 'clear-history') { history = []; demo.history.epoch += '-cleared'; }
      demo.history = historyPage(0);
      syncPreview();
      return structuredClone(demo);
    },
  };
}

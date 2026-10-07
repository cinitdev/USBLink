const ACTIONS = new Set(['status', 'set-sharing', 'create-mesh', 'join-mesh', 'leave-mesh', 'pairing-code', 'open-debug-settings', 'set-relay', 'disconnect']);
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
  if (!hasBridge(scope)) return Promise.reject(new Error('请从 APatch 或 KernelSU 管理器的 USBLink 模块页面打开，普通浏览器无法控制手机服务。'));
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
        catch { throw new Error(Number(errno) !== 0 ? '后台服务未能执行操作，请检查模块是否已启用。' : '后台返回了无法识别的结果，请检查模块版本。'); }
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
    version: usb ? '0.3.3-experimental-preview' : '0.2.2-preview', device: { model: 'Redmi K40', name: 'Redmi K40', modelCode: 'M2012K11AC', androidVersion: '12', sdk: 31, root: true },
    sharing: { enabled: false, active: false, state: 'off', problem: '' },
    debug: { mode: usb ? 'usb-adb-experimental' : 'system-tcp', enabled: false, connectAddress: '' },
    appSession: { ready: false, problem: '' },
    mesh: { configured: false, running: false, localIp: '', networkName: '', peers: [], problem: '' },
    sessions: [], settings: { relay: 'tcp://183.230.36.171:11010' }, features: { externalUsb: false },
  };
}

export function createClient({ preview = false, scope = globalThis, usbPreview = false } = {}) {
  let demo = previewStatus(usbPreview);
  const syncPreview = () => {
    const active = demo.sharing.enabled && demo.mesh.running;
    Object.assign(demo.sharing, { active, state: !demo.sharing.enabled ? 'off' : !demo.mesh.running ? 'waiting_network' : 'sharing' });
    demo.debug.enabled = active;
    demo.appSession.ready = demo.mesh.running;
    demo.debug.connectAddress = active ? `${demo.mesh.localIp}:${usbPreview ? 3240 : 3242}` : '';
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
      if (action === 'create-mesh' || action === 'join-mesh') Object.assign(demo.mesh, { configured: true, running: true, localIp: '10.126.126.1', networkName: '我的设备网络', peers: [] });
      if (action === 'leave-mesh') {
        Object.assign(demo.mesh, { configured: false, running: false, localIp: '', networkName: '', peers: [] });
        demo.sessions = [];
        demo.sharing.enabled = false;
      }
      if (action === 'pairing-code') return { code: 'USBLink-DEMO-ONLY-NOT-A-REAL-PAIRING-CODE' };
      if (action === 'set-relay') demo.settings.relay = payload.relay;
      if (action === 'disconnect') demo.sessions = demo.sessions.filter(session => session.id !== payload.id);
      syncPreview();
      return structuredClone(demo);
    },
  };
}

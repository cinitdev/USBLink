import { HISTORY_PAGE_SIZE, historyMatches } from './history-model.mjs';
const ACTIONS = new Set(['status', 'environment', 'set-sharing', 'create-mesh', 'join-mesh', 'leave-mesh', 'pairing-code', 'open-debug-settings', 'set-relay', 'disconnect', 'history', 'clear-history']);
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
  return typeof scope.ksu?.spawn === 'function' || typeof scope.ksu?.exec === 'function';
}

// Native spawn emits one stdout event per line. Keep collection bounded even
// if a broken host/script produces unexpected output. Control replies are <=64 KiB.
const MAX_NATIVE_OUTPUT = 128 * 1024;
const nativeFailure = '后台服务未能执行操作，请确认模块已启用、WebUI 宿主已获 Root 授权。Magisk 用户请检查 KsuWebUI 的授权。';
function retireSpawn(scope, name) {
  // Some hosts emit error AFTER a nonzero exit. Timeouts can also receive late
  // output; keep only an inert, short-lived receiver, never the response data.
  const remove = () => { clearTimeout(timer); delete scope[name]; };
  const timer = setTimeout(remove, 60000);
  timer.unref?.();
  scope[name] = {
    stdout: { emit() {} }, stderr: { emit() {} },
    emit(event, code) { if (event === 'error' || event === 'exit' && Number(code) === 0) remove(); },
  };
}

export function callNative(action, payload = {}, scope = globalThis, timeoutMs = ['status', 'environment'].includes(action) ? 6000 : 30000) {
  if (!hasBridge(scope)) return Promise.reject(new Error('请从 APatch／KernelSU 的模块 WebUI 打开；Magisk 请使用已获 Root 授权的 KsuWebUI。普通浏览器无法控制手机服务。'));
  return new Promise((resolve, reject) => {
    const command = makeCommand(action, payload);
    const useSpawn = typeof scope.ksu.spawn === 'function';
    const callbackName = `__usblink_${Date.now()}_${++counter}`;
    let settled = false;
    let lines = [], size = 0;
    const cleanup = late => {
      settled = true; clearTimeout(timer); lines = [];
      if (useSpawn && late) retireSpawn(scope, callbackName);
      else delete scope[callbackName];
    };
    const timer = setTimeout(() => {
      cleanup(true);
      // A late native callback is harmless. A command timeout does not prove it failed.
      if (!useSpawn) scope[callbackName] = () => { delete scope[callbackName]; };
      reject(new Error(action === 'environment' ? '调试与权限检测超时，请稍后重新检测。' : action === 'status' ? '状态读取超时，正在自动重新同步。' : '服务响应超时，操作结果尚未确认。请等待状态同步后检查。'));
    }, timeoutMs);
    const complete = (errno, stdout) => {
      if (settled) return;
      cleanup(Number(errno) !== 0);
      try {
        if (String(stdout || '').length > MAX_NATIVE_OUTPUT) throw new Error('后台返回内容过长，操作结果尚未确认。请等待状态同步后检查。');
        let result;
        try { result = JSON.parse(String(stdout || '').trim()); }
        catch { throw new Error(Number(errno) !== 0 ? nativeFailure : '后台返回了无法识别的结果，请检查模块版本。'); }
        if (Number(errno) !== 0 || result?.ok !== true) throw new Error(typeof result?.error === 'string' ? result.error : '操作失败，请刷新后重试。');
        resolve(result.data);
      } catch (error) { reject(error); }
    };
    if (useSpawn) {
      scope[callbackName] = {
        stdout: { emit(event, line) {
          if (settled || event !== 'data') return;
          const value = String(line);
          size += value.length + 1;
          if (size > MAX_NATIVE_OUTPUT) {
            cleanup(true);
            reject(new Error('后台返回内容过长，操作结果尚未确认。请等待状态同步后检查。'));
          } else lines.push(value);
        } },
        stderr: { emit() {} }, // Never retain/display raw shell stderr or credentials.
        emit(event, value) {
          if (settled) return;
          if (event === 'exit') complete(value, lines.join('\n'));
          else if (event === 'error') { cleanup(true); reject(new Error(nativeFailure)); }
        },
      };
    } else scope[callbackName] = complete;
    try {
      // Callback-style exec still blocks JS until the shell finishes on current
      // APatch/KSU/KsuWebUI hosts. spawn enqueues the command instead.
      if (useSpawn) scope.ksu.spawn(command, '[]', '{}', callbackName);
      else scope.ksu.exec(command, '{}', callbackName); // Older exec-only hosts.
    } catch {
      if (!settled) { cleanup(useSpawn); reject(new Error('无法调用模块服务，请检查 WebUI 宿主版本与 Root 授权；操作未自动重试。')); }
    }
  });
}

export function validateEnvironment(value) {
  if (!value || ![true, false, null].includes(value.usbDebugging) || !(value.shellUid === null || Number.isSafeInteger(value.shellUid) && value.shellUid >= 0)) throw new Error('检测结果无法确认，请更新模块后重新检测。');
  return { usbDebugging: value.usbDebugging, shellUid: value.shellUid };
}

function previewStatus(usb = false) {
  return {
    version: usb ? '0.3.25-preview' : '0.2.15-preview', device: { model: 'Redmi K40', name: 'Redmi K40', modelCode: 'M2012K11AC', androidVersion: '12', sdk: 31, root: true },
    sharing: { enabled: false, active: false, state: 'off', problem: '' },
    debug: { mode: usb ? 'usb-adb-experimental' : 'system-tcp', enabled: false, connectAddress: '' },
    appSession: { ready: false, problem: '' },
    mesh: { configured: false, running: false, paused: true, localIp: '', networkName: '', peers: [], problem: '' },
    sessions: [], settings: { relay: 'tcp://183.230.36.171:11010' }, features: { externalUsb: false },
    history: { entries: [], count: 0, epoch: 'preview', revision: 1, hasMore: false, limit: 1000, problem: '' },
  };
}

export function createClient({ preview = false, scope = globalThis, usbPreview = false, disconnectPreview = false, historyPreview = false, debugPreview = '', networkPreview = '' } = {}) {
  let demo = previewStatus(usbPreview);
  const samples = [
    ['app', 'app', 'install-commit', 'abb_exec:package install-commit 128'],
    ['exec', 'app', 'install-write', 'exec:cmd package install-write -S 57006306 128 base.apk -'],
    ['app', 'app', 'install-create', 'abb_exec:package install-create -r -S 57006306'],
    ['shell', 'app', 'uninstall', 'shell,v2,raw:pm uninstall com.example.demo'],
    ['shell', 'command', 'shell', 'shell,v2,raw:echo hello'],
    ['push', 'file', 'push', 'SEND /data/local/tmp/app-release.apk'],
    ['shell', 'debug', 'logcat', 'shell,v2,raw:logcat -v threadtime'],
    ['service', 'device', 'reboot', 'reboot:bootloader'],
    ['service', 'network', 'transport', 'reverse:forward:tcp:8080;tcp:8080'],
    ['service', 'other', 'service', 'unknown-service:example'],
    ['shell', 'app', 'install', 'shell:pm install /data/local/tmp/older-release.apk'],
    ['shell', 'device', 'system-command', 'shell,v2,raw:getprop ro.product.model'],
  ];
  let history = preview && historyPreview ? Array.from({ length: 125 }, (_, index) => {
    const [kind, category, action, command] = samples[index % samples.length];
    const response = { state: 'closed', stdout: '', stderr: '', exitCode: null, protocolStatus: '', limit: 4096 };
    if (action === 'install-commit' || action === 'install') response.stdout = 'Success\n';
    else if (action === 'install-write') response.stdout = 'Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]\n';
    else if (action === 'install-create') response.stdout = 'Success: created install session [128]\n';
    else if (action === 'shell' || action === 'uninstall' || action === 'system-command') Object.assign(response, { state: 'completed', exitCode: 0, stdout: action === 'shell' ? 'hello\n' : action === 'uninstall' ? 'Success\n' : 'Redmi K40\n' });
    else if (action === 'push') Object.assign(response, { state: 'completed', protocolStatus: 'OKAY' });
    else if (action === 'logcat') Object.assign(response, { state: 'pending', stdout: '11-08 10:20:31.081 I ActivityManager: Start proc com.example.demo\n'.repeat(60), truncated: true });
    else Object.assign(response, { state: 'unavailable', note: '此服务返回协议未解析' });
    return { id: 125 - index, at: Date.now() - index * 60000, peer: '10.126.126.2', kind, category, action, command, response };
  }) : [];
  const historyPage = (before, filter = 'all', query = '') => {
    const matching = history.filter(item => historyMatches(item, filter, query));
    const remaining = matching.filter(item => !before || item.id < before);
    const entries = []; let bytes = 0;
    for (const item of remaining) {
      const size = new TextEncoder().encode(JSON.stringify(item)).length + 1;
      if (entries.length >= HISTORY_PAGE_SIZE || bytes + size > 40000) break;
      entries.push(item); bytes += size;
    }
    return { ...demo.history, entries, count: history.length, matchedCount: matching.length, filter, query, hasMore: remaining.length > entries.length };
  };
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
      if (!preview) {
        const result = await callNative(action, payload, scope);
        return action === 'environment' ? validateEnvironment(result) : result;
      }
      makeCommand(action, payload);
      await new Promise(resolve => setTimeout(resolve, 250));
      if (action === 'environment') return { usbDebugging: debugPreview === 'off' ? false : debugPreview === 'unknown' ? null : true, shellUid: 0 };
      if (action === 'set-sharing') {
        if (payload.enabled && ['off', 'unknown'].includes(debugPreview)) throw new Error('请先确认 USB 调试已开启');
        demo.sharing.enabled = payload.enabled === true;
        if (!demo.sharing.enabled) demo.sessions = [];
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
      if (action === 'history') { if (payload.epoch !== demo.history.epoch || !(payload.before >= 0)) throw new Error('历史记录已更新，请返回最新记录'); return structuredClone(historyPage(payload.before, payload.filter || 'all', (payload.query || '').trim())); }
      if (action === 'clear-history') { history = []; demo.history.epoch += '-cleared'; }
      demo.history = historyPage(0);
      syncPreview();
      return structuredClone(demo);
    },
  };
}

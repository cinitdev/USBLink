// Real App + native bridge adapter, with controlled local replies only.
// Kept outside the production entry graph; never executes a phone command.
import { act } from 'react';
import { createClient } from '../src/api.mjs';
import { readHistoryPage } from '../src/history-model.mjs';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
Object.defineProperty(document, 'hidden', { configurable: true, value: false });
const result = document.getElementById('result');
const root = document.getElementById('root');
const check = (value, message) => { if (!value) throw new Error(message); };
const tick = () => new Promise(resolve => setTimeout(resolve, 0));
async function until(predicate, message) {
  for (let i = 0; i < 80; i++) {
    if (predicate()) return;
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 25)); });
  }
  check(predicate(), message);
}
const calls = [];
let pendingShare, pendingStatus, holdStatus = false, execCalls = 0;
try {
  const preview = createClient({ preview: true, usbPreview: true, historyPreview: true });
  let state = await preview.request('create-mesh');
  state.history = await readHistoryPage(payload => preview.request('history', payload),
    { before: 0, epoch: state.history.epoch, filter: 'all', query: '' }, state.history);
  // All 100 rows have the maximum stored output, including text that must be escaped.
  for (const item of state.history.entries) item.response = {
    state: 'pending', stdout: '<img src=x onerror=alert(1)>\n' + 'A'.repeat(4000), stderr: '', limit: 4096,
  };
  // Native status/history replies remain byte-bounded. The real UI assembles
  // a complete 100-row page across these smaller batches after the mutation.
  const historyPage = (before = 0) => {
    const remaining = state.history.entries.filter(item => !before || item.id < before);
    return { ...state.history, entries: remaining.slice(0, 8), count: 100, hasMore: remaining.length > 8 };
  };
  const snapshot = () => ({ ...state, history: historyPage() });
  const reply = (name, data) => {
    window[name].stdout.emit('data', JSON.stringify({ ok: true, data }));
    window[name].emit('exit', 0);
  };
  window.ksu = {
    exec() { execCalls++; throw new Error('blocking exec must not be called'); },
    spawn(command, args, options, callback) {
      check(args === '[]' && options === '{}', 'native spawn contract');
      const action = command.split(' ')[1]; calls.push(action);
      if (action === 'set-sharing') { pendingShare = callback; return; }
      if (action === 'status' && holdStatus) { pendingStatus = callback; return; }
      const payload = JSON.parse(atob(command.split("'")[1]));
      queueMicrotask(() => reply(callback, action === 'environment' ? { usbDebugging: true, shellUid: 0 }
        : action === 'history' ? historyPage(payload.before) : snapshot()));
    },
  };
  await act(async () => { await import('../src/main.jsx'); });
  await until(() => root.querySelector('[role="switch"]')?.disabled === false, '初次状态和 USB 调试检测未完成');
  await act(async () => { root.querySelector('[role="switch"]').click(); });
  await until(() => !!pendingShare, '开启共享未提交');
  check(root.textContent.includes('正在连接网络并开启共享'), '缺少共享等待反馈');
  const navigate = async label => {
    const button = Array.from(root.querySelectorAll('.bottom-nav button')).find(item => item.textContent === label);
    check(button && !button.disabled, label + ' 导航应始终可点击');
    await act(async () => { button.click(); await tick(); });
    check(button.getAttribute('aria-current') === 'page', label + ' 应在原生命令完成前切换');
  };
  for (const page of ['历史', '设置', '连接', '设备', '历史']) await navigate(page);
  check(root.querySelectorAll('.history-record').length === 8, '共享等待期间应保留已确认记录');
  check(root.querySelectorAll('.history-record-body').length === 0, '收起的输出不应提前渲染');
  const detail = root.querySelector('.history-record-details');
  await act(async () => { detail.querySelector('summary').click(); await tick(); });
  await until(() => detail.querySelector('.history-record-body'), '详情应按需展示');
  check(root.querySelectorAll('.history-record-body').length === 1, '只加载展开的一条详情');
  check(!detail.querySelector('img') && detail.textContent.includes('<img src=x'), '返回内容必须作为文本显示');
  state = structuredClone(state);
  Object.assign(state.sharing, { enabled: true, active: true, state: 'sharing' });
  Object.assign(state.mesh, { running: true, paused: false, localIp: '10.126.126.1' });
  state.history.revision++;
  state.history.entries[0].response.stdout = 'UPDATED_RESULT';
  state.history.entries[0].response.state = 'completed';
  state.history.entries[0].response.exitCode = 0;
  await act(async () => { reply(pendingShare, snapshot()); pendingShare = null; await tick(); });
  await until(() => root.querySelectorAll('.history-record').length === 100, '后台批次应合并为默认100条记录');
  check(root.querySelector('.history-record-details') === detail && detail.open, '后台同步不能重建或折叠展开的记录');
  check(detail.textContent.includes('UPDATED_RESULT') && detail.textContent.includes('退出码 0'), '新返回内容必须同步到展开详情');
  check(calls.filter(action => action === 'set-sharing').length === 1 && execCalls === 0, '切页不能重发共享或调用 exec');
  holdStatus = true;
  await act(async () => { root.querySelector('[aria-label="立即刷新状态"]').click(); await tick(); });
  await until(() => !!pendingStatus, '只读状态请求未开始');
  for (const page of ['设置', '连接', '历史']) await navigate(page);
  await act(async () => { reply(pendingStatus, snapshot()); pendingStatus = null; holdStatus = false; await tick(); });
  result.textContent = 'PASS · 共享等待及状态同步期间四页可切换；共享仅提交一次；100条长输出按需展开；返回文本转义；同步保留展开并更新结果。模拟桥接，未连接手机。';
} catch (error) {
  result.textContent = 'FAIL · ' + error.message;
} finally {
  delete document.hidden;
  globalThis.IS_REACT_ACT_ENVIRONMENT = false;
}

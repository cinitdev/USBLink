// Browser-only component regression: fake local observations, no native bridge or phone.
import React, { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { HomeDebugControls } from '../src/home-debug.jsx';
import { DeviceOverview } from '../src/device-overview.jsx';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// Run the same foreground sequence even when this test tab is kept in the background.
Object.defineProperty(document, 'hidden', { configurable: true, value: false });
const result = document.getElementById('result');
const fixture = document.getElementById('fixture');
const nextTick = () => new Promise(resolve => setTimeout(resolve, 0));
const realNow = Date.now;
const check = (value, message) => { if (!value) throw new Error(message); };
let root, resolveObservation, calls = 0;
const client = { available: true, request: () => new Promise(resolve => { resolveObservation = resolve; }) };
function Example() {
  const [usb, setUsb] = useState(null);
  const [enabled, setEnabled] = useState(false);
  return <>
    <DeviceOverview device={{ model: '测试手机' }} sharing={{ enabled }} sessions={[]} usbMode
      enableBlocked={usb === true ? '' : 'USB 调试待确认'} onConnections={() => {}}
      onToggle={() => { calls++; setEnabled(value => !value); }} />
    <HomeDebugControls client={client} run={() => {}} onUsbDebugging={setUsb} />
  </>;
}
const switchButton = () => fixture.querySelector('[role="switch"]');
async function mount() {
  root = createRoot(fixture);
  await act(async () => { root.render(<Example />); });
  check(switchButton().disabled, '首次检测未完成必须禁止开启');
  await act(async () => { resolveObservation({ usbDebugging: true, shellUid: 0 }); });
  check(!switchButton().disabled, '检测成功后可开启');
}
try {
  if (document.readyState !== 'complete') await new Promise(resolve => window.addEventListener('load', resolve, { once: true }));
  await nextTick();
  for (let visit = 0; visit < 2; visit++) {
    await mount();
    // WebView gains focus before delivering the first tap's click. Leave its
    // background refresh unresolved so the tap cannot rely on a lucky response.
    await act(async () => { window.dispatchEvent(new Event('focus')); await nextTick(); });
    check(!switchButton().disabled, '焦点刷新不应清空有效结果或吞掉首次点击');
    const before = calls;
    await act(async () => { switchButton().click(); });
    check(calls === before + 1 && switchButton().getAttribute('aria-checked') === 'true', '每次打开页面首次点击应开启一次');
    await act(async () => { switchButton().click(); });
    check(calls === before + 2 && switchButton().getAttribute('aria-checked') === 'false', '关闭不应受影响');
    await act(async () => { root.unmount(); });
  }
  await mount();
  Object.defineProperty(document, 'hidden', { configurable: true, value: true });
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
  check(switchButton().disabled, '隐藏页面仍需使检测结果失效');
  Object.defineProperty(document, 'hidden', { configurable: true, value: false });
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); await nextTick(); });
  check(switchButton().disabled, '返回页面不能用隐藏前的结果开启');
  await act(async () => { resolveObservation({ usbDebugging: false, shellUid: 0 }); });
  check(switchButton().disabled, 'USB 调试关闭仍禁止开启');
  await act(async () => { root.unmount(); });
  await mount();
  Date.now = () => realNow() + 21000;
  await act(async () => { window.dispatchEvent(new Event('focus')); await nextTick(); });
  check(switchButton().disabled, '焦点刷新仍必须拒绝过期结果');
  Date.now = realNow;
  await act(async () => { root.unmount(); }); root = null;
  result.textContent = 'PASS · 首次点击、重新打开、关闭、隐藏后失效、USB 调试关闭、结果过期，共 6 个场景通过。';
} catch (error) {
  result.textContent = 'FAIL · ' + error.message + '\n' + fixture.innerText;
} finally {
  delete document.hidden;
  Date.now = realNow;
  if (root) await act(async () => { root.unmount(); });
  globalThis.IS_REACT_ACT_ENVIRONMENT = false;
}

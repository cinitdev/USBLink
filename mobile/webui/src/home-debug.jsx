import React, { useEffect, useRef, useState } from 'react';
import { UsbPlug24Regular, ShieldCheckmark24Regular, ArrowSync20Regular, ChevronRight20Regular, Info20Regular } from '@fluentui/react-icons';
import { createStatusPolling } from './request-flow.mjs';

export function HomeDebugControls({ client, disabled, busy, run, onUsbDebugging }) {
  const [observation, setObservation] = useState({ value: null, confirmed: false, checking: true, error: '' });
  const refresh = useRef(() => {});
  useEffect(() => {
    onUsbDebugging?.(observation.confirmed ? observation.value?.usbDebugging ?? null : null);
    return () => onUsbDebugging?.(null);
  }, [observation.confirmed, observation.value?.usbDebugging, onUsbDebugging]);
  useEffect(() => {
    let disposed = false, reading = null, generation = 0, polling;
    const invalidate = (checking = false) => { ++generation; setObservation(previous => ({ ...previous, confirmed: false, checking })); };
    if (!client.available || busy) {
      setObservation(previous => ({ ...previous, confirmed: false, checking: false, error: client.available ? '' : '请从已获 Root 授权的模块管理器打开。' }));
      refresh.current = () => {};
      return;
    }
    const read = () => {
      if (reading) return reading;
      const stamp = generation;
      const task = (async () => {
        try {
          const value = await client.request('environment');
          if (!disposed && stamp === generation && !document.hidden) {
            setObservation({ value, confirmed: true, checking: false, error: '' });
            polling.confirmed();
          }
        } catch (error) {
          if (!disposed && stamp === generation) setObservation({ value: null, confirmed: false, checking: false, error: error.message });
        } finally {
          if (!disposed && stamp !== generation && !document.hidden) polling.wake();
        }
      })();
      reading = task;
      void task.finally(() => { if (reading === task) reading = null; });
      return task;
    };
    polling = createStatusPolling({ read, delay: () => 15000, visible: () => !document.hidden,
      expire: () => setObservation(previous => ({ ...previous, confirmed: false })), freshnessMs: 20000 });
    refresh.current = () => { invalidate(true); polling.wake(); };
    const onVisible = () => { invalidate(!document.hidden); if (document.hidden) polling.suspend(); else polling.wake(); };
    // WebView may focus just before dispatching the first tap. Refresh silently:
    // clearing a fresh USB-debugging result here disables the switch before click.
    // wake() still expires old observations; hiding the page invalidates at once.
    const onFocus = () => polling.wake();
    const onHide = () => { invalidate(); polling.suspend(); };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onFocus);
    window.addEventListener('pageshow', onFocus);
    window.addEventListener('pagehide', onHide);
    setObservation(previous => ({ ...previous, confirmed: false, checking: true }));
    polling.start();
    return () => {
      disposed = true; polling.stop(); refresh.current = () => {};
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('pageshow', onFocus);
      window.removeEventListener('pagehide', onHide);
    };
  }, [client, busy]);
  const value = observation.confirmed ? observation.value : null;
  const waiting = observation.checking ? '正在检测…' : '状态待确认';
  const usbLabel = value?.usbDebugging === true ? '已开启' : value?.usbDebugging === false ? '已关闭' : waiting;
  const shellLabel = value?.shellUid === 0 ? '有 Root 权限 · UID 0' : Number.isSafeInteger(value?.shellUid) ? '没有 Root 权限 · UID ' + value.shellUid : waiting;
  return <>
    <div className="section-heading access-heading"><h2>调试与权限</h2><button className={'text-button ' + (observation.checking ? 'working' : '')} disabled={!!busy || observation.checking || !client.available} onClick={() => refresh.current()}><ArrowSync20Regular />{observation.checking ? '检测中' : '重新检测'}</button></div>
    <section className="group access-controls" aria-label="首页调试与权限">
      <div className="row"><span className="row-icon"><UsbPlug24Regular /></span><div className="row-content"><strong>USB 调试</strong><span className={value?.usbDebugging === true ? 'access-ok' : ''}>{usbLabel}{value?.usbDebugging === true ? ' · 系统开关已启用' : value?.usbDebugging === false ? ' · 请在开发者选项开启' : ''}</span></div><button className="row-link" aria-label="打开 USB 调试设置" disabled={disabled} onClick={() => run('open-debug-settings', {}, client.preview ? '预览不会打开系统设置。' : '')}>设置<ChevronRight20Regular /></button></div>
      <div className="row"><span className="row-icon"><ShieldCheckmark24Regular /></span><div className="row-content"><strong>Shell Root 权限</strong><span className={value?.shellUid === 0 ? 'access-ok' : ''}>{shellLabel}</span></div><span className={'badge ' + (value?.shellUid === 0 ? 'active' : '')}>{value?.shellUid === 0 ? '已授权' : Number.isSafeInteger(value?.shellUid) ? '未授权' : '待确认'}</span></div>
      {observation.error && <div className="access-problem"><p className="field-error" role="status">{observation.error}</p></div>}
      {observation.confirmed && (value?.usbDebugging === null || value?.shellUid === null) && <div className="access-problem"><p className="quiet-note">系统未返回完整检测结果，请检查 WebUI 宿主的 Root 授权后重新检测。</p></div>}
    </section>
    <p className="quiet-note"><Info20Regular /><span>开启共享需要先开启 USB 调试。Shell Root 显示的是本机 WebUI 的控制权限。</span></p>
    <details className="access-explainer"><summary>Shell Root 权限说明</summary><p>此处检测模块 WebUI 执行控制命令的实际权限，由 Magisk / APatch / KernelSU 授权，不代表远程 ADB 已获得 Root。检测只在首页可见时进行，每 15 秒更新，返回页面立即检测。</p></details>
  </>;
}

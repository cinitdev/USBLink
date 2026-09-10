import { useCallback, useEffect, useRef, useState } from "react";
import { applySnapshot, createLatestPoller } from "./usb-state.mjs";

export function useAttachedDevices(read, enabled, autoRefresh) {
  const [snapshot, setSnapshot] = useState({ devices: [], ready: false, problem: "" });
  const poller = useRef(null);

  useEffect(() => {
    if (!enabled) {
      setSnapshot((old) => old.ready ? { ...old, ready: false, problem: "USB/IP 组件尚未通过检查，连接状态待确认" } : old);
      return;
    }
    const current = createLatestPoller(read, (result) => setSnapshot((old) => applySnapshot(old, result)));
    poller.current = current;
    current.refresh();
    const refresh = () => { if (!document.hidden) current.refresh(); };
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    const timer = autoRefresh ? window.setInterval(refresh, 5000) : null;
    return () => {
      current.dispose();
      if (poller.current === current) poller.current = null;
      window.clearInterval(timer);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [read, enabled, autoRefresh]);

  const refresh = useCallback((force = false) => poller.current?.refresh(force) || Promise.resolve(), []);
  const invalidate = useCallback(() => poller.current?.invalidate(), []);
  return { ...snapshot, refresh, invalidate };
}

export function isStatus(value) {
  return !!value?.sharing && !!value?.mesh && !!value?.debug;
}

export function pollDelay(status, failures = 0) {
  if (failures) return Math.min(15000, 2000 * 2 ** Math.min(failures - 1, 3));
  return status?.sharing?.enabled || ['starting', 'waiting_network'].includes(status?.sharing?.state) || (status?.mesh?.configured && !status.mesh.running) ? 2000 : 5000;
}

// One read-only loop, also used after WebView resume. Generation fencing prevents
// an older completion from replacing a timer scheduled by a newer user action.
export function createStatusPolling({ read, delay, visible = () => true, available = () => true, expire = () => {},
  setTimer = setTimeout, clearTimer = clearTimeout, now = () => Date.now(), freshnessMs = 10000 }) {
  let timer, expiry, generation = 0, stopped = true, confirmedAt = null;
  const expired = () => { if (confirmedAt !== null && now() - confirmedAt >= freshnessMs) expire(); };
  const poll = async () => {
    const stamp = ++generation; clearTimer(timer);
    if (stopped || !visible() || !available()) return;
    expired();
    try { await read(); }
    finally { if (!stopped && stamp === generation && visible() && available()) timer = setTimer(poll, delay()); }
  };
  return {
    start() { if (!stopped) return; stopped = false; void poll(); },
    confirmed() { confirmedAt = now(); clearTimer(expiry); expiry = setTimer(() => { if (!stopped) expired(); }, freshnessMs); },
    wake(after = 0) { if (stopped) return; ++generation; clearTimer(timer); expired(); if (visible() && available()) timer = setTimer(poll, after); },
    suspend() { ++generation; clearTimer(timer); },
    stop() { stopped = true; ++generation; clearTimer(timer); clearTimer(expiry); },
  };
}

// Reads can overlap a click, but never delay it or overwrite its newer result.
// Mutations remain single-flight, including while the native bridge is starting.
export function createRequestFlow(request, { onStatus, onReadError, onPending, onActionError, beforeAction = async () => {} }) {
  let generation = 0, reading = null, pending = '', disposed = false;
  const read = () => {
    if (disposed || pending) return Promise.resolve(null);
    if (reading) return reading;
    const stamp = generation;
    const task = (async () => {
      try {
        const result = await request('status');
        if (!isStatus(result)) throw new Error('后台状态不完整，请检查模块版本。');
        if (!disposed && stamp === generation) { onStatus(result); return result; }
      } catch (error) { if (!disposed && stamp === generation) onReadError(error); }
      return null;
    })();
    reading = task;
    void task.finally(() => { if (reading === task) reading = null; });
    return task;
  };
  return {
    read,
    async run(action, payload = {}) {
      if (disposed || pending) return null;
      ++generation; reading = null; pending = action; onPending(action, payload);
      let failed = false;
      try {
        // Let the pending feedback paint before entering the manager's native bridge.
        await beforeAction();
        if (disposed) return null;
        const result = await request(action, payload);
        if (action !== 'pairing-code' && !isStatus(result)) throw new Error('操作已提交，但状态无法确认。请刷新后检查。');
        if (!disposed && isStatus(result)) onStatus(result);
        return disposed ? null : result;
      } catch (error) { failed = true; if (!disposed) onActionError(error); return null; }
      finally {
        pending = '';
        if (!disposed) {
          onPending('', {});
          // Read only after failure: do not replay the operation or guess it succeeded.
          if (failed) void read();
        }
      }
    },
    dispose() { disposed = true; ++generation; reading = null; },
  };
}

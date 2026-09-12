export function sameDevice(left, right) {
  return left.host === right.host && left.busId === right.busId
    && left.vidPid?.toLowerCase() === right.vidPid?.toLowerCase();
}

export function applyAttachedState(host, devices, attached) {
  // Exporters can temporarily omit an in-use device. Its confirmed local mount
  // remains a row for this host, independently of the remote export snapshot.
  const rows = [...devices];
  for (const device of attached) {
    if (device.host !== host) continue;
    const index = rows.findIndex(row => row.busId === device.busId);
    if (index < 0) rows.push(device);
    else if (!sameDevice({ ...rows[index], host }, device)) rows[index] = device;
  }
  return rows.map((device) => ({
    ...device,
    attached: attached.some((item) => sameDevice({ ...device, host }, item)),
  }));
}

export function applySnapshot(previous, result) {
  return result.ok
    ? { devices: result.devices, ready: true, problem: "" }
    : { ...previous, ready: true, problem: String(result.error) };
}

// A late poll must not overwrite the state sampled after a manual USB operation.
export function createLatestPoller(read, publish) {
  let revision = 0;
  let pending = null;
  let disposed = false;
  let paused = false;
  return {
    refresh(force = false) {
      if (disposed || paused) return Promise.resolve();
      if (pending && !force) return pending;
      const current = ++revision;
      const task = Promise.resolve().then(read).then(
        (devices) => { if (!disposed && current === revision) publish({ ok: true, devices }); },
        (error) => { if (!disposed && current === revision) publish({ ok: false, error }); },
      ).finally(() => { if (pending === task) pending = null; });
      pending = task;
      return task;
    },
    invalidate() { revision += 1; },
    pause() { paused = true; revision += 1; pending = null; },
    resume() { paused = false; },
    accept(devices) { revision += 1; if (!disposed) publish({ ok: true, devices }); },
    dispose() { disposed = true; revision += 1; },
  };
}

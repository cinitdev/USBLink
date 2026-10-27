import { applyAttachedState } from './usb-state.mjs';

// Count by source address, never by model or USB bus ID shared by other exporters.
export function networkDeviceRows(peers, mounts, query = '') {
  const counts = new Map();
  for (const mount of mounts) counts.set(mount.host, (counts.get(mount.host) || 0) + 1);
  const term = query.trim().toLocaleLowerCase();
  return peers.filter(peer => !term || `${peer.name} ${peer.ip}`.toLocaleLowerCase().includes(term))
    .map(peer => ({ ...peer, mountedCount: counts.get(peer.ip) || 0 }));
}

// A previous source's response must never become the next source's connectable list.
export function sourceDeviceRows(host, snapshot, mounts) {
  return applyAttachedState(host, snapshot.host === host ? snapshot.devices : [], mounts);
}

export function sourceDeviceKey(host, device) {
  return JSON.stringify([host, device.busId, device.vidPid?.toLowerCase() || '']);
}

export function selectedSourceBusIds(host, devices, selection) {
  return new Set(devices.filter(device => !device.attached && selection.has(sourceDeviceKey(host, device)))
    .map(device => device.busId));
}

// Browser-only fixtures. Production always discovers peers from the authenticated mesh.
export function previewNetworkPeers(count) {
  return Array.from({ length: count }, (_, index) => ({
    name: `${index % 3 === 2 ? '测试电脑' : '测试手机'} ${String(index + 1).padStart(2, '0')}`,
    ip: `10.126.${126 + Math.floor(index / 253)}.${index % 253 + 2}`,
    online: index % 5 !== 4, usbReady: index % 5 !== 4,
    os: index % 3 === 2 ? 'windows' : 'android',
    usbKind: index % 3 === 2 ? 'usbip' : 'android-adb-experimental',
    adbReady: false, adbState: 'unavailable', latency: '3.5', tunnel: 'udp',
  }));
}

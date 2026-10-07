export function canUsePeer(peer) {
  return peer?.online === true && (peer.os === 'android' ? peer.usbKind === 'android-adb-experimental' && peer.usbReady === true : peer.usbReady !== false);
}

export function peerStatusLabel(peer) {
  if (!peer?.online) return 'USBLink 未运行或不可达';
  if (peer.os !== 'android') return 'USBLink 在线';
  if (peer.usbKind === 'android-adb-experimental') return peer.usbReady ? '手机在线 · USB ADB 共享就绪' : '手机在线 · USB 共享未就绪';
  return peer.adbReady ? '手机在线 · ADB 共享就绪' : peer.adbState === 'off' ? '手机在线 · 共享已关闭' : peer.adbState === 'preparing' ? '手机在线 · 正在准备共享' : '手机在线 · 共享未就绪';
}

export function adbConnectCommand(peer) {
  if (!peer?.online || peer.os !== 'android' || peer.adbReady !== true) return '';
  const parts = String(peer.ip).split('.');
  if (parts.length !== 4 || parts.some(part => !/^\d{1,3}$/.test(part) || Number(part) > 255)) return '';
  return `adb connect ${peer.ip}:3242`;
}

export function mergePeerPresence(previous, incoming, { configured, running, sameNetwork, problem }) {
  if (!configured) return [];
  const current = new Map((sameNetwork ? previous : []).map(peer => [peer.ip, {
    ...peer, online: false, usbReady: false, adbReady: false, adbState: 'unavailable', latency: "-",
    problem: problem || "对方 USBLink 未运行或已离开当前连接，等待对方打开程序",
  }]));
  for (const peer of incoming || []) current.set(peer.ip, running ? {
    ...peer,
    // Retain the last authenticated kind only as an offline display hint on this network.
    os: !peer.online && peer.os === 'unknown' ? current.get(peer.ip)?.os || 'unknown' : peer.os,
    usbKind: !peer.online && peer.os === 'unknown' ? current.get(peer.ip)?.usbKind || 'usbip' : peer.usbKind,
  } : {
    ...peer, online: false, usbReady: false, adbReady: false, adbState: 'unavailable', latency: "-", problem: problem || "本机网络未就绪，对方在线状态待确认",
  });
  return [...current.values()];
}

export function mountPresence(device, peers, networkReady) {
  const peer = peers.find(peer => peer.ip === device.host);
  if (!networkReady || !peer) return "对方状态待确认";
  if (!peer.online) return "对方 USBLink 未运行或不可达";
  if (peer.usbReady === false) return "共享服务不可达";
  return "已连接";
}

export function canUsePeer(peer) {
  return peer?.online === true && peer.usbReady !== false;
}

export function mergePeerPresence(previous, incoming, { configured, running, sameNetwork, problem }) {
  if (!configured) return [];
  const current = new Map((sameNetwork ? previous : []).map(peer => [peer.ip, {
    ...peer, online: false, usbReady: false, latency: "-",
    problem: problem || "对方 USBLink 未运行或已离开当前连接，等待对方打开程序",
  }]));
  for (const peer of incoming || []) current.set(peer.ip, running ? peer : {
    ...peer, online: false, usbReady: false, latency: "-", problem: problem || "本机网络未就绪，对方在线状态待确认",
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

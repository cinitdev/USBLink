// Preferences and name caches are optional. A corrupt or full WebView store must
// never prevent USB status from loading or turn a successful operation into an error.
export function readStored(key, fallback = null) {
  try { return localStorage.getItem(key) ?? fallback; } catch { return fallback; }
}

export function readBoolean(key, fallback) {
  const value = readStored(key);
  return value === "true" ? true : value === "false" ? false : fallback;
}

export function writeStored(key, value) {
  try { localStorage.setItem(key, value); } catch { /* Keep the in-memory state. */ }
}

export function removeStored(key) {
  try { localStorage.removeItem(key); } catch { /* Storage may be unavailable. */ }
}

export function normalizeRelay(value) {
  const relay = value.trim();
  const match = /^(?:tcp|udp|ws|wss|wg|quic):\/\/[A-Za-z0-9.-]+:(\d{1,5})\/?$/.exec(relay);
  if (relay.length > 260 || !match || Number(match[1]) < 1 || Number(match[1]) > 65535) {
    throw new Error("中继地址格式无效，请填写协议、主机和 1–65535 的端口");
  }
  if (/^\w+:\/\/public\.easytier\.top:/i.test(relay)) {
    throw new Error("这个公共节点已经失效，请使用默认节点或填写其他地址");
  }
  return relay;
}

import test from "node:test";
import assert from "node:assert/strict";
import { canUsePeer, mergePeerPresence, mountPresence } from "./peer-state.mjs";

const peer = { ip: "10.0.0.2", name: "HOST", online: true, usbReady: true, latency: "4" };
const network = { configured: true, running: true, sameNetwork: true };

test("pairing and a remembered peer do not establish online presence", () => {
  const [offline] = mergePeerPresence([peer], [], network);
  assert.equal(offline.name, peer.name);
  assert.equal(offline.online, false);
  assert.equal(canUsePeer(offline), false);
  assert.equal(offline.latency, "-");
});
test("fresh probes replace stale status and distinguish USB service readiness", () => {
  const offline = mergePeerPresence([peer], [], network);
  const [online] = mergePeerPresence(offline, [peer], network);
  assert.equal(canUsePeer(online), true);
  assert.equal(canUsePeer({ ...online, usbReady: false }), false);
  assert.equal(canUsePeer({ ...online, online: false }), false);
});
test("network changes discard remembered peers and local failure invalidates online flags", () => {
  assert.deepEqual(mergePeerPresence([peer], [], { ...network, sameNetwork: false }), []);
  assert.deepEqual(mergePeerPresence([peer], [], { ...network, configured: false }), []);
  assert.equal(mergePeerPresence([peer], [peer], { ...network, running: false })[0].online, false);
});
test("a driver mount record cannot make an offline computer look connected", () => {
  const device = { host: peer.ip };
  assert.equal(mountPresence(device, [peer], true), "已连接");
  assert.equal(mountPresence(device, [{ ...peer, online: false }], true), "对方离线或不可达");
  assert.equal(mountPresence(device, [{ ...peer, usbReady: false }], true), "共享服务不可达");
  assert.equal(mountPresence(device, [peer], false), "对方状态待确认");
  assert.equal(mountPresence(device, [], true), "对方状态待确认");
});

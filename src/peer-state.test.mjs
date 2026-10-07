import test from "node:test";
import assert from "node:assert/strict";
import { canUsePeer, mergePeerPresence, mountPresence, peerStatusLabel, adbConnectCommand } from "./peer-state.mjs";

const peer = { ip: "10.0.0.2", name: "HOST", online: true, usbReady: true, latency: "4" };
const network = { configured: true, running: true, sameNetwork: true };

test('experimental USB phones use the existing USB flow only while authenticated and ready', () => {
  const phone={...peer,os:'android',usbKind:'android-adb-experimental',adbReady:false};
  assert.equal(canUsePeer(phone),true);
  assert.equal(adbConnectCommand(phone),'');
  assert.equal(canUsePeer({...phone,usbReady:false}),false);
  assert.equal(canUsePeer({...phone,usbReady:undefined}),false);
  assert.equal(canUsePeer({...phone,online:false}),false);
  const [lost]=mergePeerPresence([phone],[{...phone,online:false,os:'unknown',usbKind:'usbip'}],network);
  assert.equal(lost.usbKind,phone.usbKind);
  assert.equal(canUsePeer(lost),false);
});

test('authenticated Android capability is independent of USB/IP and sharing preference', () => {
  const phone = { ...peer, os: 'android', usbReady: false, adbReady: false, adbState: 'off' };
  assert.equal(canUsePeer(phone), false);
  assert.equal(canUsePeer({ ...phone, usbReady: true }), false);
  assert.equal(peerStatusLabel(phone), '手机在线 · 共享已关闭');
  assert.equal(adbConnectCommand(phone), '');
  const ready = { ...phone, adbReady: true, adbState: 'ready' };
  assert.equal(adbConnectCommand(ready), `adb connect ${phone.ip}:3242`);
  assert.equal(adbConnectCommand({ ...ready, online: false }), '');
  assert.equal(adbConnectCommand({ ...ready, ip: '10.0.0.2;shutdown' }), '');
  assert.equal(adbConnectCommand({ ...ready, ip: '999.0.0.2' }), '');
  assert.equal(adbConnectCommand({ ...peer, name: 'USBLink-Android' }), '', 'Hostname alone never enables ADB capability');
  const [lost] = mergePeerPresence([ready], [], network);
  assert.equal(lost.adbReady, false);
  assert.equal(adbConnectCommand(lost), '');
  const [localOffline] = mergePeerPresence([ready], [ready], { ...network, running: false });
  assert.equal(localOffline.adbReady, false);
  const unconfirmed = { ...ready, os: 'unknown', online: false, adbReady: false, adbState: 'unavailable' };
  const [offlinePhone] = mergePeerPresence([ready], [unconfirmed], network);
  assert.equal(offlinePhone.os, 'android', 'Offline display retains previously authenticated kind');
  assert.equal(adbConnectCommand(offlinePhone), '');
  assert.equal(mergePeerPresence([ready], [unconfirmed], { ...network, sameNetwork: false })[0].os, 'unknown');
  assert.equal(mergePeerPresence([ready], [{ ...peer, os: 'windows' }], network)[0].os, 'windows');
});

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
  assert.equal(mountPresence(device, [{ ...peer, online: false }], true), "对方 USBLink 未运行或不可达");
  assert.equal(mountPresence(device, [{ ...peer, usbReady: false }], true), "共享服务不可达");
  assert.equal(mountPresence(device, [peer], false), "对方状态待确认");
  assert.equal(mountPresence(device, [], true), "对方状态待确认");
});

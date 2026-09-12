import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { mkdir } from "node:fs/promises";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
const page = await browser.newPage({ viewport: { width: 820, height: 600 } });
const errors = [];
page.on("pageerror", error => errors.push(error.message));
await page.addInitScript(() => {
  localStorage.setItem("usblink.autoRefresh", "false");
  const peer = { ip: "10.126.126.2", name: "HOST-PC", online: true, usbReady: true, problem: null, latency: "4", tunnel: "udp" };
  const phone = { busId: "3-2", vidPid: "18d1:4ee7", name: "测试手机", detail: "Android", friendlyName: true, shared: true, attached: false };
  const serial = { ...phone, busId: "3-3", name: "测试串口", host: peer.ip, port: 1, attached: true };
  const state = window.presenceTest = { peer, phone, serial, peers: [peer], calls: {}, deferRemote: false, mounted: [serial] };
  window.__TAURI_INTERNALS__ = { async invoke(command) {
    state.calls[command] = (state.calls[command] || 0) + 1;
    switch (command) {
      case "get_sharing_session": return { phase: "ready", ready: true, problem: null };
      case "get_environment_status": return { computerName: "TEST-PC", usbipdInstalled: true, usbipInstalled: true, usbipSafe: true, easytierVersion: "2.6.4" };
      case "get_mesh_status": return { configured: true, running: true, localIp: "10.126.126.1", networkName: "presence-test", relay: "tcp://183.230.36.171:11010", peers: structuredClone(state.peers), peerCount: state.peers.filter(p => p.online).length, problem: null, needsRepair: false };
      case "ensure_mesh_service_current": return false;
      case "list_local_devices": return [];
      case "list_remote_devices":
        if (state.deferRemote) { state.deferRemote = false; return new Promise(resolve => { state.finishRemote = () => resolve([phone, serial]); }); }
        return [phone, serial];
      case "list_attached_devices": return state.mounted;
      case "detach_all_devices": state.mounted = []; return;
      default: throw new Error("Unexpected command: " + command);
    }
  } };
});

try {
  await page.goto(process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173");
  await page.getByRole("button", { name: "连接", exact: true }).click();
  const table = page.locator(".device-panel");
  const imported = page.getByRole("region", { name: "已连接到本机的 USB" });
  const attach = page.getByRole("button", { name: "连接所选设备", exact: true });
  await page.getByText("对方电脑在线", { exact: true }).waitFor();
  await table.getByRole("checkbox", { name: "选择 测试手机" }).check();
  assert.equal(await attach.isEnabled(), true);
  await page.evaluate(() => { window.presenceTest.deferRemote = true; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await page.waitForFunction(() => !!window.presenceTest.finishRemote);
  // Presence must keep polling even when automatic USB device refresh is off.
  await page.evaluate(() => { const s = window.presenceTest; s.peers = [{ ...s.peer, online: false, usbReady: false, problem: "对方电脑离线或网络不可达" }]; });
  await page.getByText("暂无在线电脑", { exact: true }).waitFor();
  await imported.getByText("对方离线或不可达", { exact: true }).waitFor();
  assert.equal(await attach.isDisabled(), true);
  assert.equal(await table.getByText("已连接", { exact: true }).count(), 0);
  assert.equal(await table.getByRole("checkbox", { name: "选择 测试手机" }).isChecked(), false);
  await page.evaluate(() => window.presenceTest.finishRemote());
  assert.equal(await attach.isDisabled(), true);
  assert.equal(await table.getByText("可连接", { exact: true }).count(), 0);

  await page.evaluate(() => { window.presenceTest.peers = []; window.dispatchEvent(new Event("focus")); });
  await page.getByText(/对方电脑已离线或已离开当前连接/).waitFor();
  await page.locator(".peer-picker").getByText("HOST-PC", { exact: true }).waitFor();
  await mkdir("test-results", { recursive: true });
  await page.screenshot({ path: "test-results/peer-offline-820.png" });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);

  await page.evaluate(() => { const s = window.presenceTest; s.peers = [{ ...s.peer, usbReady: false, problem: "电脑在线，但 USB 共享服务不可达" }]; window.dispatchEvent(new Event("focus")); });
  await page.getByText("对方电脑在线", { exact: true }).waitFor();
  await imported.getByText("共享服务不可达", { exact: true }).waitFor();
  assert.equal(await attach.isDisabled(), true);
  await page.evaluate(() => { const s = window.presenceTest; s.peers = [s.peer]; window.dispatchEvent(new Event("focus")); });
  await imported.getByText("已连接", { exact: true }).waitFor();
  await table.getByRole("checkbox", { name: "选择 测试手机" }).check();
  assert.equal(await attach.isEnabled(), true);
  assert.equal(await page.evaluate(() => window.presenceTest.calls.attach_devices || 0), 0, "recovery never reconnects USB automatically");
  await imported.getByRole("button", { name: "断开全部 USB", exact: true }).click();
  await imported.waitFor({ state: "detached" });
  assert.deepEqual(errors, []);
  console.log("PASS: live peer -> stale route offline -> peer removed -> online without USB service -> recovery; retained mounts, late results, no automatic attach, 820px layout");
} finally { await browser.close(); }

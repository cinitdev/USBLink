import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
const page = await browser.newPage({ viewport: { width: 1180, height: 760 } });
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
await page.addInitScript(() => {
  const phone = { busId: "3-2", vidPid: "18d1:4ee7", name: "Redmi K40", detail: "Android 设备", friendlyName: true, shared: true, attached: false };
  const peer = { name: "LAPTOP", ip: "10.126.126.2", online: true, latency: "5", tunnel: "udp" };
  const mesh = { configured: true, running: true, localIp: "10.126.126.1", networkName: "test-network", relay: "tcp://127.0.0.1:11010", peerCount: 1, peers: [peer], needsRepair: false, problem: null };
  const environment = { computerName: "TEST-PC", usbipdInstalled: true, usbipInstalled: true, usbipSafe: true, meshConfigured: true, easytierVersion: "2.6.4", usbipVersion: "0.9.8.0" };
  const state = { phone, attached: [], remoteError: false, portError: false, noPeers: false, calls: {}, pendingAttach: null };
  window.usbTest = state;
  window.__TAURI_INTERNALS__ = {
    async invoke(command) {
      state.calls[command] = (state.calls[command] || 0) + 1;
      switch (command) {
        case "get_sharing_session": return { phase: "ready", ready: true, problem: null };
        case "get_environment_status": return environment;
        case "get_mesh_status": return state.noPeers ? { ...mesh, running: false, peers: [], peerCount: 0, problem: "模拟网络查询失败" } : mesh;
        case "list_local_devices": return [{ ...phone, attached: true }];
        case "list_remote_devices":
          if (state.remoteError) throw new Error("模拟远程列表超时");
          return [phone];
        case "list_attached_devices":
          if (state.portError) throw new Error("模拟端口读取失败");
          return state.attached.map((device) => ({ ...device }));
        case "ensure_mesh_service_current": return false;
        case "ensure_usb_sharing_ready": return true;
        case "attach_devices":
          await new Promise((resolve) => { state.pendingAttach = resolve; });
          state.attached = [{ ...phone, host: peer.ip, port: 1, attached: true }];
          return state.attached.map(device => ({ ...device }));
        case "detach_all_devices": state.attached = []; return;
        default: throw new Error("Unexpected command: " + command);
      }
    },
  };
});
try {
  await page.goto(process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173");
  await page.getByText("正在被使用", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "停止共享", exact: true }).count(), 1);
  await page.getByRole("button", { name: "连接", exact: true }).click();
  await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).click();
  await page.getByRole("button", { name: "连接所选设备", exact: true }).click();
  await page.waitForFunction(() => !!window.usbTest.pendingAttach);
  const remoteReads = await page.evaluate(() => window.usbTest.calls.list_remote_devices);
  await page.evaluate(() => { window.usbTest.remoteError = true; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  assert.equal(await page.evaluate(() => window.usbTest.calls.list_remote_devices), remoteReads, "discovery waits for an in-progress import");
  assert.equal(await page.getByRole("button", { name: "正在连接…", exact: true }).isDisabled(), true);
  await page.evaluate(() => window.usbTest.pendingAttach());
  await page.getByText(/远程设备列表暂时无法更新/).waitFor();
  const imported = page.getByRole("region", { name: "已连接到本机的 USB", exact: true });
  await imported.getByText("Redmi K40", { exact: true }).waitFor();
  await imported.getByText("已连接", { exact: true }).waitFor();
  await page.screenshot({ path: "ui-usb-connected.png" });
  assert.equal(await page.getByRole("button", { name: "连接所选设备", exact: true }).isDisabled(), true);

  await page.evaluate(() => { window.usbTest.noPeers = true; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await imported.getByText("对方状态待确认", { exact: true }).waitFor();
  await page.locator(".peer-picker").getByText("LAPTOP", { exact: true }).waitFor();
  assert.equal(await imported.getByText("Redmi K40", { exact: true }).count(), 1);
  await page.setViewportSize({ width: 820, height: 600 });
  await page.screenshot({ path: "ui-usb-peer-query-failed-820.png" });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  assert.equal(overflow, false, "No horizontal overflow at minimum window size");

  await page.evaluate(() => { window.usbTest.portError = true; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await imported.getByRole("heading", { name: /USB 连接状态待确认/ }).waitFor();
  assert.equal(await imported.getByText("Redmi K40", { exact: true }).count(), 1);
  await page.screenshot({ path: "ui-usb-unconfirmed-820.png" });

  await page.evaluate(() => { window.usbTest.portError = false; window.usbTest.noPeers = false; window.usbTest.remoteError = false; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await imported.getByText("已连接", { exact: true }).waitFor();
  await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).waitFor();
  assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).isDisabled(), true);
  await imported.getByRole("button", { name: "断开全部 USB", exact: true }).click();
  await imported.waitFor({ state: "detached" });
  await page.getByText("可连接", { exact: true }).waitFor();
  assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).isDisabled(), false);

  await page.evaluate(() => {
    const state = window.usbTest;
    state.attached = Array.from({ length: 6 }, (_, index) => ({ ...state.phone, name: "Redmi K40 " + (index + 1), host: "10.126.126." + (index + 2), port: index + 1 }));
  });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await imported.getByText("Redmi K40 6", { exact: true }).waitFor();
  assert.equal(await imported.getByText("已连接", { exact: true }).count(), 1);
  assert.equal(await imported.getByText("对方状态待确认", { exact: true }).count(), 5);
  await page.screenshot({ path: "ui-usb-six-devices-820.png" });
  await page.evaluate(() => { window.usbTest.attached = []; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await imported.waitFor({ state: "detached" });
  assert.deepEqual(errors, []);
  console.log("PASS: host Attached, pending attach, remote timeout, peer loss, port failure/recovery, detach, six hosts and actual empty snapshot; no browser errors.");
} finally {
  await browser.close();
}

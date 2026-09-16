import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });

async function scenario(fail = false) {
  const context = await browser.newContext({ viewport: { width: 1180, height: 760 } });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(() => {
    localStorage.setItem("usblink.autoRefresh", "false");
    const device = { busId: "3-2", vidPid: "18d1:4ee7", name: "测试手机", detail: "Android 设备", friendlyName: true, shared: true, attached: false };
    const peer = { name: "HOST-PC", ip: "10.126.126.2", online: true, latency: "5", tunnel: "udp" };
    const mesh = { configured: true, running: true, localIp: "10.126.126.1", networkName: "single-click-test", relay: "tcp://183.230.36.171:11010", peerCount: 1, peers: [peer], problem: null, needsRepair: false };
    const state = window.singleTest = { calls: {}, mounted: [], pending: false, exported: [device], deferMesh: false, deferRemote: false, deferPorts: false };
    window.__TAURI_INTERNALS__ = { async invoke(command, args) {
      state.calls[command] = (state.calls[command] || 0) + 1;
      switch (command) {
        case "get_sharing_session": return { phase: "ready", ready: true, problem: null };
        case "get_environment_status": return { computerName: "TEST-PC", usbipdInstalled: true, usbipInstalled: true, usbipSafe: true, easytierVersion: "2.6.4", usbipVersion: "0.9.8.0" };
        case "get_mesh_status":
          if (state.deferMesh) { state.deferMesh = false; return new Promise(resolve => { state.finishMesh = () => resolve({ ...mesh, running: false, peers: [], peerCount: 0, problem: "连接初始化期间的旧网络查询" }); }); }
          return mesh;
        case "list_local_devices": return [];
        case "ensure_mesh_service_current": return false;
        case "list_remote_devices":
          if (state.deferRemote) { state.deferRemote = false; return new Promise(resolve => { state.finishRemote = () => resolve([]); }); }
          return structuredClone(state.exported);
        case "list_attached_devices":
          if (state.deferPorts) { state.deferPorts = false; return new Promise(resolve => { state.finishPorts = () => resolve([]); }); }
          return structuredClone(state.mounted);
        case "attach_devices":
          state.attachArgs = args;
          state.pending = true;
          return new Promise((resolve, reject) => { state.finishAttach = success => {
            state.pending = false;
            if (!success) { reject("设备 3-2 的连接请求已提交，但未能确认挂载"); return; }
            state.mounted = [{ ...device, attached: true, host: peer.ip, port: 1 }];
            // A device in use may disappear from the export list.
            state.exported = [];
            resolve(structuredClone(state.mounted));
          }; });
        case "detach_all_devices": state.mounted = []; return;
        default: throw new Error("Unexpected command: " + command);
      }
    } };
  });
  try {
    await page.goto(process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173");
    await page.getByRole("button", { name: "连接", exact: true }).click();
    const row = page.locator(".device-row").filter({ hasText: "测试手机" });
    await row.getByRole("checkbox").check();
    await page.evaluate(() => { const s = window.singleTest; s.deferMesh = true; s.deferRemote = true; s.deferPorts = true; });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.waitForFunction(() => window.singleTest.finishMesh && window.singleTest.finishRemote && window.singleTest.finishPorts);
    await page.getByRole("button", { name: "连接所选设备", exact: true }).click();
    await page.waitForFunction(() => !!window.singleTest.finishAttach);
    // Deliver stale empty snapshots after the user's one and only attach request.
    await page.evaluate(() => { const s = window.singleTest; s.finishMesh(); s.finishRemote(); s.finishPorts(); });
    assert.equal(await page.getByRole("button", { name: "刷新", exact: true }).isDisabled(), true);
    await page.evaluate(() => window.dispatchEvent(new Event("focus")));
    assert.equal(await row.count(), 1, "pending connection must not clear the device row");
    assert.equal(await page.getByRole("button", { name: "正在连接…", exact: true }).isDisabled(), true);
    await page.evaluate(success => window.singleTest.finishAttach(success), !fail);
    if (fail) {
      await page.getByText(/设备 3-2 的连接请求已提交，但未能确认挂载/).waitFor();
      assert.equal(await page.getByRole("region", { name: "已连接到本机的 USB" }).count(), 0);
      assert.equal(await row.getByText("已连接", { exact: true }).count(), 0);
      assert.equal(await page.getByRole("button", { name: "连接所选设备", exact: true }).isEnabled(), true);
    } else {
      await page.getByRole("region", { name: "已连接到本机的 USB" }).getByText("已连接", { exact: true }).waitFor();
      await row.getByText("已连接", { exact: true }).waitFor();
      assert.equal(await row.getByRole("checkbox").isDisabled(), true);
      assert.equal(await page.getByRole("button", { name: "连接所选设备", exact: true }).isDisabled(), true);
    }
    assert.equal(await page.evaluate(() => window.singleTest.calls.attach_devices), 1, "never submit attach twice");
    assert.deepEqual(await page.evaluate(() => window.singleTest.attachArgs), { host: "10.126.126.2", busIds: ["3-2"], expectedVidPids: { "3-2": "18d1:4ee7" } }, "native preflight must validate the device the user selected");
    assert.deepEqual(errors, []);
    console.log(`PASS: single click ${fail ? "reports failure without false success or automatic reattach" : "retains the row and shows the confirmed mount with auto-refresh disabled"}`);
  } finally { await context.close(); }
}

try { await scenario(); await scenario(true); } finally { await browser.close(); }

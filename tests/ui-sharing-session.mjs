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
  const device = { busId: "3-2", vidPid: "18d1:4ee7", name: "测试手机", detail: "Android", friendlyName: true, shared: true, attached: false };
  const state = window.sessionTest = {
    calls: {}, devices: [device], phase: "starting", problem: null, deferLocal: false,
    finishCleanup() { this.devices = this.devices.map(d => ({ ...d, shared: false, attached: false })); this.phase = "ready"; this.problem = null; },
  };
  window.__TAURI_INTERNALS__ = { async invoke(command) {
    state.calls[command] = (state.calls[command] || 0) + 1;
    switch (command) {
      case "get_sharing_session": return { phase: state.phase, ready: state.phase === "ready", problem: state.problem };
      case "retry_sharing_cleanup": return new Promise(resolve => { state.finishRetry = () => {
        state.finishCleanup(); resolve({ phase: "ready", ready: true, problem: null });
      }; });
      case "get_environment_status": return { computerName: "TEST-PC", usbipdInstalled: true, usbipInstalled: true, usbipSafe: true, easytierVersion: "2.6.4" };
      case "get_mesh_status": return { configured: true, running: true, networkName: "session-test", localIp: "10.126.126.1", peers: [], peerCount: 0, relay: "tcp://183.230.36.171:11010", problem: null, needsRepair: false };
      case "list_local_devices": {
        const snapshot = structuredClone(state.devices);
        if (state.deferLocal) { state.deferLocal = false; return new Promise(resolve => { state.finishLocal = () => resolve(snapshot); }); }
        return snapshot;
      }
      case "list_attached_devices": return [];
      case "ensure_mesh_service_current": return false;
      case "ensure_usb_sharing_ready": return true;
      case "share_devices": state.devices[0].shared = true; return;
      default: throw new Error("Unexpected command: " + command);
    }
  } };
});
try {
  await page.goto(process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173");
  const notice = page.locator(".sharing-session-status");
  const share = page.getByRole("button", { name: "共享所选设备", exact: true });
  const check = page.getByRole("checkbox", { name: "选择 测试手机", exact: true });
  await notice.getByText(/正在清理上次遗留/).waitFor();
  await check.waitFor();
  assert.equal(await check.isDisabled(), true);
  assert.equal(await share.isDisabled(), true);
  assert.equal(await page.evaluate(() => window.sessionTest.calls.ensure_usb_sharing_ready || 0), 0);

  await page.evaluate(() => { const s = window.sessionTest; s.phase = "failed"; s.problem = "未能清理遗留 USB 共享：已取消管理员授权"; });
  await notice.getByText(/已取消管理员授权/).waitFor();
  assert.equal(await page.getByText("已共享", { exact: true }).count(), 1, "failed cleanup cannot pretend the real share is gone");
  assert.equal(await share.isDisabled(), true);
  await mkdir("test-results", { recursive: true });
  await page.screenshot({ path: "test-results/sharing-cleanup-failed-820.png" });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.getByRole("button", { name: "重新清理共享", exact: true }).click();
  await page.waitForFunction(() => !!window.sessionTest.finishRetry);
  assert.equal(await check.isDisabled(), true);
  await page.evaluate(() => window.sessionTest.finishRetry());
  await notice.waitFor({ state: "detached" });
  await page.getByText("可共享", { exact: true }).waitFor();
  await check.check();
  assert.equal(await share.isEnabled(), true);
  await share.click();
  await page.getByText("已共享", { exact: true }).waitFor();
  assert.equal(await page.evaluate(() => window.sessionTest.calls.share_devices), 1);

  await page.evaluate(() => { const s = window.sessionTest; s.phase = "closing"; });
  await notice.getByText(/正在停止 USB 共享，完成后退出/).waitFor();
  assert.equal(await check.isDisabled(), true);
  assert.equal(await share.isDisabled(), true);
  await page.evaluate(() => { const s = window.sessionTest; s.phase = "failed"; s.problem = "USB 共享尚未全部停止，程序暂未退出：已取消管理员授权"; });
  await notice.getByText(/程序暂未退出/).waitFor();
  assert.equal(await share.isDisabled(), true);

  // Reopening after an interrupted exit must clean persistent bindings again.
  await page.reload();
  await notice.getByText(/正在清理上次遗留/).waitFor();
  await page.evaluate(() => { window.sessionTest.deferLocal = true; });
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await page.waitForFunction(() => !!window.sessionTest.finishLocal);
  await page.evaluate(() => window.sessionTest.finishCleanup());
  await notice.waitFor({ state: "detached" });
  await page.getByText("可共享", { exact: true }).waitFor();
  await page.evaluate(() => window.sessionTest.finishLocal());
  assert.equal(await page.getByText("已共享", { exact: true }).count(), 0, "late pre-cleanup state cannot restore sharing");
  assert.equal(await check.isChecked(), false);
  assert.equal(await page.evaluate(() => window.sessionTest.calls.share_devices || 0), 0, "reopening never shares automatically");
  await page.screenshot({ path: "test-results/sharing-reopened-820.png" });
  assert.deepEqual(errors, []);
  console.log("PASS: cleanup gates startup, cancellation retains real state, manual retry/share, closing blocks actions, reopen clears old shares and invalidates stale queries; 820px layout");
} finally { await browser.close(); }

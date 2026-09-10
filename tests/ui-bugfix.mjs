import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
const url = process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173";

async function fixture(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1180, height: 760 } });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("dialog", dialog => dialog.accept());
  await page.addInitScript((options) => {
    const relay = "tcp://183.230.36.171:11010";
    localStorage.setItem("usblink.autoStart", "false");
    if (options.corrupt) {
      localStorage.setItem("usblink.autoRefresh", "{broken");
      localStorage.setItem("usblink.autoStart", "undefined");
      localStorage.setItem("usblink.remoteDeviceNames", "{broken");
    }
    if (options.denyWrites) Storage.prototype.setItem = () => { throw new Error("QuotaExceededError"); };
    const phone = { busId: "3-2", vidPid: "18d1:4ee7", name: "Redmi K40", detail: "Android 设备", friendlyName: true, shared: false, attached: false };
    const peer = { name: "OFFICE-PC", ip: "10.126.126.2", online: true, latency: "5", tunnel: "udp" };
    const configured = !options.unpaired;
    const state = window.bugTest = {
      calls: {}, devices: [phone, { ...phone, busId: "3-3", name: "测试串口", shared: true }], attached: [],
      mesh: { configured, running: configured, localIp: configured ? "10.126.126.1" : null, networkName: configured ? "test-network" : null, relay, peerCount: configured ? 1 : 0, peers: configured ? [peer] : [], needsRepair: false, problem: null },
      environment: { computerName: "TEST-PC", usbipdInstalled: true, usbipInstalled: true, usbipSafe: true, easytierVersion: "2.6.4", usbipVersion: "0.9.8.0" },
      failRelay: false, failAutoStart: true, deferMesh: false, deferLocal: false, holdShare: false,
    };
    navigator.clipboard.writeText = async value => { state.clipboard = value; };
    window.__TAURI_INTERNALS__ = {
      async invoke(command, args = {}) {
        state.calls[command] = (state.calls[command] || 0) + 1;
        switch (command) {
          case "get_environment_status": return { ...state.environment, meshConfigured: state.mesh.configured };
          case "get_mesh_status": {
            const snapshot = structuredClone(state.mesh);
            if (args.includeCode) snapshot.pairingCode = "test-pairing-code-" + snapshot.relay;
            if (state.deferMesh && !args.includeCode) {
              state.deferMesh = false;
              return new Promise(resolve => { state.finishMesh = () => resolve(snapshot); });
            }
            return snapshot;
          }
          case "list_local_devices": {
            const snapshot = structuredClone(state.devices);
            if (state.deferLocal) {
              state.deferLocal = false;
              return new Promise(resolve => { state.finishLocal = () => resolve(snapshot); });
            }
            return snapshot;
          }
          case "list_remote_devices": return [phone];
          case "list_attached_devices": return structuredClone(state.attached);
          case "ensure_mesh_service_current": return false;
          case "ensure_usb_sharing_ready": return true;
          case "change_mesh_relay":
            if (state.failRelay) throw "已取消管理员授权";
            state.mesh.relay = args.relay;
            return structuredClone(state.mesh);
          case "set_auto_start":
            if (state.failAutoStart) throw "Windows 未能保存开机启动设置";
            return;
          case "leave_mesh":
            state.mesh = { ...state.mesh, configured: false, running: false, networkName: null, localIp: null, peers: [], peerCount: 0 };
            return;
          case "share_devices":
            if (state.holdShare) await new Promise((resolve, reject) => { state.finishShare = () => {
              state.devices[0].shared = true; reject("第二个设备共享失败");
            }; });
            else state.devices[0].shared = true;
            return;
          default: throw new Error("Unexpected command: " + command);
        }
      },
    };
  }, options);
  await page.goto(url);
  await page.getByRole("button", { name: options.unpaired ? "创建连接" : "共享所选设备", exact: true }).waitFor();
  await page.waitForFunction(() => window.bugTest.calls.get_environment_status > 0);
  return { page, context, errors };
}

async function check(name, options, test) {
  const fixtureState = await fixture(options);
  try {
    await test(fixtureState.page);
    assert.deepEqual(fixtureState.errors, [], "No uncaught browser exceptions");
    console.log("PASS: " + name);
  } finally { await fixtureState.context.close(); }
}

try {
  await check("corrupt preferences and full storage do not crash device queries", { corrupt: true, denyWrites: true }, async page => {
    await page.getByText("Redmi K40", { exact: true }).waitFor();
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).waitFor();
    assert.equal(await page.getByText(/远程设备列表暂时无法更新/).count(), 0);
  });

  await check("relay draft survives status refresh; invalid or cancelled save is not persisted", {}, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const input = page.getByRole("textbox", { name: "EasyTier 中继节点" });
    await input.fill("tcp://example.com:11010");
    await page.evaluate(() => { window.bugTest.mesh.localIp = "10.126.126.9"; window.dispatchEvent(new Event("focus")); });
    await page.getByText("10.126.126.9", { exact: true }).waitFor();
    assert.equal(await input.inputValue(), "tcp://example.com:11010");
    await input.fill("tcp://example.com:99999");
    await page.getByRole("button", { name: "保存", exact: true }).click();
    await page.getByText(/中继地址格式无效/).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.calls.change_mesh_relay || 0), 0);
    await input.fill("tcp://example.com:11010");
    await page.evaluate(() => { window.bugTest.failRelay = true; });
    await page.getByRole("button", { name: "保存", exact: true }).click();
    await page.getByText("已取消管理员授权", { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => localStorage.getItem("usblink.relay")), null);
    assert.equal(await input.inputValue(), "tcp://example.com:11010");
  });

  await check("relay changes invalidate the old pairing code", {}, async page => {
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("button", { name: "复制配对码", exact: true }).click();
    await page.getByRole("textbox", { name: "当前配对码" }).waitFor();
    const old = await page.getByRole("textbox", { name: "当前配对码" }).inputValue();
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByRole("textbox", { name: "EasyTier 中继节点" }).fill(" tcp://example.com:11010 ");
    await page.getByRole("button", { name: "保存", exact: true }).click();
    await page.getByText("中继地址已更新", { exact: true }).waitFor();
    await page.getByRole("button", { name: "连接", exact: true }).click();
    assert.equal(await page.getByRole("textbox", { name: "当前配对码" }).count(), 0);
    await page.getByRole("button", { name: "复制配对码", exact: true }).click();
    await page.getByRole("textbox", { name: "当前配对码" }).waitFor();
    const current = await page.getByRole("textbox", { name: "当前配对码" }).inputValue();
    assert.notEqual(current, old);
    assert.ok(current.endsWith("tcp://example.com:11010"));
    assert.equal(await page.evaluate(() => localStorage.getItem("usblink.relay")), "tcp://example.com:11010");
  });

  await check("failed auto-start does not alter persisted preferences", {}, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const toggle = page.getByRole("switch", { name: "开机启动 USBLink" });
    await toggle.click();
    await page.getByText("Windows 未能保存开机启动设置", { exact: true }).waitFor();
    assert.equal(await toggle.getAttribute("aria-checked"), "false");
    assert.equal(await page.evaluate(() => localStorage.getItem("usblink.autoStart")), "false");
    await page.evaluate(() => { window.bugTest.failAutoStart = false; });
    await toggle.click();
    await page.getByText("已设置开机启动", { exact: true }).waitFor();
    assert.equal(await toggle.getAttribute("aria-checked"), "true");
    assert.equal(await page.evaluate(() => localStorage.getItem("usblink.autoStart")), "true");
  });

  await check("late mesh response cannot undo leaving the network", {}, async page => {
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.evaluate(() => { window.bugTest.deferMesh = true; window.dispatchEvent(new Event("focus")); });
    await page.waitForFunction(() => !!window.bugTest.finishMesh);
    await page.getByRole("button", { name: "离开连接", exact: true }).click();
    await page.getByRole("button", { name: "创建连接", exact: true }).waitFor();
    await page.evaluate(async () => { window.bugTest.finishMesh(); await new Promise(resolve => setTimeout(resolve, 100)); });
    assert.equal(await page.getByRole("button", { name: "创建连接", exact: true }).count(), 1);
    assert.equal(await page.getByRole("button", { name: "离开连接", exact: true }).count(), 0);
  });

  await check("refresh cannot unlock a pending share; partial failure and late polls show actual state", {}, async page => {
    await page.getByRole("checkbox", { name: "选择 Redmi K40" }).click();
    await page.evaluate(() => { window.bugTest.holdShare = true; window.bugTest.deferLocal = true; });
    await page.getByRole("button", { name: "共享所选设备", exact: true }).click();
    await page.waitForFunction(() => !!window.bugTest.finishShare);
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.waitForFunction(() => !!window.bugTest.finishLocal);
    assert.equal(await page.getByRole("button", { name: "正在共享…", exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole("button", { name: "停止共享", exact: true }).isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishShare());
    await page.getByText("第二个设备共享失败", { exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelectorAll(".danger-action").length === 2);
    await page.evaluate(async () => { window.bugTest.finishLocal(); await new Promise(resolve => setTimeout(resolve, 100)); });
    assert.equal(await page.getByRole("button", { name: "停止共享", exact: true }).count(), 2);
    assert.equal(await page.getByRole("button", { name: "共享所选设备", exact: true }).isEnabled(), true);
  });

  await check("driver safety change marks retained mounts unconfirmed", {}, async page => {
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.evaluate(() => { window.bugTest.attached = [{ ...window.bugTest.devices[0], host: "10.126.126.2", port: 1 }]; window.dispatchEvent(new Event("focus")); });
    const imported = page.getByRole("region", { name: "已连接到本机的 USB" });
    await imported.getByText("已连接", { exact: true }).waitFor();
    await page.evaluate(() => { window.bugTest.environment.usbipSafe = false; window.dispatchEvent(new Event("focus")); });
    await imported.getByRole("heading", { name: /USB 连接状态待确认/ }).waitFor();
    assert.equal(await imported.getByText("Redmi K40", { exact: true }).count(), 1);
  });

  await check("unpaired relay preference survives focus polling", { unpaired: true }, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const input = page.getByRole("textbox", { name: "EasyTier 中继节点" });
    await input.fill("tcp://example.com:11010");
    await page.getByRole("button", { name: "保存", exact: true }).click();
    await page.getByText("默认中继地址已保存", { exact: true }).waitFor();
    await page.evaluate(async () => { window.dispatchEvent(new Event("focus")); await new Promise(resolve => setTimeout(resolve, 100)); });
    assert.equal(await input.inputValue(), "tcp://example.com:11010");
  });
} finally {
  await browser.close();
}

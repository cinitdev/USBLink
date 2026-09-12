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
      failMesh: false, failLocal: false,
    };
    navigator.clipboard.writeText = async value => { state.clipboard = value; };
    window.__TAURI_INTERNALS__ = {
      async invoke(command, args = {}) {
        state.calls[command] = (state.calls[command] || 0) + 1;
        switch (command) {
          case "get_sharing_session": return { phase: "ready", ready: true, problem: null };
          case "get_environment_status": return { ...state.environment, meshConfigured: state.mesh.configured };
          case "get_mesh_status": {
            if (state.failMesh) throw "模拟网络状态查询失败";
            const snapshot = structuredClone(state.mesh);
            if (args.includeCode) snapshot.pairingCode = "test-pairing-code-" + snapshot.relay;
            if (state.deferMesh && !args.includeCode) {
              state.deferMesh = false;
              return new Promise(resolve => { state.finishMesh = () => resolve(snapshot); });
            }
            return snapshot;
          }
          case "list_local_devices": {
            if (state.failLocal) throw "模拟本机 USB 状态读取失败";
            const snapshot = structuredClone(state.devices);
            if (state.deferLocal) {
              state.deferLocal = false;
              return new Promise(resolve => { state.finishLocal = () => resolve(snapshot); });
            }
            return snapshot;
          }
          case "list_remote_devices": return [phone];
          case "list_attached_devices": return structuredClone(state.attached);
          case "ensure_mesh_service_current":
            if (options.holdMeshCheck) return new Promise((resolve, reject) => {
              state.finishMeshCheck = (success) => success ? resolve(false) : reject("已取消管理员授权");
            });
            return false;
          case "ensure_usb_sharing_ready":
            if (options.holdSharingCheck) return new Promise((resolve, reject) => {
              state.finishSharingCheck = (success) => success ? resolve(true) : reject("已取消管理员授权");
            });
            return true;
          case "repair_mesh": return structuredClone(state.mesh);
          case "repair_usb_sharing": return;
          case "join_mesh":
            state.mesh = { ...state.mesh, configured: true, running: true, networkName: "test-network", localIp: "10.126.126.1", peers: [peer], peerCount: 1 };
            return structuredClone(state.mesh);
          case "open_dependency_download": return;
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
  await check("cancelled service check stays failed, blocks USB work and supports manual repair", { holdMeshCheck: true }, async page => {
    await page.waitForFunction(() => !!window.bugTest.finishMeshCheck);
    assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40" }).isDisabled(), true);
    await page.getByRole("button", { name: "连接", exact: true }).click();
    assert.equal(await page.getByRole("button", { name: "离开连接", exact: true }).isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishMeshCheck(false));
    await page.locator(".mesh-summary").getByText(/EasyTier 服务配置检查失败/).waitFor();
    const meshReads = await page.evaluate(() => window.bugTest.calls.get_mesh_status);
    await page.evaluate(() => window.dispatchEvent(new Event("focus")));
    await page.waitForFunction(reads => window.bugTest.calls.get_mesh_status > reads + 1, meshReads);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_mesh_service_current), 1);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_usb_sharing_ready || 0), 0);
    assert.equal(await page.getByRole("button", { name: "连接所选设备", exact: true }).isDisabled(), true);
    await page.getByRole("button", { name: "修复连接", exact: true }).click();
    await page.locator(".mesh-summary").getByText("对方电脑在线", { exact: true }).waitFor();
    await page.waitForFunction(() => window.bugTest.calls.ensure_usb_sharing_ready === 1);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_mesh_service_current), 1);
  });

  await check("automatic sharing check owns the operation lock and cancellation can be repaired", { holdSharingCheck: true }, async page => {
    await page.waitForFunction(() => !!window.bugTest.finishSharingCheck);
    assert.equal(await page.getByRole("button", { name: "修复共享", exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40" }).isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishSharingCheck(false));
    await page.getByRole("status").getByText(/USB 共享访问规则检查失败/).waitFor();
    const meshReads = await page.evaluate(() => window.bugTest.calls.get_mesh_status);
    await page.evaluate(() => window.dispatchEvent(new Event("focus")));
    await page.waitForFunction(reads => window.bugTest.calls.get_mesh_status > reads + 1, meshReads);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_usb_sharing_ready), 1);
    await page.getByRole("button", { name: "修复共享", exact: true }).click();
    await page.getByText("USB 共享访问已修复", { exact: true }).waitFor();
    assert.equal(await page.getByRole("status").count(), 0);
  });

  await check("local query failure disables stale sharing rows until a successful refresh", {}, async page => {
    await page.getByRole("checkbox", { name: "选择 Redmi K40" }).click();
    await page.evaluate(() => { window.bugTest.failLocal = true; });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByRole("status").getByText(/本机设备状态待确认/).waitFor();
    assert.equal(await page.getByText("状态待确认", { exact: true }).count(), 2);
    assert.equal(await page.getByRole("button", { name: "共享所选设备", exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole("button", { name: "停止共享", exact: true }).isDisabled(), true);
    assert.equal(await page.evaluate(() => window.bugTest.calls.share_devices || 0), 0);
    await page.evaluate(() => { window.bugTest.failLocal = false; });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByRole("status").waitFor({ state: "detached" });
    await page.getByRole("checkbox", { name: "选择 Redmi K40" }).click();
    assert.equal(await page.getByRole("button", { name: "共享所选设备", exact: true }).isEnabled(), true);
  });

  await check("failed mesh query marks remembered peers offline and clears selections while retaining actual USB mounts", {}, async page => {
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("checkbox", { name: "选择 Redmi K40" }).click();
    await page.evaluate(() => {
      window.bugTest.attached = [{ ...window.bugTest.devices[1], host: "10.126.126.2", port: 1 }];
      window.bugTest.failMesh = true;
    });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    const imported = page.getByRole("region", { name: "已连接到本机的 USB" });
    await imported.getByText("对方状态待确认", { exact: true }).waitFor();
    await page.locator(".peer-picker").getByText("OFFICE-PC", { exact: true }).waitFor();
    await imported.getByText("测试串口", { exact: true }).waitFor();
    assert.equal(await page.getByRole("button", { name: "连接所选设备", exact: true }).isDisabled(), true);
    assert.equal(await page.evaluate(() => window.bugTest.calls.attach_devices || 0), 0);
    await page.evaluate(() => { window.bugTest.failMesh = false; window.dispatchEvent(new Event("focus")); });
    await imported.getByText("已连接", { exact: true }).waitFor();
    assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40" }).isChecked(), false);
    assert.equal(await imported.getByText("已连接", { exact: true }).count(), 1);
  });

  await check("leaving and joining the same network resets automatic service and access checks", {}, async page => {
    await page.waitForFunction(() => window.bugTest.calls.ensure_usb_sharing_ready === 1);
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("button", { name: "离开连接", exact: true }).click();
    await page.getByRole("textbox", { name: "配对码", exact: true }).fill("test-code");
    await page.getByRole("button", { name: "加入连接", exact: true }).click();
    await page.waitForFunction(() => window.bugTest.calls.ensure_usb_sharing_ready === 2);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_mesh_service_current), 2);
  });

  await check("viewing an installed component does not claim a new installation", {}, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.locator(".dependency").filter({ hasText: "usbipd-win" }).getByRole("button", { name: "查看" }).click();
    await page.waitForFunction(() => window.bugTest.calls.open_dependency_download === 1);
    assert.equal(await page.getByText(/已检测到 usbipd-win/).count(), 0);
    assert.equal(await page.getByText("等待安装完成…", { exact: true }).count(), 0);
  });

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

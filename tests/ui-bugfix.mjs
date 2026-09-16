import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { mkdir } from "node:fs/promises";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || "msedge", headless: true });
const url = process.env.USBLINK_TEST_URL || "http://127.0.0.1:5173";
await mkdir("test-results/ui-audit", { recursive: true });

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
          case "get_environment_status":
            if (state.deferEnvironment) { state.deferEnvironment = false; await new Promise(resolve => { state.finishEnvironment = resolve; }); }
            return { ...state.environment, meshConfigured: state.mesh.configured };
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
            if (options.holdMeshCheck && !state.repairedMesh) return new Promise((resolve, reject) => {
              state.finishMeshCheck = (success) => success ? resolve(false) : reject("已取消管理员授权");
            });
            return false;
          case "ensure_usb_sharing_ready":
            if (options.holdSharingCheck) return new Promise((resolve, reject) => {
              state.finishSharingCheck = (success) => success ? resolve(true) : reject("已取消管理员授权");
            });
            return true;
          case "repair_mesh": state.repairedMesh = true; return structuredClone(state.mesh);
          case "repair_usb_sharing": return;
          case "join_mesh":
            if (state.holdJoin) await new Promise((resolve, reject) => { state.finishJoin = success => success ? resolve() : reject("配对码无效，请检查是否复制完整"); });
            state.mesh = { ...state.mesh, configured: true, running: true, networkName: "test-network", localIp: "10.126.126.1", peers: [peer], peerCount: 1 };
            return structuredClone(state.mesh);
          case "create_mesh":
            state.createRelay = args.relay;
            state.mesh = { ...state.mesh, relay: args.relay, configured: true, running: true, networkName: "created-network", localIp: "10.126.126.1", peers: [], peerCount: 0 };
            return structuredClone(state.mesh);
          case "open_dependency_download":
            if (state.holdDownload) await new Promise((resolve, reject) => { state.finishDownload = success => success ? resolve() : reject("无法打开安装页面，请稍后重试"); });
            return;
          case "change_mesh_relay":
            if (state.holdRelay) await new Promise(resolve => { state.finishRelay = resolve; });
            if (state.failRelay) throw "已取消管理员授权";
            state.mesh.relay = args.relay;
            return structuredClone(state.mesh);
          case "set_auto_start":
            if (state.holdAutoStart) await new Promise(resolve => { state.finishAutoStart = resolve; });
            if (state.failAutoStart) throw "Windows 未能保存开机启动设置";
            return;
          case "leave_mesh":
            state.mesh = { ...state.mesh, configured: false, running: false, networkName: null, localIp: null, peers: [], peerCount: 0 };
            return;
          case "share_devices":
            state.sharedIds = args.busIds;
            if (state.holdShare) await new Promise((resolve, reject) => { state.finishShare = () => {
              state.devices[0].shared = true; reject("第二个设备共享失败");
            }; });
            else state.devices.forEach(device => { if (args.busIds.includes(device.busId)) device.shared = true; });
            return;
          case "unshare_device":
            if (state.holdUnshare) await new Promise(resolve => { state.finishUnshare = resolve; });
            state.devices.find(device => device.busId === args.busId).shared = false;
            return;
          case "attach_devices":
            state.attachArgs = args;
            if (state.holdAttach) await new Promise(resolve => { state.finishAttach = resolve; });
            state.attached.push({ ...phone, host: args.host, port: 3, attached: true });
            return structuredClone(state.attached);
          case "detach_device":
            state.detachTarget = args.target;
            if (state.holdDetach) await new Promise(resolve => { state.finishDetach = resolve; });
            state.attached = state.attached.filter(device => device.port !== args.target.port);
            return structuredClone(state.attached);
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
  if (process.env.USBLINK_TEST_FILTER && !name.includes(process.env.USBLINK_TEST_FILTER)) return;
  const fixtureState = await fixture(options);
  try {
    await test(fixtureState.page);
    assert.deepEqual(fixtureState.errors, [], "No uncaught browser exceptions");
    console.log("PASS: " + name);
  } finally { await fixtureState.context.close(); }
}

try {
  await check("row selection has a mixed state, preserves category, and skips shared devices", {}, async page => {
    await page.evaluate(() => window.bugTest.devices.push({ ...window.bugTest.devices[0], busId: "4-1", name: "Pixel 9" }));
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByRole("tab", { name: "手机 2", exact: true }).click();
    await page.getByText("Redmi K40", { exact: true }).click();
    assert.equal(await page.getByRole("checkbox", { name: "全选", exact: true }).getAttribute("aria-checked"), "mixed");
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByRole("button", { name: "设备", exact: true }).click();
    assert.equal(await page.getByRole("tab", { name: "手机 2", exact: true }).getAttribute("aria-selected"), "true");
    assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40", exact: true }).isChecked(), true);
    await page.getByRole("button", { name: "清除选择", exact: true }).click();
    await page.getByRole("button", { name: "共享 Pixel 9", exact: true }).click();
    await page.getByRole("button", { name: "停止共享", exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => window.bugTest.sharedIds), ["4-1"]);
    assert.equal(await page.getByRole("checkbox", { name: "选择 Pixel 9", exact: true }).isDisabled(), true);
    await page.getByRole("tab", { name: "全部 3", exact: true }).click();
    await page.getByRole("checkbox", { name: "全选", exact: true }).click();
    await page.getByRole("button", { name: "共享所选设备", exact: true }).click();
    await page.waitForFunction(() => window.bugTest.calls.share_devices === 2);
    assert.deepEqual(await page.evaluate(() => window.bugTest.sharedIds), ["3-2"]);
    await page.getByRole("checkbox", { name: "全选", exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelector('[aria-label="全选"]').disabled);
    await page.screenshot({ path: "test-results/ui-audit/04-shared-selection.png" });
    await page.evaluate(() => { window.bugTest.holdUnshare = true; });
    const row = page.locator(".device-row").filter({ hasText: "Pixel 9" });
    await row.getByRole("button", { name: "停止共享", exact: true }).click();
    await row.getByRole("button", { name: "正在停止…", exact: true }).waitFor();
    assert.equal(await row.getByRole("checkbox").isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishUnshare());
    await row.getByRole("button", { name: "共享 Pixel 9", exact: true }).waitFor();
  });

  await check("remote row connects once and disconnects only its confirmed mount", {}, async page => {
    await page.evaluate(() => {
      const state = window.bugTest;
      state.attached = [{ ...state.devices[1], host: "10.126.126.7", port: 9 }];
      state.holdAttach = true; state.holdDetach = true;
      window.dispatchEvent(new Event("focus"));
    });
    await page.getByRole("button", { name: "连接", exact: true }).click();
    const remote = page.getByRole("region", { name: "远程可用设备", exact: true });
    const row = remote.locator(".device-row").filter({ hasText: "Redmi K40" });
    await row.getByRole("button", { name: "连接 Redmi K40", exact: true }).click();
    await page.waitForFunction(() => !!window.bugTest.finishAttach);
    assert.equal(await row.getByRole("button").last().isDisabled(), true);
    assert.equal(await row.count(), 1);
    await page.screenshot({ path: "test-results/ui-audit/05-row-connect-pending.png" });
    await page.evaluate(() => window.bugTest.finishAttach());
    await row.getByText("已连接", { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.calls.attach_devices), 1);
    assert.deepEqual(await page.evaluate(() => window.bugTest.attachArgs), { host: "10.126.126.2", busIds: ["3-2"], expectedVidPids: { "3-2": "18d1:4ee7" } });
    await row.getByRole("button", { name: "断开 Redmi K40", exact: true }).click();
    await page.waitForFunction(() => !!window.bugTest.finishDetach);
    assert.equal(await row.getByRole("button").last().isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishDetach());
    await row.getByRole("button", { name: "连接 Redmi K40", exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => window.bugTest.detachTarget), { host: "10.126.126.2", busId: "3-2", vidPid: "18d1:4ee7", port: 3 });
    assert.equal(await page.evaluate(() => window.bugTest.calls.detach_all_devices || 0), 0);
    assert.deepEqual(await page.evaluate(() => window.bugTest.attached.map(device => device.port)), [9]);
  });

  await check("peer picker supports keyboard, outside dismissal, and page changes", {}, async page => {
    await page.evaluate(() => { window.bugTest.mesh.peers.push({ name: "LAPTOP", ip: "10.126.126.3", online: true }); window.dispatchEvent(new Event("focus")); });
    const picker = page.locator(".peer-picker");
    await picker.press("ArrowDown");
    await page.getByRole("option", { name: /LAPTOP/ }).waitFor();
    await page.getByRole("option", { name: /OFFICE-PC/ }).press("End");
    assert.equal(await page.getByRole("option", { name: /LAPTOP/ }).evaluate(element => element === document.activeElement), true);
    await page.getByRole("option", { name: /LAPTOP/ }).press("Enter");
    await picker.getByText("LAPTOP", { exact: true }).waitFor();
    assert.equal(await picker.getAttribute("aria-expanded"), "false");
    await picker.click();
    assert.equal(await page.getByRole("option", { name: /LAPTOP/ }).evaluate(element => element === document.activeElement), true, "Reopening focuses the selected computer");
    await page.getByRole("option", { name: /LAPTOP/ }).press("Escape");
    assert.equal(await picker.evaluate(element => element === document.activeElement), true);
    await picker.click();
    await page.getByRole("heading", { name: "设备", exact: true }).click();
    assert.equal(await page.getByRole("listbox").count(), 0);
    await picker.click();
    await page.getByRole("option", { name: /LAPTOP/ }).press("Tab");
    assert.equal(await page.getByRole("listbox").count(), 0);
    assert.notEqual(await page.evaluate(() => document.activeElement.tagName), "BODY");
    await picker.click();
    await page.getByRole("button", { name: "连接", exact: true }).click();
    assert.equal(await page.getByRole("listbox").count(), 0);
    assert.equal(await page.getByRole("button", { name: "连接", exact: true }).getAttribute("aria-current"), "page");
  });

  await check("Enter joins once, shows a persistent field error, and supports a manual correction", { unpaired: true }, async page => {
    const input = page.getByRole("textbox", { name: "配对码", exact: true });
    await page.evaluate(() => { window.bugTest.holdJoin = true; });
    await input.fill("incomplete-demo-code");
    await input.press("Enter");
    await page.waitForFunction(() => !!window.bugTest.finishJoin);
    assert.equal(await input.isDisabled(), true);
    assert.equal(await page.getByRole("button", { name: "加入连接", exact: true }).isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishJoin(false));
    await page.locator("#join-problem").waitFor();
    assert.equal(await input.inputValue(), "incomplete-demo-code");
    assert.equal(await input.getAttribute("aria-invalid"), "true");
    await page.screenshot({ path: "test-results/ui-audit/06-pairing-error.png" });
    await page.evaluate(() => { window.bugTest.holdJoin = false; });
    await input.fill("corrected-demo-code");
    await input.press("Enter");
    await page.getByRole("button", { name: "离开连接", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.calls.join_mesh), 2);
  });

  await check("relay form prevents stale edits during save and offers undo and Enter submission", {}, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const input = page.getByRole("textbox", { name: "EasyTier 中继节点" });
    const save = page.getByRole("button", { name: "保存", exact: true });
    assert.equal(await save.isDisabled(), true);
    await input.fill("tcp://example.com:11010");
    await page.getByRole("button", { name: "撤销修改", exact: true }).click();
    assert.equal(await input.inputValue(), "tcp://183.230.36.171:11010");
    await page.evaluate(() => { window.bugTest.holdRelay = true; });
    await input.fill("tcp://example.com:11010");
    await input.press("Enter");
    await page.waitForFunction(() => !!window.bugTest.finishRelay);
    assert.equal(await input.isDisabled(), true);
    assert.equal(await page.getByRole("button", { name: "正在保存…", exact: true }).isDisabled(), true);
    assert.equal(await page.evaluate(() => localStorage.getItem("usblink.relay")), null);
    await page.evaluate(() => window.bugTest.finishRelay());
    await page.getByText("当前地址已保存", { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.calls.change_mesh_relay), 1);
    assert.equal(await input.isEnabled(), true);
  });

  await check("component refresh waits for the existing query instead of finishing early", {}, async page => {
    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.evaluate(() => { window.bugTest.deferEnvironment = true; window.dispatchEvent(new Event("focus")); });
    await page.waitForFunction(() => !!window.bugTest.finishEnvironment);
    const reads = await page.evaluate(() => window.bugTest.calls.get_environment_status);
    const refresh = page.getByRole("button", { name: "刷新", exact: true });
    await refresh.click();
    assert.equal(await refresh.isDisabled(), true);
    assert.equal(await refresh.getAttribute("aria-busy"), "true");
    assert.equal(await page.evaluate(() => window.bugTest.calls.get_environment_status), reads);
    await page.evaluate(() => window.bugTest.finishEnvironment());
    await page.getByText("组件状态已刷新", { exact: true }).waitFor();
    assert.equal(await refresh.isEnabled(), true);
  });

  await check("download failures clear pending state; both drivers can be watched and dismissed", {}, async page => {
    await page.evaluate(() => { const s = window.bugTest; s.environment.usbipdInstalled = false; s.environment.usbipInstalled = false; s.holdDownload = true; window.dispatchEvent(new Event("focus")); });
    await page.getByRole("button", { name: "设置", exact: true }).click();
    const server = page.locator(".dependency").filter({ hasText: "usbipd-win" });
    const client = page.locator(".dependency").filter({ hasText: "usbip-win2" });
    await server.getByRole("button", { name: "安装", exact: true }).click();
    await page.waitForFunction(() => !!window.bugTest.finishDownload);
    assert.equal(await server.getByRole("button", { name: "正在打开…", exact: true }).isDisabled(), true);
    await page.evaluate(() => window.bugTest.finishDownload(false));
    await page.getByRole("alert").getByText("无法打开安装页面，请稍后重试", { exact: true }).waitFor();
    assert.equal(await page.getByText("等待安装完成…", { exact: true }).count(), 0);
    await page.getByRole("button", { name: "关闭通知", exact: true }).click();
    await page.evaluate(() => { window.bugTest.holdDownload = false; });
    await server.getByRole("button", { name: "安装", exact: true }).click();
    await server.getByText("等待安装完成…", { exact: true }).waitFor();
    await client.getByRole("button", { name: "安装", exact: true }).click();
    await client.getByText("等待安装完成…", { exact: true }).waitFor();
    assert.equal(await page.getByText("等待安装完成…", { exact: true }).count(), 2);
    await server.getByRole("button", { name: "暂不安装", exact: true }).click();
    await server.getByText("尚未安装", { exact: true }).waitFor();
    await page.evaluate(() => { window.bugTest.environment.usbipInstalled = true; window.dispatchEvent(new Event("focus")); });
    await client.getByRole("button", { name: "查看", exact: true }).waitFor();
  });

  await check("leave confirmation defaults to cancel, Escape restores focus, pairing code can be hidden", {}, async page => {
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("button", { name: "复制配对码", exact: true }).click();
    await page.getByRole("button", { name: "隐藏配对码", exact: true }).click();
    assert.equal(await page.getByRole("textbox", { name: "当前配对码", exact: true }).count(), 0);
    const leave = page.getByRole("button", { name: "离开连接", exact: true });
    await leave.click();
    const cancel = page.getByRole("button", { name: "取消", exact: true });
    assert.equal(await cancel.evaluate(element => element === document.activeElement), true);
    await cancel.press("Escape");
    assert.equal(await page.getByRole("dialog").count(), 0);
    assert.equal(await leave.evaluate(element => element === document.activeElement), true);
    assert.equal(await page.evaluate(() => window.bugTest.calls.leave_mesh || 0), 0);
    await leave.click();
    await page.screenshot({ path: "test-results/ui-audit/07-leave-confirmation.png" });
    await page.getByRole("button", { name: "确认离开", exact: true }).click();
    await page.getByRole("button", { name: "创建连接", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.calls.leave_mesh), 1);
  });

  await check("small dark windows keep all pages and unpaired retained mounts reachable", {}, async page => {
    await page.setViewportSize({ width: 820, height: 600 });
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    for (const [route, name] of [["devices", "设备"], ["connections", "连接"], ["settings", "设置"]]) {
      await page.getByRole("button", { name, exact: true }).click();
      await page.screenshot({ path: `test-results/ui-audit/08-${route}-dark-820.png` });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      const clipped = await page.locator("main").evaluate(element => element.scrollWidth > element.clientWidth + 1);
      assert.equal(clipped, false, route + " workspace must not overflow horizontally");
    }
    await page.evaluate(() => {
      const s = window.bugTest;
      s.attached = Array.from({ length: 6 }, (_, i) => ({ ...s.devices[0], name: "已挂载手机 " + (i + 1), host: "10.126.126.2", port: i + 1 }));
      window.dispatchEvent(new Event("focus"));
    });
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByRole("region", { name: "已连接到本机的 USB" }).getByText("已挂载手机 6", { exact: true }).waitFor();
    await page.getByRole("button", { name: "离开连接", exact: true }).click();
    await page.getByRole("button", { name: "确认离开", exact: true }).click();
    const code = page.getByRole("textbox", { name: "配对码", exact: true });
    await code.click(); // Must scroll into view below all six mounts.
    await code.fill("demo-code");
    const box = await code.boundingBox();
    await page.screenshot({ path: "test-results/ui-audit/09-unpaired-mounts-dark-820.png" });
    assert.ok(box.y >= 0 && box.y + box.height <= 600, JSON.stringify(box));
  });

  await check("device categories scope selection and sharing, and follow refreshed USB descriptions", {}, async page => {
    const tab = name => page.getByRole("tab", { name, exact: true });
    const share = page.getByRole("button", { name: "共享所选设备", exact: true });
    await tab("全部 2").waitFor();
    await page.getByRole("checkbox", { name: "全选", exact: true }).click();
    await tab("手机 1").click();
    assert.equal(await page.locator(".device-row").count(), 1);
    assert.equal(await page.getByRole("checkbox", { name: "选择 Redmi K40" }).isChecked(), false);
    assert.equal(await share.isDisabled(), true);
    await page.getByRole("checkbox", { name: "全选", exact: true }).click();
    await tab("其他设备 1").click();
    assert.equal(await page.getByRole("checkbox", { name: "选择 测试串口" }).isChecked(), false);
    assert.equal(await page.getByText("Redmi K40", { exact: true }).count(), 0);
    await page.getByRole("button", { name: "停止共享", exact: true }).waitFor();
    assert.equal(await page.getByRole("checkbox", { name: "全选", exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole("checkbox", { name: "选择 测试串口" }).isDisabled(), true);
    await tab("其他设备 1").press("ArrowLeft");
    assert.equal(await tab("手机 1").getAttribute("aria-selected"), "true");
    assert.equal(await share.isDisabled(), true);
    await page.getByRole("checkbox", { name: "全选", exact: true }).click();
    await share.click();
    await page.getByText("已共享 1 个 USB 设备", { exact: true }).waitFor();
    assert.deepEqual(await page.evaluate(() => window.bugTest.sharedIds), ["3-2"]);
    await page.getByRole("button", { name: "停止共享", exact: true }).waitFor();
    // A refreshed name can move a shared row into another category.
    assert.equal(await page.getByRole("checkbox", { name: "全选", exact: true }).isDisabled(), true);
    await page.evaluate(() => { window.bugTest.devices[0].name = "USB 摄像头"; });
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await page.getByText("暂未识别到手机", { exact: true }).waitFor();
    assert.equal(await tab("手机 0").getAttribute("aria-selected"), "true");
    assert.equal(await share.isDisabled(), true);
    await tab("手机 0").press("End");
    assert.equal(await tab("其他设备 2").getAttribute("aria-selected"), "true");
    assert.equal(await page.getByRole("checkbox", { name: "选择 USB 摄像头" }).isChecked(), false);
    await tab("其他设备 2").press("Home");
    assert.equal(await tab("全部 2").getAttribute("aria-selected"), "true");
    assert.equal(await page.evaluate(() => window.bugTest.calls.share_devices), 1);
  });

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
    await page.locator(".mesh-summary").getByText("对方 USBLink 在线", { exact: true }).waitFor();
    await page.waitForFunction(() => window.bugTest.calls.ensure_usb_sharing_ready === 1);
    assert.equal(await page.evaluate(() => window.bugTest.calls.ensure_mesh_service_current), 2, "explicit repair checks the application's session access rule again");
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
    assert.equal(await page.locator(".usb-status-warning").count(), 0);
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
    await page.locator(".usb-status-warning").waitFor({ state: "detached" });
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
    await page.getByRole("button", { name: "确认离开", exact: true }).click();
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
    await page.getByRole("button", { name: "确认离开", exact: true }).click();
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
    assert.equal(await page.getByRole("button", { name: "共享所选设备", exact: true }).isDisabled(), true, "Shared rows are no longer eligible for resubmission");
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
    await input.fill("tcp://unsaved.example.com:11010");
    await page.getByRole("button", { name: "连接", exact: true }).click();
    await page.getByRole("button", { name: "创建连接", exact: true }).click();
    await page.getByRole("button", { name: "离开连接", exact: true }).waitFor();
    assert.equal(await page.evaluate(() => window.bugTest.createRelay), "tcp://example.com:11010", "Only saved relay settings are applied");
  });
} finally {
  await browser.close();
}

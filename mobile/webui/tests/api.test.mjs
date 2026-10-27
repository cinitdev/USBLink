import test from 'node:test';
import assert from 'node:assert/strict';
import { makeCommand, hasBridge, callNative, createClient } from '../src/api.mjs';

test('untrusted Unicode input reaches shell only as base64', () => {
  const payload = { code: "手机'; $(touch /data/fail) `id`\n\"网络" };
  const command = makeCommand('join-mesh', payload);
  const match = command.match(/^\/data\/adb\/modules\/usblink-mobile\/bin\/usblinkctl join-mesh '([A-Za-z0-9+/=]+)'$/);
  assert.ok(match);
  assert.deepEqual(JSON.parse(Buffer.from(match[1], 'base64').toString('utf8')), payload);
  assert.throws(() => makeCommand('status; reboot', {}), /不支持/);
  assert.throws(() => makeCommand('join-mesh', { code: 'a'.repeat(9000) }), /过长/);
});

test('ordinary browser cannot control service or pretend success', async () => {
  assert.equal(hasBridge({}), false);
  const client = createClient({ scope: {}, disconnectPreview: true });
  assert.equal(client.available, false);
  await assert.rejects(client.request('status'), /普通浏览器无法控制/);
  await assert.rejects(createClient({ scope: {}, historyPreview: true }).request('history'), /普通浏览器无法控制/);
  await assert.rejects(createClient({ scope: {}, networkPreview: 'degraded' }).request('status'), /普通浏览器无法控制/);
});

test('degraded network observations are distinct from active sharing and cannot undo explicit off', async () => {
  const client = createClient({ preview: true, usbPreview: true, networkPreview: 'degraded' });
  await client.request('create-mesh');
  let status = await client.request('set-sharing', { enabled: true });
  assert.equal(status.sharing.active, true);
  assert.equal(status.appSession.ready, true);
  assert.equal(status.mesh.statusDegraded, true);
  assert.equal(status.mesh.running, false, 'unconfirmed relay cannot be reported connected');
  assert.deepEqual(status.mesh.peers, []);
  assert.match(status.mesh.problem, /共享保持运行/);
  status = await client.request('set-sharing', { enabled: false });
  assert.equal(status.sharing.active, false);
  assert.equal(status.mesh.paused, true);
  assert.equal(status.mesh.statusDegraded, false);
});

test('history pages and clear preserve sharing and reject stale cursors', async () => {
  const client = createClient({ preview: true, usbPreview: true, historyPreview: true });
  await client.request('create-mesh');
  let status = await client.request('set-sharing', { enabled: true });
  assert.equal(status.history.entries.length, 20);
  assert.equal(status.history.count, 25);
  const epoch = status.history.epoch;
  const older = await client.request('history', { before: status.history.entries.at(-1).id, epoch });
  assert.equal(older.entries.length, 5); assert.equal(older.hasMore, false);
  status = await client.request('clear-history');
  assert.equal(status.sharing.active, true); assert.equal(status.mesh.running, true);
  assert.equal(status.history.count, 0); assert.deepEqual(status.history.entries, []);
  await assert.rejects(client.request('history', { before: 5, epoch }), /历史记录已更新/);
});

test('Root preference persists across sharing and cannot fake unsupported ROM success', async () => {
  const client = createClient({ preview: true, usbPreview: true });
  assert.equal((await client.request('status')).adbRoot.enabled, true);
  let status = await client.request('set-adb-root', { enabled: false });
  assert.equal(status.adbRoot.active, false);
  status = await client.request('create-mesh');
  assert.equal(status.mesh.running, false, 'Root preference does not connect the mesh');
  await client.request('set-sharing', { enabled: true });
  status = await client.request('set-sharing', { enabled: false });
  assert.equal(status.adbRoot.enabled, false);
  status = await client.request('set-adb-root', { enabled: true });
  assert.equal(status.adbRoot.active, true);
  const unsupported = createClient({ preview: true, rootPreview: 'unsupported' });
  status = await unsupported.request('set-adb-root', { enabled: true });
  assert.equal(status.adbRoot.enabled, true); assert.equal(status.adbRoot.active, false);
  assert.equal(status.adbRoot.state, 'unsupported');
  await assert.rejects(createClient({}).request('set-adb-root', { enabled: true }), /普通浏览器无法控制/);
  await assert.rejects(client.request('set-adb-root', { enabled: 'true' }), /开关值无效/);
});

test('native callback uses official errno/stdout contract and cleans up', async () => {
  const scope = { ksu: { exec(command, options, callback) {
    assert.equal(options, '{}');
    assert.match(command, /usblink-mobile/);
    queueMicrotask(() => scope[callback](0, JSON.stringify({ ok: true, data: { value: 1 } }), ''));
  } } };
  assert.deepEqual(await callNative('status', {}, scope), { value: 1 });
  assert.deepEqual(Object.keys(scope), ['ksu']);
});

test('KsuWebUI host uses the shared three-argument bridge without retrying a denied operation', async () => {
  let calls = 0;
  // Models the Java-injected KsuWebUI interface, which also exposes other exec overloads.
  const scope = { ksu: { exec(...args) {
    calls++;
    assert.equal(args.length, 3, 'Never fall back to synchronous exec or resubmit a mutation');
    const [command, options, callback] = args;
    assert.equal(options, '{}');
    assert.match(command, /usblinkctl set-sharing '[A-Za-z0-9+/=]+'$/);
    queueMicrotask(() => scope[callback](1, '', 'su: denied PRIVATE_SENTINEL'));
  } } };
  assert.equal(hasBridge(scope), true);
  await assert.rejects(callNative('set-sharing', { enabled: true }, scope), error => {
    assert.match(error.message, /KsuWebUI.*授权/);
    assert.doesNotMatch(error.message, /PRIVATE_SENTINEL/);
    return true;
  });
  assert.equal(calls, 1);
  assert.deepEqual(Object.keys(scope), ['ksu']);
});

test('backend failures are surfaced without raw shell stderr or invalid JSON', async () => {
  const scope = { ksu: { exec(command, options, callback) { queueMicrotask(() => scope[callback](1, JSON.stringify({ ok: false, error: '未开启共享' }), 'private shell output')); } } };
  await assert.rejects(callNative('set-sharing', { enabled: true }, scope), /未开启共享/);
  scope.ksu.exec = (command, options, callback) => queueMicrotask(() => scope[callback](0, 'malformed secret output', ''));
  await assert.rejects(callNative('status', {}, scope), /无法识别/);
});

test('late native callback after timeout does not resolve an unconfirmed operation', async () => {
  let callback;
  const scope = { ksu: { exec(command, options, name) { callback = name; } } };
  await assert.rejects(callNative('set-sharing', { enabled: false }, scope, 5), /操作结果尚未确认/);
  assert.equal(typeof scope[callback], 'function');
  scope[callback](0, '{"ok":true,"data":{}}', '');
  assert.equal(scope[callback], undefined);
});

test('explicit demo uses TCP mode, resets intent on leave, and revokes on stop', async () => {
  const client = createClient({ preview: true, scope: {} });
  let status = await client.request('set-sharing', { enabled: true });
  assert.equal(status.sharing.enabled, true);
  assert.equal(status.sharing.active, false);
  assert.equal(status.sharing.state, 'waiting_network');
  status = await client.request('create-mesh');
  assert.equal(status.sharing.active, true);
  assert.equal(status.debug.mode, 'system-tcp');
  assert.equal(status.debug.connectAddress, '10.126.126.1:3242');
  status = await client.request('set-sharing', { enabled: false });
  assert.equal(status.sharing.active, false);
  assert.equal(status.debug.connectAddress, '');
  assert.equal(status.debug.enabled, false);
  assert.equal(status.mesh.running, false);
  assert.equal(status.mesh.paused, true);
  assert.equal(status.mesh.configured, true);
  assert.equal(status.appSession.ready, false);
  await assert.rejects(client.request('set-pairing', { port: -1 }), /不支持/);
  await client.request('set-sharing', { enabled: true });
  status = await client.request('leave-mesh');
  assert.equal(status.sharing.enabled, false);
});

test('saved preview network stays offline until sharing, retaining pairing across toggles', async () => {
  const client = createClient({ preview: true, usbPreview: true, scope: {} });
  let status = await client.request('create-mesh');
  const code = (await client.request('pairing-code')).code;
  assert.equal(status.mesh.configured, true);
  assert.equal(status.mesh.running, false);
  assert.equal(status.mesh.paused, true);
  status = await client.request('set-sharing', { enabled: true });
  assert.equal(status.sharing.active, true);
  assert.equal(status.mesh.running, true);
  assert.equal(status.mesh.paused, false);
  status = await client.request('set-sharing', { enabled: false });
  assert.equal(status.mesh.localIp, '');
  assert.equal(status.mesh.running, false);
  assert.equal((await client.request('pairing-code')).code, code);
  status = await client.request('set-relay', { relay: 'tcp://example.org:11010' });
  assert.equal(status.mesh.running, false);
  status = await client.request('set-sharing', { enabled: true });
  assert.equal(status.mesh.running, true);
  assert.equal((await client.request('pairing-code')).code, code);
});

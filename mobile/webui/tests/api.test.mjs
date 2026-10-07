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
  const client = createClient({ scope: {} });
  assert.equal(client.available, false);
  await assert.rejects(client.request('status'), /普通浏览器无法控制/);
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
  await assert.rejects(client.request('set-pairing', { port: -1 }), /不支持/);
  await client.request('set-sharing', { enabled: true });
  status = await client.request('leave-mesh');
  assert.equal(status.sharing.enabled, false);
});

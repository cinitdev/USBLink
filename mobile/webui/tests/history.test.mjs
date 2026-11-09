import test from 'node:test';
import assert from 'node:assert/strict';
import { HISTORY_PAGE_SIZE, readHistoryPage, historyLabel, historyCategory, historyMatches, historyCommand, historyCommandFamily, historyResultLabel } from '../src/history-model.mjs';
import { createClient } from '../src/api.mjs';

test('installation stages, uninstall and unknown services have distinct labels', () => {
  assert.equal(historyLabel({ action: 'install-write' }), '写入安装包');
  assert.equal(historyLabel({ action: 'install-commit' }), '提交安装');
  assert.equal(historyLabel({ action: 'uninstall' }), '卸载应用');
  assert.equal(historyCategory({ kind: 'shell' }), 'command');
  assert.equal(historyLabel({ kind: 'new-service' }), 'ADB 请求');
  assert.equal(historyMatches({ category: 'app', action: 'uninstall', command: 'pm uninstall foo' }, 'install', ''), false);
});

test('history displays observed arguments as adb equivalents without inventing host paths', () => {
  assert.equal(historyCommand({ command: "shell,v2,raw:echo 'hello world'" }), "adb shell echo 'hello world'");
  assert.equal(historyCommand({ command: 'shell,v2,pty:' }), 'adb shell');
  assert.equal(historyCommand({ command: 'abb_exec:package\0install-write\0-S\0' + '57\0' + '123\0my app.apk\0-\0' }), "adb shell cmd package install-write -S 57 123 'my app.apk' -");
  const install = { category: 'app', action: 'install-write', command: 'exec:cmd package install-write -S 57 123 base.apk -' };
  assert.equal(historyCommand(install), 'adb exec-out cmd package install-write -S 57 123 base.apk -');
  assert.equal(historyCommandFamily(install), 'adb install');
  assert.equal(historyMatches(install, 'all', 'adb install'), true);
  assert.equal(historyCommand({ kind: 'push', command: 'SEND /sdcard/a.apk' }), 'adb push <电脑端源路径未知> /sdcard/a.apk');
  assert.equal(historyCommand({ kind: 'service', command: 'vendor-custom:request' }), 'vendor-custom:request');
});
test('full history search finds older installs and preserves the chosen filter in responses', async () => {
  const client = createClient({ preview: true, usbPreview: true, historyPreview: true });
  const status = await client.request('status');
  assert.equal(status.history.limit, 1000);
  assert.ok(status.history.entries.length > 0 && status.history.entries.length <= 100);
  assert.equal(status.history.hasMore, true);
  const display = await readHistoryPage(payload => client.request('history', payload), { before: 0, epoch: status.history.epoch, filter: 'all', query: '' }, status.history);
  assert.equal(display.entries.length, 100);
  const payload = { before: 0, epoch: status.history.epoch, filter: 'install', query: 'older-release.apk' };
  const result = await client.request('history', payload);
  assert.equal(result.matchedCount, 10);
  assert.ok(result.entries.some(item => item.id < display.entries.at(-1).id));
  assert.equal(result.filter, payload.filter);
  assert.equal(result.query, payload.query);
  const all = await client.request('history', { ...payload, query: '' });
  assert.ok(all.entries.every(item => item.action.startsWith('install')));
  await client.request('clear-history');
  await assert.rejects(client.request('history', payload), /历史记录已更新/);
});

test('byte-bounded reads fill 100 visible rows without losing the next page', async () => {
  const records = Array.from({ length: 125 }, (_, i) => ({ id: 125 - i, response: { stdout: 'x'.repeat(4096) } }));
  const payload = { epoch: 'one', before: 0, filter: 'all', query: '' };
  let reads = 0;
  const read = async args => {
    reads++;
    const rows = records.filter(item => !args.before || item.id < args.before);
    return { ...payload, entries: rows.slice(0, 7), hasMore: rows.length > 7, count: 125, limit: 1000 };
  };
  const seed = await read(payload);
  const first = await readHistoryPage(read, payload, seed);
  assert.equal(first.entries.length, HISTORY_PAGE_SIZE);
  assert.equal(first.entries[0].id, 125);
  assert.equal(first.entries.at(-1).id, 26);
  assert.equal(first.hasMore, true);
  assert.equal(reads, 15);
  const second = await readHistoryPage(read, { ...payload, before: 26 });
  assert.deepEqual([...first.entries, ...second.entries].map(item => item.id), records.map(item => item.id));
  assert.equal(second.hasMore, false);
});

test('page assembly rejects cleared history and stops when navigation cancels it', async () => {
  const payload = { epoch: 'one', before: 0, filter: 'all', query: '' };
  await assert.rejects(readHistoryPage(async () => ({ ...payload, epoch: 'cleared', entries: [] }), payload), /历史记录已更新/);
  let active = true, calls = 0;
  const result = await readHistoryPage(async () => {
    calls++; active = false;
    return { ...payload, entries: [{ id: 4 }], hasMore: true };
  }, payload, undefined, () => active);
  assert.equal(result, null);
  assert.equal(calls, 1);
  await assert.rejects(readHistoryPage(async () => ({ ...payload, entries: [], hasMore: true }), payload), /不完整/);
});

test('results distinguish actual exits from transport closure and old records', () => {
  assert.match(historyResultLabel({ response: { state: 'completed', exitCode: 0 } }), /退出码 0/);
  assert.match(historyResultLabel({ response: { state: 'completed', exitCode: 1 } }), /报错/);
  assert.match(historyResultLabel({ response: { state: 'closed', stdout: 'Success\n' } }), /未提供退出码/);
  assert.match(historyResultLabel({}), /当时未采集/);
  assert.equal(historyMatches({ command: 'exec:cmd package install', response: { stdout: 'Failure [INSTALL_FAILED_USER_RESTRICTED]' } }, 'all', 'user_restricted'), true);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createClient, makeCommand, validateEnvironment } from '../src/api.mjs';

const script = readFileSync(new URL('../../ksu/bin/usblinkctl', import.meta.url), 'utf8').replaceAll('/system/bin/id', 'mock_id').replaceAll('/system/bin/toybox', 'mock_toybox');
const shell = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : '/bin/sh';
for (const [name, uid, usb, exit, expected] of [
  ['USB enabled and control shell Root', '0', '1', 0, { usbDebugging: true, shellUid: 0 }],
  ['USB off and ordinary shell remain distinct', '2000', '0', 0, { usbDebugging: false, shellUid: 2000 }],
  ['failed settings read is unknown even with numeric output', '0', '1', 1, { usbDebugging: null, shellUid: 0 }],
  ['missing settings and malformed UID do not imply permission', 'not-a-uid', 'null', 0, { usbDebugging: null, shellUid: null }],
]) test(name, () => {
  const mocks = 'mock_id() { printf "%s\n" "' + uid + '"; };\n' +
    'mock_toybox() { [ "$1" = timeout ] && [ "$2" = 3 ] && [ "$3" = /system/bin/settings ] && [ "$4" = get ] && [ "$5" = global ] && [ "$6" = adb_enabled ] || return 99; printf "%s\n" "' + usb + '"; return ' + exit + '; };\n';
  const result = spawnSync(shell, ['-c', mocks.replaceAll('\\n', '\n') + 'set -- environment e30=\n' + script], { encoding: 'utf8', timeout: 5000 });
  assert.equal(result.status, 0, result.stderr || String(result.error || ''));
  assert.deepEqual(validateEnvironment(JSON.parse(result.stdout).data), expected);
});

test('only an explicit valid observation can report permission', () => {
  assert.match(makeCommand('environment'), /usblinkctl environment 'e30='/);
  for (const value of [null, {}, { usbDebugging: '1', shellUid: 0 }, { usbDebugging: true, shellUid: '0' }, { usbDebugging: true, shellUid: -1 }]) assert.throws(() => validateEnvironment(value), /无法确认/);
});

test('sharing does not fabricate a change in control shell Root or system USB debugging', async () => {
  const client = createClient({ preview: true, usbPreview: true });
  await client.request('set-sharing', { enabled: true });
  assert.deepEqual(await client.request('environment'), { usbDebugging: true, shellUid: 0 });
  await client.request('set-sharing', { enabled: false });
  assert.deepEqual(await client.request('environment'), { usbDebugging: true, shellUid: 0 });
  await assert.rejects(createClient({ scope: {} }).request('environment'), /普通浏览器无法控制/);
});

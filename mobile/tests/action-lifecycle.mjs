import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Exercise the real action.sh readiness flow. All startup, sleeps, BusyBox
// timeout, control responses, and /data paths are fixtures; no daemon or phone
// process is started, queried, or stopped by this test.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bash = process.env.USBLINK_TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const posix = value => process.platform === 'win32'
  ? value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase())
  : value;
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const original = (await fs.readFile(path.join(root, 'ksu/action.sh'), 'utf8')).replaceAll('\r\n', '\n');
const startupCommand = '/system/bin/sh "$MODDIR/bin/supervise.sh" "$MODDIR" >/dev/null 2>&1 &';
assert.equal(original.split(startupCommand).length, 2, 'The real supervisor launch must be replaced exactly once before executing a fixture');
assert.ok(original.includes('/data/adb/usblink/startup.error'), 'Startup error path must be redirected into the fixture');
await fs.mkdir(path.join(root, 'build'), { recursive: true });

const scenarios = [
  { name: 'ready', readyAt: 1, reads: 1, sleeps: 0, success: true },
  { name: 'later-ready', readyAt: 4, reads: 4, sleeps: 3, success: true },
  { name: 'never-ready', reads: 10, sleeps: 10 },
  { name: 'startup-error', reads: 10, sleeps: 10, startupError: '无法保存守护进程状态，启动已取消。' },
  { name: 'daemon-fixed-error', reads: 10, sleeps: 10, daemonError: true },
  { name: 'timeout', reads: 10, sleeps: 10, timeout: true },
  { name: 'not-json-success', reads: 10, sleeps: 10, misleading: true },
  { name: 'disabled', reads: 0, sleeps: 0, flag: 'disable' },
  { name: 'removed', reads: 0, sleeps: 0, flag: 'remove' },
  { name: 'flock-unavailable', reads: 0, sleeps: 0, noFlock: true },
];

for (const scenario of scenarios) {
  const temp = await fs.mkdtemp(path.join(root, 'build', 'action-test-'));
  const directory = posix(temp);
  const module = directory + '/module';
  const state = directory + '/state';
  await fs.mkdir(path.join(temp, 'module/bin'), { recursive: true });
  await fs.mkdir(path.join(temp, 'state'));
  if (scenario.flag) await fs.writeFile(path.join(temp, 'module', scenario.flag), '');
  if (scenario.startupError) await fs.writeFile(path.join(temp, 'state/startup.error'), scenario.startupError + '\n');
  const fixedDaemonError = '{"ok":false,"error":"控制服务已经运行"}';
  if (scenario.daemonError) {
    await fs.writeFile(path.join(temp, 'state/daemon.log'),
      'RUNTIME_PRIVATE_SENTINEL runtime detail must not appear in the UI\n' + fixedDaemonError + '\nSECOND_PRIVATE_SENTINEL\n');
  }

  const common = `STATE=${quote(state)}
BUSYBOX=fake_busybox
find_flock() {
  echo flock >> "$STATE/order"
  [ ${scenario.noFlock ? '1' : '0'} -eq 0 ] || { echo '模拟：管理器缺少 BusyBox flock' >&2; return 1; }
}
start_supervisor() { echo start >> "$STATE/order"; }
sleep() {
  [ "$#" -eq 1 ] && [ "$1" = 1 ] || { echo 'Unexpected sleep' >&2; exit 91; }
  echo sleep >> "$STATE/order"
}
fake_busybox() {
  [ "$#" -eq 4 ] && [ "$1" = timeout ] && [ "$2" = 3 ] && [ "$3" = "$MODDIR/bin/usblinkctl" ] && [ "$4" = status ] || {
    echo 'Unexpected native command' >&2; return 92;
  }
  echo read >> "$STATE/order"
  count=0
  [ ! -f "$STATE/reads" ] || read -r count < "$STATE/reads"
  count=$((count+1))
  printf '%s\\n' "$count" > "$STATE/reads"
  [ ${scenario.timeout ? '1' : '0'} -eq 0 ] || return 124
  if [ ${scenario.readyAt || '999'} -le "$count" ]; then
    printf '%s\\n' '{"ok":true,"data":{"sharing":{"enabled":false}}}'
  elif [ ${scenario.misleading ? '1' : '0'} -eq 1 ]; then
    printf '%s\\n' 'requested start; ok=true but no daemon has answered'
  else
    printf '%s\\n' '{"ok":false,"error":"模块控制服务未运行，请在 APatch 模块页点击操作启动"}'
    return 1
  fi
}
`;
  await fs.writeFile(path.join(temp, 'module/bin/common.sh'), common);
  const script = original.replace('#!/system/bin/sh', '#!/bin/sh').replace(startupCommand, 'start_supervisor')
    .replaceAll('/data/adb/usblink/startup.error', state + '/startup.error');
  assert.ok(!script.includes('/system/bin/'), 'Fixture must contain no Android executable path');
  assert.ok(!script.includes('/data/adb/'), 'Fixture must contain no real module/data path');
  await fs.writeFile(path.join(temp, 'module/action.sh'), script);

  const run = spawnSync(bash, ['--noprofile', '--norc', module + '/action.sh'], { encoding: 'utf8', timeout: 5000 });
  assert.ifError(run.error);
  const detail = `${scenario.name}\n${run.stdout}\n${run.stderr}`;
  const order = (await fs.readFile(path.join(temp, 'state/order'), 'utf8').catch(() => '')).trim().split('\n').filter(Boolean);
  assert.equal(order.filter(item => item === 'read').length, scenario.reads, detail);
  assert.equal(order.filter(item => item === 'sleep').length, scenario.sleeps, detail);
  assert.equal(order.filter(item => item === 'start').length, scenario.flag || scenario.noFlock ? 0 : 1, detail);
  assert.doesNotMatch(run.stdout, /RUNTIME_PRIVATE_SENTINEL|SECOND_PRIVATE_SENTINEL/, 'Raw daemon output must remain private');
  if (scenario.success) {
    assert.equal(run.status, 0, detail);
    assert.match(run.stdout, /USBLink 控制服务已就绪/, detail);
    assert.doesNotMatch(run.stdout, /启动未确认|未能就绪/, detail);
    assert.deepEqual(order.slice(0, 2), ['flock', 'start'], detail);
    assert.equal(order.at(-1), 'read', 'Return immediately after actual readiness, without an extra sleep');
  } else {
    assert.equal(run.status, 1, detail);
    assert.doesNotMatch(run.stdout, /控制服务已就绪/, 'A launch request, failed ctl call, or timeout must never count as readiness');
    if (scenario.startupError) {
      assert.equal(run.stdout.trim(), scenario.startupError, detail);
    } else if (scenario.flag) {
      assert.match(run.stdout, /模块已禁用或待卸载/, detail);
      assert.deepEqual(order, [], detail);
    } else if (scenario.noFlock) {
      assert.match(run.stderr, /缺少 BusyBox flock/, detail);
      assert.deepEqual(order, ['flock'], detail);
    } else {
      assert.match(run.stdout, /控制服务未能就绪，启动未确认/, detail);
      if (scenario.daemonError) assert.ok(run.stdout.includes(fixedDaemonError), detail);
      if (!scenario.timeout && !scenario.misleading) assert.match(run.stdout, /模块控制服务未运行/, detail);
    }
  }
}
console.log(`PASS: ${scenarios.length} action readiness lifecycle scenarios`);

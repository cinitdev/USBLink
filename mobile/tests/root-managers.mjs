import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Run the real installer and BusyBox discovery against local fixtures only.
// No module is installed, no root command is executed, and no phone is contacted.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bash = process.env.USBLINK_TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const posix = value => process.platform === 'win32' ? value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, d) => '/' + d.toLowerCase()) : value;
const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
await fs.mkdir(path.join(root, 'build'), { recursive: true });
const temp = await fs.mkdtemp(path.join(root, 'build', 'managers-test-'));
const dir = posix(temp);
const installer = (await fs.readFile(path.join(root, 'ksu/customize.sh'), 'utf8')).replaceAll('\r\n', '\n');
const installerPath = path.join(temp, 'customize.sh');
await fs.writeFile(installerPath, installer);
const cases = [
  { name: 'magisk', env: { MAGISK_VER_CODE: '30400' }, manager: 'Magisk' },
  { name: 'magisk-minimum', env: { MAGISK_VER_CODE: '20400' }, manager: 'Magisk' },
  { name: 'apatch', env: { APATCH: 'true', MAGISK_VER_CODE: '27000' }, manager: 'APatch' },
  { name: 'apatch-version', env: { APATCH_VER_CODE: '11000' }, manager: 'APatch' },
  { name: 'ksu', env: { KSU: 'true', MAGISK_VER_CODE: '27000' }, manager: 'KernelSU' },
  { name: 'ksu-next-version', env: { KSU_VER_CODE: '12000' }, manager: 'KernelSU' },
  { name: 'android15', env: { API: '35', MAGISK_VER_CODE: '30400' }, manager: 'Magisk' },
  { name: 'unknown', env: {}, error: /请使用 Magisk、APatch 或 KernelSU/ },
  { name: 'old-magisk', env: { MAGISK_VER_CODE: '20300' }, error: /需要 Magisk 20.4/ },
  { name: 'invalid-magisk', env: { MAGISK_VER_CODE: 'unknown' }, error: /无法识别 Magisk/ },
];
for (const managerEnv of [{ MAGISK_VER_CODE: '30400' }, { APATCH: 'true' }, { KSU: 'true' }]) {
  cases.push(
    { name: 'android11', env: { ...managerEnv, API: '30' }, error: /需要 Android 12/ },
    { name: 'unknown-android', env: { ...managerEnv, API: '' }, error: /无法识别 Android/ },
    { name: 'invalid-android', env: { ...managerEnv, API: '31x' }, error: /无法识别 Android/ },
    { name: 'arm32', env: { ...managerEnv, ARCH: 'arm' }, error: /仅支持 arm64/ },
  );
}
for (const scenario of cases) {
  const env = { ARCH: 'arm64', API: '31', APATCH: '', APATCH_VER_CODE: '', KSU: '', KSU_VER_CODE: '', MAGISK_VER_CODE: '', ...scenario.env };
  const harness = `${Object.entries(env).map(([key, value]) => `${key}=${quote(value)}`).join('\n')}
MODPATH=${quote(dir + '/module')}
ui_print() { printf '%s\\n' "$1"; }
abort() { printf '%s\\n' "$1" >&2; exit 23; }
set_perm_recursive() { printf 'PERM_RECURSIVE %s\\n' "$*"; }
set_perm() { printf 'PERM %s\\n' "$*"; }
. ${quote(posix(installerPath))}
printf 'INSTALL_FINISHED\\n'
`;
  const result = spawnSync(bash, ['--noprofile', '--norc', '-c', harness], { encoding: 'utf8', timeout: 10000 });
  assert.ifError(result.error);
  const detail = `${scenario.name}: ${result.stdout}\n${result.stderr}`;
  if (scenario.manager) {
    assert.equal(result.status, 0, detail);
    assert.match(result.stdout, new RegExp(`已识别 ${scenario.manager}`), detail);
    assert.match(result.stdout, /INSTALL_FINISHED/, 'Installer must return to manager cleanup instead of exiting');
    assert.ok(result.stdout.includes(`PERM_RECURSIVE ${dir}/module/webroot 0 0 0755 0644`), detail);
    assert.ok(result.stdout.includes(`PERM_RECURSIVE ${dir}/module/bin 0 0 0755 0755`), detail);
    if (scenario.manager === 'Magisk') assert.match(result.stdout, /KsuWebUI.*Root/, detail);
    else assert.doesNotMatch(result.stdout, /已识别 Magisk/, 'KSU/APatch may also provide Magisk compatibility variables');
  } else {
    assert.equal(result.status, 23, detail);
    assert.match(result.stderr, scenario.error, detail);
    assert.doesNotMatch(result.stdout, /PERM|INSTALL_FINISHED/, 'Rejected installations must stop before permissions are applied');
  }
}

const common = (await fs.readFile(path.join(root, 'ksu/bin/common.sh'), 'utf8')).replaceAll('\r\n', '\n');
const managerPaths = ['ap/bin/busybox', 'ksu/bin/busybox', 'magisk/busybox'];
const flockCases = [
  { name: 'magisk-only', binaries: { 'magisk/busybox': true }, selected: 'magisk/busybox' },
  { name: 'apatch-only', binaries: { 'ap/bin/busybox': true }, selected: 'ap/bin/busybox' },
  { name: 'ksu-only', binaries: { 'ksu/bin/busybox': true }, selected: 'ksu/bin/busybox' },
  { name: 'missing-applet', binaries: { 'magisk/busybox': false } },
  { name: 'missing-manager', binaries: {} },
  { name: 'fallback', binaries: { 'ap/bin/busybox': false, 'magisk/busybox': true }, selected: 'magisk/busybox' },
];
for (const scenario of flockCases) {
  const testDir = path.join(temp, scenario.name);
  await fs.mkdir(testDir);
  let fixture = common.replace('STATE=/data/adb/usblink', 'STATE=' + quote(posix(testDir) + '/state'));
  for (const managerPath of managerPaths) fixture = fixture.replaceAll('/data/adb/' + managerPath, posix(testDir) + '/' + managerPath);
  for (const [managerPath, supported] of Object.entries(scenario.binaries)) {
    const file = path.join(testDir, managerPath);
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, `#!/bin/sh\n[ "$1" = --list ] || exit 98\nprintf '%s\\n' ${supported ? 'flock timeout' : 'ls timeout'}\n`, { mode: 0o755 });
  }
  const file = path.join(testDir, 'common.sh');
  await fs.writeFile(file, fixture);
  const result = spawnSync(bash, ['--noprofile', '--norc', '-c', `. ${quote(posix(file))}; find_flock || exit 24; printf 'SELECTED=%s\\n' "$BUSYBOX"`], { encoding: 'utf8', timeout: 10000 });
  assert.ifError(result.error);
  if (scenario.selected) {
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), 'SELECTED=' + posix(testDir) + '/' + scenario.selected);
  } else {
    assert.equal(result.status, 24, result.stderr);
    assert.match(result.stderr, /缺少 BusyBox flock/);
    assert.doesNotMatch(result.stdout, /SELECTED/);
  }
}
console.log(`PASS: ${cases.length} manager installation scenarios and ${flockCases.length} BusyBox discovery scenarios`);

import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

// Run the real helper functions against fixture /proc and network commands.
// No phone, host process, routing table, or firewall is touched.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bash=process.env.USBLINK_TEST_BASH || (process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const posix=p=>process.platform==='win32'?p.replaceAll('\\','/').replace(/^([A-Za-z]):/,(_,d)=>'/'+d.toLowerCase()):p;
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
await fs.mkdir(path.join(root,'build'),{recursive:true});
const temp=await fs.mkdtemp(path.join(root,'build','script-test-'));
const dir=posix(temp);
await fs.mkdir(path.join(temp,'state'));
await fs.mkdir(path.join(temp,'proc'));
let common=await fs.readFile(path.join(root,'ksu/bin/common.sh'),'utf8');
common=common.replace('STATE=/data/adb/usblink','STATE='+quote(dir+'/state'))
  .replaceAll('/proc/',dir+'/proc/').replaceAll('/system/bin/iptables',dir+'/iptables').replaceAll('/system/bin/ip ',dir+'/ip ')
  .replace('/system/bin/app_process / --nice-name=usblink-restore',quote(dir+'/app-process')+' / --nice-name=usblink-restore');
await fs.writeFile(path.join(temp,'common.sh'),common);
await fs.writeFile(path.join(temp,'ip'),`#!/bin/sh
printf '%s\\n' "$*" >> ${quote(dir+'/ip.log')}
case "$*" in
  '-4 rule del '*) [ ! -f ${quote(dir+'/rule-present')} ];;
  '-4 rule show') [ ! -f ${quote(dir+'/deny-network')} ] || exit 1
    [ ! -f ${quote(dir+'/rule-present')} ] || echo '17891: from all to 10.126.126.0/24 lookup main';;
  *) exit 1;;
esac
`,{mode:0o755});
await fs.writeFile(path.join(temp,'iptables'),`#!/bin/sh
printf '%s\\n' "$*" >> ${quote(dir+'/iptables.log')}
[ ! -f ${quote(dir+'/deny-network')} ] || exit 4
exit 0
`,{mode:0o755});
await fs.writeFile(path.join(temp,'app-process'),`#!/bin/sh
printf '%s\\n' "$CLASSPATH" "$@" > ${quote(dir+'/restore.log')}
[ ! -f ${quote(dir+'/restore-failure')} ] || exit 1
`,{mode:0o755});
const harness=String.raw`#!/bin/bash
set -u
. SOURCE_FILE
MODDIR=/data/adb/modules/usblink-mobile
FIXTURE=FIXTURE_DIR
checks=0
check() { "$@" || { echo "FAIL ($checks): $*" >&2; exit 1; }; checks=$((checks+1)); }
no() { ! "$@"; }
sleep() { :; }
readlink() { cat "$1.target"; }
make_stat() {
  printf '%s (name with ) spaces) %s' "$1" "$3" > "$FIXTURE/proc/$1/stat"
  local n=0
  while [ "$n" -lt 18 ]; do printf ' 0' >> "$FIXTURE/proc/$1/stat"; n=$((n+1)); done
  printf ' %s\n' "$2" >> "$FIXTURE/proc/$1/stat"
}
make_proc() {
  mkdir -p "$FIXTURE/proc/$1"
  make_stat "$1" "$2" S
  printf '%s\n' "$3" > "$FIXTURE/proc/$1/exe.target"
  shift 3
  printf '%s\0' "$@" > "$FIXTURE/proc/42/cmdline"
}
KILL_MODE=exit
kill() {
  [ "$1" != -0 ] || return 0
  printf '%s\n' "$*" >> "$FIXTURE/kills"
  [ "$KILL_MODE" != exit ] || make_stat "$2" 123 Z
  return 0
}

make_proc 42 123 /system/bin/app_process64 /system/bin/app_process / --nice-name=usblink-mobile io.usblink.mobile.Main serve "$MODDIR" "$STATE"
check write_record "$STATE/daemon.pid" 42
check test "$(cat "$STATE/daemon.pid")" = '42 123'
check owned_record "$STATE/daemon.pid" daemon
printf 'usblink-mobile\0' > "$FIXTURE/proc/42/cmdline"
check owned_record "$STATE/daemon.pid" daemon
check stop_record "$STATE/daemon.pid" daemon
check test ! -f "$STATE/daemon.pid"
check test "$(cat "$FIXTURE/kills")" = '-TERM 42'

make_proc 42 999 /system/bin/app_process64 usblink-mobile
printf '42 123\n' > "$STATE/daemon.pid"
: > "$FIXTURE/kills"
check stop_record "$STATE/daemon.pid" daemon
check test ! -s "$FIXTURE/kills"
check test ! -f "$STATE/daemon.pid"

printf '0 123\n' > "$STATE/daemon.pid"
check no stop_record "$STATE/daemon.pid" daemon
check test -f "$STATE/daemon.pid"
check test ! -s "$FIXTURE/kills"

make_proc 42 123 /system/bin/app_process64 someone-else
printf '42 123\n' > "$STATE/daemon.pid"
check no stop_record "$STATE/daemon.pid" daemon
check test -f "$STATE/daemon.pid"
check test ! -s "$FIXTURE/kills"

make_proc 42 123 /system/bin/app_process64 usblink-mobile
KILL_MODE=ignore
check no stop_record "$STATE/daemon.pid" daemon
check test -f "$STATE/daemon.pid"
check test "$(cat "$FIXTURE/kills")" = $'-TERM 42\n-KILL 42'
KILL_MODE=exit
check stop_record "$STATE/daemon.pid" daemon

make_proc 42 123 /system/bin/sh /system/bin/sh "$MODDIR/bin/mesh-run.sh" "$MODDIR"
check write_record "$STATE/mesh.pid" 42
check owned_record "$STATE/mesh.pid" mesh
check no clear_rpc_rule
check test ! -f "$FIXTURE/iptables.log"
check stop_record "$STATE/mesh.pid" mesh
check clear_rpc_rule
check grep -Fq -- '-D OUTPUT -d 127.0.0.1/32 -p tcp --dport 15891 -m owner ! --uid-owner 0 -m comment --comment usblink-mobile-rpc -j REJECT' "$FIXTURE/iptables.log"

printf '0.0.0.0/0\n' > "$STATE/mesh.route"
check no clear_route
check test ! -f "$FIXTURE/ip.log"
printf '10.126.126.0/24\n' > "$STATE/mesh.route"
check clear_route
check test ! -f "$STATE/mesh.route"
check test "$(cat "$FIXTURE/ip.log")" = '-4 rule del priority 17891 to 10.126.126.0/24 lookup main'
printf '10.126.126.0/24\n' > "$STATE/mesh.route"
: > "$FIXTURE/rule-present"
check no clear_route
check test -f "$STATE/mesh.route"
: > "$FIXTURE/deny-network"
check no clear_rpc_rule

flock_calls=0
flock_fail=2
exec 8>"$FIXTURE/test-control.flock"
exec 9>"$FIXTURE/test-supervisor.flock"
check test -e "$FIXTURE/test-control.flock"
check test -e "$FIXTURE/test-supervisor.flock"
fake_busybox() {
  case "$1 $2 $3" in
    'flock -n 8') : >&8 || return 2;;
    'flock -n 9') : >&9 || return 2; return 0;;
    *) return 2;;
  esac
  flock_calls=$((flock_calls+1))
  [ "$flock_calls" -gt "$flock_fail" ]
}
BUSYBOX=fake_busybox
check lock_control
check test "$flock_calls" -eq 3
flock_calls=0; flock_fail=1000
check no lock_control
check test "$flock_calls" -eq 151
check "$BUSYBOX" flock -n 9 9>&9
check restore_adb
check test ! -f "$FIXTURE/restore.log"
: > "$STATE/adbd-tcp.json"
check restore_adb
check grep -Fxq -- "$MODDIR/lib/usblink-daemon.jar" "$FIXTURE/restore.log"
check grep -Fxq -- 'restore-adb' "$FIXTURE/restore.log"
check grep -Fxq -- "$STATE" "$FIXTURE/restore.log"
: > "$FIXTURE/restore-failure"
check no restore_adb
check test -f "$STATE/adbd-tcp.json"

mkdir -p "$FIXTURE/proc/sys/kernel/random"
printf '%s\n' '11111111-1111-1111-1111-111111111111' > "$FIXTURE/proc/sys/kernel/random/boot_id"
printf '%s\n' '100.00 100.00' > "$FIXTURE/proc/uptime"
make_proc 42 123 /system/bin/app_process64 usblink-mobile
check write_record "$STATE/daemon.pid" 42
snapshot() {
  printf '42 %s %s %s\n%s\n' "$1" "$2" "$3" '{"ok":true,"data":{"sharing":{"enabled":false}}}' > "$STATE/status.snapshot"
}
snapshot 123 11111111-1111-1111-1111-111111111111 100
check test "$(read_status)" = '{"ok":true,"data":{"sharing":{"enabled":false}}}'
printf '%s\n' '110.00 110.00' > "$FIXTURE/proc/uptime"
check read_status
printf '%s\n' '111.00 111.00' > "$FIXTURE/proc/uptime"
check no read_status
printf '%s\n' '99.00 99.00' > "$FIXTURE/proc/uptime"
check no read_status
printf '%s\n' '100.00 100.00' > "$FIXTURE/proc/uptime"
snapshot 123 22222222-2222-2222-2222-222222222222 100
check no read_status
snapshot 999 11111111-1111-1111-1111-111111111111 100
check no read_status
snapshot 123 11111111-1111-1111-1111-111111111111 100
make_stat 42 999 S
check no read_status
make_stat 42 123 Z
check no read_status
make_proc 42 123 /system/bin/app_process64 another-app
check no read_status
make_proc 42 123 /system/bin/app_process64 usblink-mobile
: > "$STATE/stopping"
check no read_status
rm -f "$STATE/stopping"
printf '%s\n' '42 123 invalid incomplete' > "$STATE/status.snapshot"
check no read_status

exec 8>&-
exec 9>&-
echo "PASS: $checks module lifecycle checks"
`.replace('SOURCE_FILE',quote(dir+'/common.sh')).replace('FIXTURE_DIR',quote(dir));
await fs.writeFile(path.join(temp,'test.sh'),harness);
for(const name of ['common.sh','supervise.sh','stop.sh']) {
  const syntax=spawnSync(bash,['--noprofile','--norc','-n',posix(path.join(root,'ksu/bin',name))],{encoding:'utf8'});
  assert.equal(syntax.status,0,syntax.stderr || `Shell syntax: ${name}`);
}
const run=spawnSync(bash,['--noprofile','--norc',posix(path.join(temp,'test.sh'))],{encoding:'utf8',timeout:15000});
assert.equal(run.status,0,run.stdout+'\n'+run.stderr);
process.stdout.write(run.stdout);

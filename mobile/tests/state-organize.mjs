import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

// The tidy-up may move files, but it must never block startup, never overwrite a
// newer file and never touch anything while a module worker is alive.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const bash=process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash';
const posix=p=>process.platform==='win32'?p.replaceAll('\\','/').replace(/^([A-Za-z]):/,(_,d)=>'/'+d.toLowerCase()):p;
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
await fs.mkdir(path.join(root,'build'),{recursive:true});
const temp=await fs.mkdtemp(path.join(root,'build','organize-test-'));
const dir=posix(temp);
await fs.writeFile(path.join(temp,'common.sh'),(await fs.readFile(path.join(root,'ksu/bin/common.sh'),'utf8')).replace('STATE=/data/adb/usblink','STATE='+quote(dir+'/state')));
const script=String.raw`#!/bin/bash
. COMMON
checks=0
check() { "$@" || { echo "FAIL ($checks): $*" >&2; exit 1; }; checks=$((checks+1)); }
workers=0
runtime_workers_absent() { [ "$workers" = 0 ]; }
module_workers_absent() { [ "$workers" = 0 ]; }

# Resolution without any tidy-up: no group directory, so files stay flat.
mkdir -p "$STATE"
printf 'pairing' > "$STATE/mesh.json"
check test "$(state_file mesh.json)" = "$STATE/mesh.json"
check test "$(state_live daemon.pid)" = "$STATE/daemon.pid"
rm -f "$STATE/mesh.json"

# A live worker means the tidy-up does nothing at all.
printf 'pairing' > "$STATE/mesh.json"
printf 'log' > "$STATE/daemon.log"
workers=1
check organize_state
check test -f "$STATE/mesh.json"
check test -f "$STATE/daemon.log"
check test ! -e "$STATE/config"
check test ! -e "$STATE/logs"
workers=0

# Everything flat is moved into the group directories, and nothing is lost.
mkdir -p "$STATE/supervisor.lock" "$STATE/recovery-1794000000-20"
printf '42 123\n' > "$STATE/daemon.pid"
printf '43 124\n' > "$STATE/mesh.pid"
printf 'snapshot' > "$STATE/status.snapshot"
printf 'journal' > "$STATE/adbd-tcp.json"
printf 'on' > "$STATE/sharing.json"
printf 'db' > "$STATE/adb-history.db"
printf 'wal' > "$STATE/adb-history.db-wal"
printf 'shm' > "$STATE/adb-history.db-shm"
printf 'log' > "$STATE/daemon.log"
printf 'old-log' > "$STATE/daemon.previous.log"
printf 'error' > "$STATE/startup.error"
printf 'stop' > "$STATE/stopping"
printf 'mesh-toml' > "$STATE/mesh.toml"
printf 'mesh-route' > "$STATE/mesh.route"
printf 'json-history' > "$STATE/adb-history.jsonl"
printf 'unknown' > "$STATE/keep-me.txt"
printf '44 125\n' > "$STATE/supervisor.lock/pid"
printf 'old' > "$STATE/recovery-1794000000-20/daemon.pid"
mkdir -p "$STATE/config"
printf '2\n' > "$STATE/config/layout.version"
check organize_state
check test "$(cat "$STATE/config/mesh.json")" = pairing
check test "$(cat "$STATE/config/sharing.json")" = on
check test "$(cat "$STATE/history/adb-history.db")" = db
check test "$(cat "$STATE/history/adb-history.db-wal")" = wal
check test "$(cat "$STATE/history/adb-history.db-shm")" = shm
check test "$(cat "$STATE/runtime/adbd-tcp.json")" = journal
check test "$(cat "$STATE/runtime/mesh.toml")" = mesh-toml
check test "$(cat "$STATE/runtime/mesh.route")" = mesh-route
check test "$(cat "$STATE/runtime/daemon.pid")" = '42 123'
check test "$(cat "$STATE/runtime/mesh.pid")" = '43 124'
check test "$(cat "$STATE/runtime/status.snapshot")" = snapshot
check test "$(cat "$STATE/runtime/stopping")" = stop
check test "$(cat "$STATE/runtime/supervisor.lock/pid")" = '44 125'
check test "$(cat "$STATE/logs/daemon.log")" = log
check test "$(cat "$STATE/logs/daemon.previous.log")" = old-log
check test "$(cat "$STATE/logs/startup.error")" = error
check test "$(cat "$STATE/backups/legacy/adb-history.jsonl")" = json-history
check test "$(cat "$STATE/backups/recovery-1794000000-20/daemon.pid")" = old
check test "$(cat "$STATE/keep-me.txt")" = unknown
check test ! -e "$STATE/mesh.json"
check test ! -e "$STATE/adb-history.db"
check test ! -e "$STATE/supervisor.lock"
check test ! -e "$STATE/recovery-1794000000-20"
check test ! -e "$STATE/config/layout.version"
# The organized copy is used from now on, and a second run changes nothing.
check test "$(state_file mesh.json)" = "$STATE/config/mesh.json"
check test "$(state_live daemon.pid)" = "$STATE/runtime/daemon.pid"
check organize_state
check test "$(cat "$STATE/config/mesh.json")" = pairing
check test "$(cat "$STATE/history/adb-history.db")" = db
check test "$(cat "$STATE/keep-me.txt")" = unknown

# A different organized copy is retired into backups; the flat copy stays live.
printf 'flat-pairing' > "$STATE/mesh.json"
printf 'organized-pairing' > "$STATE/config/mesh.json"
printf 'flat-db' > "$STATE/adb-history.db"
check organize_state
check test "$(cat "$STATE/config/mesh.json")" = flat-pairing
check test ! -e "$STATE/mesh.json"
check test "$(cat "$STATE/history/adb-history.db")" = flat-db
check test ! -e "$STATE/adb-history.db"
check test "$(find "$STATE/backups" -path '*conflict-*/mesh.json' -exec cat {} \;)" = organized-pairing
check test "$(find "$STATE/backups" -path '*db-*/adb-history.db' -exec cat {} \;)" = db
check test "$(state_file mesh.json)" = "$STATE/config/mesh.json"

# Resolution order: live state prefers the organized copy, data prefers a flat file.
printf 'flat-live' > "$STATE/status.snapshot"
printf 'organized-live' > "$STATE/runtime/status.snapshot"
check test "$(state_live status.snapshot)" = "$STATE/runtime/status.snapshot"
printf 'flat-journal' > "$STATE/adbd-tcp.json"
check test "$(state_file adbd-tcp.json)" = "$STATE/adbd-tcp.json"

printf 'PASS: %s data directory tidy-up checks\n' "$checks"
`.replace('COMMON',quote(dir+'/common.sh'));
await fs.writeFile(path.join(temp,'test.sh'),script);
const run=spawnSync(bash,['--noprofile','--norc',posix(path.join(temp,'test.sh'))],{encoding:'utf8',timeout:15000});
assert.equal(run.status,0,run.stdout+'\n'+run.stderr);
process.stdout.write(run.stdout.trim()+'\n');

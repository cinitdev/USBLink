import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

// Execute mesh-run with fixture process ownership and firewall commands only.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const bash=process.env.USBLINK_TEST_BASH || (process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const posix=p=>process.platform==='win32'?p.replaceAll('\\','/').replace(/^([A-Za-z]):/,(_,d)=>'/'+d.toLowerCase()):p;
const quote=s=>"'"+s.replaceAll("'","'\\''")+"'";
const source=await fs.readFile(path.join(root,'ksu/bin/mesh-run.sh'),'utf8');
await fs.mkdir(path.join(root,'build'),{recursive:true});
let checks=0;
for(const scenario of ['ready','missing-parent','different-parent','replaced-parent','stopping']) {
  const temp=await fs.mkdtemp(path.join(root,'build','wrapper-test-'));
  const dir=posix(temp),module=dir+'/module',state=dir+'/state';
  await fs.mkdir(path.join(temp,'module/bin'),{recursive:true});
  await fs.mkdir(path.join(temp,'state'));
  await fs.mkdir(path.join(temp,'dev'));
  const common=`STATE=${quote(state)}
find_flock() { return 0; }
lock_control() { return 0; }
owned_record() {
  RECORD_PID=$PPID
  RECORD_STAMP=123
  [ ${quote(scenario)} != missing-parent ] || return 1
  [ ${quote(scenario)} != different-parent ] || RECORD_PID=$((PPID+1))
  if [ ${quote(scenario)} = replaced-parent ] && [ -f "$STATE/prepared" ]; then RECORD_STAMP=456; fi
  return 0
}
write_record() { printf '%s %s\\n' "$2" 789 > "$1"; echo record >> "$STATE/order"; }
`;
  await fs.writeFile(path.join(temp,'module/bin/common.sh'),common);
  const firewall=`#!/bin/sh
[ -f ${quote(state+'/mesh.pid')} ] || exit 69
echo firewall >> ${quote(state+'/order')}
: > ${quote(state+'/prepared')}
[ ${quote(scenario)} != stopping ] || : > ${quote(state+'/stopping')}
exit 0
`;
  await fs.writeFile(path.join(temp,'firewall'),firewall,{mode:0o755});
  const core=`#!/bin/sh
# A child must not keep the supervisor's operation lock alive.
if (: >&8) 2>/dev/null; then exit 71; fi
echo core >> ${quote(state+'/order')}
exit 0
`;
  await fs.writeFile(path.join(temp,'module/bin/easytier-core'),core,{mode:0o755});
  const script=source.replaceAll('/data/adb/modules/usblink-mobile',module)
    .replaceAll('/system/bin/iptables',dir+'/firewall')
    .replaceAll('/dev/net/tun',dir+'/dev/net/tun').replaceAll('/dev/tun',dir+'/dev/tun');
  await fs.writeFile(path.join(temp,'module/bin/mesh-run.sh'),script);
  const run=spawnSync(bash,['--noprofile','--norc',module+'/bin/mesh-run.sh',module],{encoding:'utf8',timeout:5000});
  const order=await fs.readFile(path.join(temp,'state/order'),'utf8').catch(()=> '');
  if(scenario==='ready') {
    assert.equal(run.status,0,run.stderr);
    assert.match(order,/^record\nfirewall[\s\S]*core\n$/,'Register before firewall and exec only after releasing fd 8');
  } else {
    assert.notEqual(run.status,0,scenario+' must stop');
    assert.ok(!order.includes('core'),scenario+' must not launch EasyTier');
    if(scenario.endsWith('parent') && scenario!=='replaced-parent')assert.equal(order,'','Reject stale parent before preparing networking');
  }
  checks++;
}
console.log(`PASS: ${checks} mesh wrapper lifecycle scenarios`);

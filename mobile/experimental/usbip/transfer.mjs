// Explicit real-phone data test on the already manually attached experimental USB interface.
// Does not connect/reconnect USB or ADB and never installs an application.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
const adb=path.join(process.env.LOCALAPPDATA,'Android/Sdk/platform-tools/adb.exe');
const serial=process.env.USBLINK_TEST_SERIAL;
if(!serial || !/^[A-Za-z0-9_-]{1,64}$/.test(serial))throw new Error('Set USBLINK_TEST_SERIAL to the explicitly authorized physical test phone');
const dir='mobile/build/usbip-probe';
const preflight=JSON.parse(fs.readFileSync(`${dir}/preflight.json`,'utf8'));
const session=preflight.sourcePath.split('/').pop();
if(!/^[a-f0-9-]{36}$/.test(session))throw new Error('Invalid test session');
const remote=`/data/local/tmp/usblink-usb-probe-transfer-${session.slice(0,8)}.bin`;
const source=`${dir}/transfer-64m.bin`,received=`${dir}/received-64m.bin`;
if(fs.statSync(source).size!==64*1024*1024)throw new Error('Expected 64 MiB source');
async function digest(file) {const hash=crypto.createHash('sha256');for await(const data of fs.createReadStream(file))hash.update(data);return hash.digest('hex');}
async function run(args) {
  return await new Promise((resolve,reject)=>{
    const process=spawn(adb,['-s',`USBLink-${serial}`,...args],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let result='';const started=Date.now();
    const timer=setTimeout(()=>process.kill(),15*60*1000);
    for(const stream of [process.stdout,process.stderr])stream.on('data',b=>{result+=b.toString();if(result.length>65536)process.kill();});
    process.on('error',reject);process.on('exit',code=>{
      clearTimeout(timer);if(code!==0){reject(new Error(`ADB test failed (${code}): ${result.slice(-2000)}`));return;}
      resolve({output:result.trim(),seconds:(Date.now()-started)/1000});
    });
  });
}
const result={startedAt:new Date().toISOString(),bytes:64*1024*1024,session,remote,sourceSha256:await digest(source)};
const model=await run(['shell','getprop','ro.product.model']);
if(model.output!=='M2012K11AC')throw new Error('Unexpected phone behind experimental USB interface');
console.log('Starting 64 MiB push through the manually attached virtual USB interface');
try {
  result.push=await run(['push',source,remote]);console.log(result.push);
  result.remoteHash=await run(['shell','sha256sum',remote]);
  if(result.remoteHash.output.split(/\s/)[0]!==result.sourceSha256)throw new Error('Remote SHA256 mismatch');
  console.log('Phone SHA256 matches; starting 64 MiB pull over the same virtual USB interface');
  result.pull=await run(['pull',remote,received]);console.log(result.pull);
  result.receivedSha256=await digest(received);
  if(result.receivedSha256!==result.sourceSha256)throw new Error('Pulled SHA256 mismatch');
  await run(['shell','rm','-f',remote]);
  result.completedAt=new Date().toISOString();result.ok=true;
  console.log('64 MiB push + pull passed; all three SHA256 values match. Temporary phone file removed.');
}catch(error){result.ok=false;result.error=error.message;process.exitCode=1;console.error(error.message);}
finally{fs.writeFileSync(`${dir}/transfer-result.json`,JSON.stringify(result,null,2));}

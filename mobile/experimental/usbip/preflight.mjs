import net from 'node:net';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
const adb = path.join(process.env.LOCALAPPDATA,'Android/Sdk/platform-tools/adb.exe');
const serial = process.env.USBLINK_TEST_SERIAL;
if (!serial || !/^[A-Za-z0-9_-]{1,64}$/.test(serial)) throw new Error('Set USBLINK_TEST_SERIAL to the explicitly authorized physical test phone');
const read = name => {
  try { return execFileSync(adb,['-s',serial,'exec-out','su','-c',`cat /data/adb/${name}`],
    {windowsHide:true,maxBuffer:4096,stdio:['ignore','pipe','pipe']}); }
  catch { throw new Error('Could not read authorized phone preflight data'); }
};
const expected = read('usblink-usb-probe/record.bin');
if (expected.length !== 312 || expected.readUInt32BE(288) !== 99 || expected.readUInt16BE(300) !== 0x18d1 || expected.readUInt16BE(302) !== 0x4ee7)
  throw new Error('Unexpected source identity');
const profileBytes=read('usblink/mesh.json');
let profile;
try { profile=JSON.parse(profileBytes.toString('utf8')); }
catch { throw new Error('Invalid authorized phone mesh profile'); }
finally { profileBytes.fill(0); }
const key=crypto.createHash('sha256').update('USBLink application presence v1\0').update(profile.network_secret).digest();
profile.network_secret='';
async function exchange(port,request,length) {
  return await new Promise((resolve,reject)=>{
    const socket=net.connect({host:'127.0.0.1',port});const chunks=[];let count=0;
    socket.setTimeout(5000,()=>socket.destroy(new Error('Read-only preflight timeout')));
    socket.on('connect',()=>socket.write(request));socket.on('error',reject);
    socket.on('end',()=>{if(count<length)reject(new Error('Short preflight response'));});
    socket.on('data',data=>{chunks.push(data);count+=data.length;if(count>=length){socket.destroy();resolve(Buffer.concat(chunks));}});
  });
}
async function presence() {
  const nonce=crypto.randomBytes(16);
  const tag=crypto.createHmac('sha256',key).update('USBLink presence request v1\0').update(nonce).digest();
  const response=await exchange(33241,Buffer.concat([Buffer.from('USBLINK3'),nonce,tag]),33);
  const wanted=crypto.createHmac('sha256',key).update('USBLink presence response v1\0').update(nonce).update(response.subarray(0,1)).digest();
  if(response[0]!==5 || !crypto.timingSafeEqual(wanted,response.subarray(1,33)))throw new Error('Android application authentication/readiness failed');
}
for(let i=0;i<3;i++) {
  await presence();
  const response=await exchange(33240,Buffer.from('0111800500000000','hex'),328);
  if(response.readUInt16BE(0)!==0x111 || response.readUInt16BE(2)!==5 || response.readUInt32BE(4)!==0 || response.readUInt32BE(8)!==1 ||
      !crypto.timingSafeEqual(response.subarray(12,324),expected))throw new Error('Export identity changed');
  if(i<2)await new Promise(r=>setTimeout(r,500));
}
const result={checkedAt:new Date().toISOString(),busId:'99-1',vidPid:'18d1:4ee7',
  sourcePath:expected.subarray(0,256).toString('ascii').split('\0')[0],authenticatedAndroidPhase:5,stableSamples:3};
fs.writeFileSync('mobile/build/usbip-probe/preflight.json',JSON.stringify(result,null,2));key.fill(0);
console.log(result);

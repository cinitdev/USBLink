// Temporary, encrypted route to the phone's existing mesh. No TUN, service or saved profile.
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const adb = path.join(process.env.LOCALAPPDATA, 'Android/Sdk/platform-tools/adb.exe');
const serial = process.env.USBLINK_TEST_SERIAL;
if (!serial || !/^[A-Za-z0-9_-]{1,64}$/.test(serial)) throw new Error('Set USBLINK_TEST_SERIAL to the explicitly authorized physical test phone');
const identity = execFileSync(adb, ['-s', serial, 'shell', 'getprop', 'ro.product.model'], { encoding: 'utf8', windowsHide: true }).trim();
if (identity !== 'M2012K11AC') throw new Error('Authorized test phone is not connected');
// Keep pairing data entirely in memory and child environment, never in argv, files or logs.
let bytes, profile;
try {
  bytes = execFileSync(adb, ['-s', serial, 'exec-out', 'su', '-c', 'cat /data/adb/usblink/mesh.json'], { windowsHide: true, maxBuffer: 4096, stdio: ['ignore', 'pipe', 'pipe'] });
  profile = JSON.parse(bytes.toString('utf8'));
} catch { throw new Error('Could not read the authorized phone mesh profile; no profile data was logged'); }
finally { bytes?.fill(0); }
if (!/^usblink-[a-f0-9]{12}$/.test(profile.network_name) || !/^[a-f0-9]{64}$/.test(profile.network_secret) ||
    profile.relay !== 'tcp://183.230.36.171:11010') throw new Error('Unexpected phone mesh profile');
const child = spawn(path.join(root, 'src-tauri/vendor/easytier/easytier-core.exe'), [
  '--no-tun', '--ipv4', '10.126.126.2', '--no-listener', '--hostname', 'USBLink-USB-Probe',
  '--instance-name', 'usblink-usb-probe', '--rpc-portal', '127.0.0.1:15896',
  '--encryption-algorithm', 'aes-256-gcm',
  '--peers', profile.relay, '--port-forward', 'tcp://127.0.0.1:33240/10.126.126.1:3240',
  '--port-forward', 'tcp://127.0.0.1:33241/10.126.126.1:3241',
  '--console-log-level', 'off', '--disable-upnp', '--disable-ipv6', '--bind-device', 'false', '--use-smoltcp',
], { windowsHide: true, stdio: 'ignore', env: {
  ...process.env, ET_NETWORK_NAME: profile.network_name, ET_NETWORK_SECRET: profile.network_secret,
} });
profile.network_secret = '';
const stopFile = path.join(root, 'mobile/build/usbip-probe/stop-host');
fs.rmSync(stopFile, { force: true });
console.log('Temporary mesh process started. RPC 15896; USB/IP loopback 33240. No desktop pairing changed.');
const timer = setInterval(() => { if (fs.existsSync(stopFile)) child.kill(); }, 1000);
const expires = setTimeout(() => child.kill(), 46 * 60 * 1000);
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill());
child.on('error', () => { console.error('Temporary mesh process could not start'); process.exitCode = 1; });
child.on('close', code => { clearInterval(timer); clearTimeout(expires); console.log(`Temporary mesh stopped (${code})`); });

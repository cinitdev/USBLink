import test from 'node:test';
import assert from 'node:assert/strict';
import { networkDeviceRows, sourceDeviceRows, sourceDeviceKey, selectedSourceBusIds, previewNetworkPeers } from './network-devices.mjs';
import { mergePeerPresence } from './peer-state.mjs';

test('large mixed networks keep every source and independent mount counts', () => {
  const peers = previewNetworkPeers(80);
  const mounts = peers.flatMap(peer => Array.from({ length: 4 }, (_, index) => ({ host: peer.ip, busId: `99-${index + 1}`, vidPid: '18d1:4ee7', port: index + 1 })));
  const rows = networkDeviceRows(peers, mounts);
  assert.equal(rows.length, 80);
  assert.ok(rows.every(row => row.mountedCount === 4));
  assert.equal(networkDeviceRows(peers, mounts, peers[67].name)[0].ip, peers[67].ip);
  assert.equal(networkDeviceRows(peers, mounts, peers[67].ip)[0].ip, peers[67].ip);
  assert.equal(networkDeviceRows(peers, mounts, '没有此设备').length, 0);
  assert.equal(networkDeviceRows(peers, mounts, '').length, 80, 'Search does not discard sources');
});

test('switching sources never carries another phone export or selection identity', () => {
  const hosts = previewNetworkPeers(12).map(peer => peer.ip);
  const device = { busId: '99-1', vidPid: '18d1:4ee7', name: 'Phone' };
  const mounts = hosts.map((host, index) => ({ ...device, host, port: index + 1 }));
  for (const host of hosts) {
    const rows = sourceDeviceRows(host, { host: hosts[0], devices: [device, { ...device, busId: '99-2' }] }, mounts);
    assert.equal(rows.filter(row => row.attached).length, 1);
    assert.ok(rows.filter(row => row.attached).every(row => row.host === host || host === hosts[0]));
    if (host !== hosts[0]) assert.equal(rows.length, 1, 'Old exports cannot be offered on a newly selected host');
  }
  const remaining = mounts.filter(mount => mount.host !== hosts[7]);
  assert.equal(networkDeviceRows(previewNetworkPeers(12), remaining)[7].mountedCount, 0);
  assert.ok(networkDeviceRows(previewNetworkPeers(12), remaining).filter((_, index) => index !== 7).every(row => row.mountedCount === 1));
});

test('offline sources stay visible without retaining readiness or deleting local mounts', () => {
  const peers = previewNetworkPeers(40);
  const fresh = peers.filter((_, index) => index % 2 === 0);
  const remembered = mergePeerPresence(peers, fresh, { configured: true, running: true, sameNetwork: true });
  assert.equal(remembered.length, 40);
  assert.equal(remembered[1].online, false);
  const mounts = [{ host: peers[1].ip, busId: '99-1', vidPid: '18d1:4ee7', port: 1 }];
  assert.equal(networkDeviceRows(remembered, mounts)[1].mountedCount, 1);
  assert.deepEqual(mergePeerPresence(peers, [], { configured: true, running: true, sameNetwork: false }), []);
});

test('selection requires the same source and USB identity after a refresh', () => {
  const device = { busId: '99-1', vidPid: '18d1:4ee7' };
  const host = '10.0.0.2';
  const selected = new Set([sourceDeviceKey(host, device)]);
  assert.deepEqual([...selectedSourceBusIds(host, [device], selected)], ['99-1']);
  assert.equal(selectedSourceBusIds('10.0.0.3', [device], selected).size, 0);
  assert.equal(selectedSourceBusIds(host, [{ ...device, vidPid: '18d1:4ee8' }], selected).size, 0);
  assert.equal(selectedSourceBusIds(host, [{ ...device, attached: true }], selected).size, 0);
  assert.equal(selectedSourceBusIds(host, [], selected).size, 0);
  assert.equal(selectedSourceBusIds(host, [{ ...device, vidPid: '18D1:4EE7' }], selected).size, 1);
});

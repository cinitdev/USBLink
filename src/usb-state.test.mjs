import test from "node:test";
import assert from "node:assert/strict";
import { sameDevice, applyAttachedState, applySnapshot, createLatestPoller } from "./usb-state.mjs";

const phone = { host: "10.126.126.2", busId: "3-2", vidPid: "18d1:4ee7", name: "Redmi K40" };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

test("connection identity includes host, bus ID and VID/PID", () => {
  assert.equal(sameDevice(phone, { ...phone, vidPid: "18D1:4EE7" }), true);
  assert.equal(sameDevice(phone, { ...phone, host: "10.126.126.3" }), false);
  assert.equal(sameDevice(phone, { ...phone, busId: "3-3" }), false);
  assert.equal(sameDevice(phone, { ...phone, vidPid: "1234:5678" }), false);
});

test("remote discovery uses actual mount state", () => {
  assert.equal(applyAttachedState(phone.host, [phone], [phone])[0].attached, true);
  assert.equal(applyAttachedState("10.126.126.3", [phone], [phone])[0].attached, false);
  assert.equal(applyAttachedState(phone.host, [phone], [])[0].attached, false);
});

test("an in-use device remains visible when omitted by the exporter", () => {
  assert.deepEqual(applyAttachedState(phone.host, [], [phone]), [{ ...phone, attached: true }]);
  assert.deepEqual(applyAttachedState("10.126.126.3", [], [phone]), []);
  assert.equal(applyAttachedState(phone.host, [phone], [phone]).length, 1);
  assert.deepEqual(applyAttachedState(phone.host, [{ ...phone, vidPid: "1234:5678" }], [phone]), [{ ...phone, attached: true }]);
});

test("a confirmed import supersedes old polls and paused refreshes cannot clear it", async () => {
  const old = deferred(), updates = [];
  let reads = 0;
  const poller = createLatestPoller(() => { reads += 1; return old.promise; }, result => updates.push(result));
  const first = poller.refresh();
  await Promise.resolve();
  poller.pause();
  await poller.refresh(true);
  poller.accept([phone]);
  old.resolve([]);
  await first;
  assert.equal(reads, 1);
  assert.deepEqual(updates, [{ ok: true, devices: [phone] }]);
  poller.resume();
  await poller.refresh();
  assert.equal(reads, 2);
  assert.deepEqual(updates.at(-1), { ok: true, devices: [] });
});

test("query failure preserves the last snapshot but marks it unconfirmed", () => {
  const state = { devices: [phone], ready: true, problem: "" };
  const failed = applySnapshot(state, { ok: false, error: "driver unavailable" });
  assert.deepEqual(failed.devices, [phone]);
  assert.equal(failed.problem, "driver unavailable");
  assert.deepEqual(applySnapshot(failed, { ok: true, devices: [] }), { devices: [], ready: true, problem: "" });
});

test("routine polls do not overlap", async () => {
  const query = deferred();
  let reads = 0;
  const poller = createLatestPoller(() => { reads += 1; return query.promise; }, () => {});
  const first = poller.refresh();
  const second = poller.refresh();
  assert.equal(first, second);
  query.resolve([]);
  await first;
  assert.equal(reads, 1);
});

test("late pre-detach success cannot restore disconnected rows", async () => {
  const old = deferred(), latest = deferred(), updates = [];
  let reads = 0;
  const poller = createLatestPoller(() => (++reads === 1 ? old : latest).promise, (result) => updates.push(result));
  const first = poller.refresh();
  await Promise.resolve();
  poller.invalidate();
  const second = poller.refresh(true);
  latest.resolve([]);
  await second;
  old.resolve([phone]);
  await first;
  assert.deepEqual(updates, [{ ok: true, devices: [] }]);
});

test("late error cannot overwrite a newer connected snapshot", async () => {
  const old = deferred(), latest = deferred(), updates = [];
  let reads = 0;
  const poller = createLatestPoller(() => (++reads === 1 ? old : latest).promise, (result) => updates.push(result));
  const first = poller.refresh();
  await Promise.resolve();
  const second = poller.refresh(true);
  latest.resolve([phone]);
  await second;
  old.reject(new Error("timeout"));
  await first;
  assert.deepEqual(updates, [{ ok: true, devices: [phone] }]);
});

test("disposed monitor cannot publish late responses", async () => {
  const query = deferred(), updates = [];
  const poller = createLatestPoller(() => query.promise, (result) => updates.push(result));
  const pending = poller.refresh();
  poller.dispose();
  query.resolve([phone]);
  await pending;
  assert.deepEqual(updates, []);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequestFlow, pollDelay } from '../src/request-flow.mjs';

const state = enabled => ({ sharing: { enabled, state: enabled ? 'sharing' : 'off' }, debug: {}, mesh: {} });
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
function harness(request, beforeAction) {
  const updates = [], errors = [], pending = [];
  const flow = createRequestFlow(request, {
    onStatus: value => updates.push(value), onReadError: error => errors.push(error.message),
    onActionError: error => errors.push(error.message), onPending: action => pending.push(action), beforeAction,
  });
  return { flow, updates, errors, pending };
}

test('a slow poll never delays a click or overwrites its confirmed response', async () => {
  const slow = deferred(), calls = [];
  const h = harness(async action => { calls.push(action); return action === 'status' ? slow.promise : state(true); });
  const read = h.flow.read();
  assert.deepEqual(await h.flow.run('set-sharing', { enabled: true }), state(true));
  assert.deepEqual(calls, ['status', 'set-sharing'], 'One native action; no extra status client');
  slow.resolve(state(false)); await read;
  assert.deepEqual(h.updates, [state(true)], 'Late polling cannot roll the switch back');
  assert.deepEqual(h.pending, ['set-sharing', '']);
});

test('stale read failures cannot turn a successful operation into an error', async () => {
  const old = deferred();
  const h = harness(action => action === 'status' ? old.promise : Promise.resolve(state(true)));
  const read = h.flow.read(); await h.flow.run('set-sharing', { enabled: true });
  old.reject(new Error('old timeout')); await read;
  assert.deepEqual(h.errors, []);
});

test('double clicks are serialized before yielding for paint', async () => {
  const frame = deferred(), calls = [];
  const h = harness(async action => { calls.push(action); return state(true); }, () => frame.promise);
  const first = h.flow.run('set-sharing', { enabled: true });
  assert.deepEqual(h.pending, ['set-sharing'], 'Pending appears before the native bridge');
  assert.equal(await h.flow.run('set-sharing', { enabled: true }), null);
  assert.equal(await h.flow.read(), null);
  assert.deepEqual(calls, []);
  frame.resolve(); await first;
  assert.deepEqual(calls, ['set-sharing']);
});

test('overlapping manual, focus and polling refreshes share a single read', async () => {
  const slow = deferred(); let calls = 0;
  const h = harness(() => { calls++; return slow.promise; });
  const one = h.flow.read(), two = h.flow.read();
  assert.equal(one, two); assert.equal(calls, 1);
  slow.resolve(state(false)); await one;
  assert.equal(h.updates.length, 1);
});

test('a failed mutation refreshes actual state without retrying the mutation', async () => {
  const calls = [];
  const h = harness(async action => { calls.push(action); if (action !== 'status') throw new Error('恢复未完成'); return state(false); });
  assert.equal(await h.flow.run('set-sharing', { enabled: false }), null);
  await h.flow.read();
  assert.deepEqual(calls, ['set-sharing', 'status']);
  assert.deepEqual(h.errors, ['恢复未完成']);
  assert.deepEqual(h.updates, [state(false)]);
});

test('pairing-code response is not cached as status or followed by a redundant read', async () => {
  const calls = [];
  const h = harness(async action => { calls.push(action); return { code: 'private-example' }; });
  assert.deepEqual(await h.flow.run('pairing-code'), { code: 'private-example' });
  assert.deepEqual(calls, ['pairing-code']); assert.deepEqual(h.updates, []);
});

test('unconfirmed responses stay errors and disposed views ignore late completions', async () => {
  const h = harness(async action => action === 'status' ? state(false) : {});
  assert.equal(await h.flow.run('set-sharing', { enabled: true }), null);
  assert.match(h.errors[0], /状态无法确认/);
  const slow = deferred(), disposed = harness(() => slow.promise);
  const read = disposed.flow.read(); disposed.flow.dispose(); slow.resolve(state(true)); await read;
  assert.deepEqual(disposed.updates, []); assert.deepEqual(disposed.errors, []);
});

test('stable pages poll less often, transitions stay responsive and failures back off', () => {
  assert.equal(pollDelay(state(false)), 5000);
  assert.equal(pollDelay(state(true)), 2000);
  assert.equal(pollDelay({ sharing: { state: 'starting' }, mesh: {} }), 2000);
  assert.equal(pollDelay({ sharing: {}, mesh: { configured: true, running: false } }), 2000);
  assert.equal(pollDelay(state(false), 1), 2000);
  assert.equal(pollDelay(state(false), 20), 15000);
});

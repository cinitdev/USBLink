import test from 'node:test';
import assert from 'node:assert/strict';
import { createStatusPolling, createRequestFlow } from '../src/request-flow.mjs';

function clock() {
  let time=0, id=0;const tasks=new Map();
  return { now:()=>time, setTimer:(fn,ms)=>{tasks.set(++id,{fn,at:time+ms});return id;}, clearTimer:id=>tasks.delete(id),
    async advance(ms) { const end=time+ms; while (true) { const next=[...tasks.entries()].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;tasks.delete(next[0]);time=next[1].at;await next[1].fn();await Promise.resolve(); }time=end;await Promise.resolve(); },
  };
}
const state={sharing:{enabled:true},debug:{},mesh:{}};
test('silent polling observes an external connection without a click and pauses while hidden',async()=>{
  const timer=clock();let visible=true, calls=0, observed=[], status=state;
  const flow=createRequestFlow(async()=>{calls++;return status;},{onStatus:s=>observed.push(s),onReadError:()=>{},onPending:()=>{},onActionError:()=>{}});
  const polling=createStatusPolling({...timer,read:flow.read,delay:()=>2000,visible:()=>visible});
  polling.start();await timer.advance(0);assert.equal(calls,1);
  status={...state,sessions:[{id:'new-manual-import'}]};await timer.advance(2000);
  assert.equal(observed.at(-1).sessions.length,1,'Externally created session is observed automatically');
  visible=false;polling.suspend();await timer.advance(20000);assert.equal(calls,2);
  visible=true;polling.wake();await timer.advance(0);assert.equal(calls,3,'Resume reads immediately');
  polling.stop();flow.dispose();await timer.advance(20000);assert.equal(calls,3);
});
test('unanswered observation expires and a failed read is retried without repeating a mutation',async()=>{
  const timer=clock();let expired=0,calls=0,fail=false,polling;
  const flow=createRequestFlow(async action=>{calls++;assert.equal(action,'status');if(fail)throw new Error('timeout');return state;},
    {onStatus:()=>polling.confirmed(),onReadError:()=>{},onPending:()=>{},onActionError:()=>{}});
  polling=createStatusPolling({...timer,read:flow.read,delay:()=>2000,expire:()=>expired++});
  polling.start();await timer.advance(0);fail=true;await timer.advance(10000);
  assert.ok(expired>0,'Cached success expires even though reads fail');assert.ok(calls>1,'Read failures do not permanently stop polling');
  fail=false;await timer.advance(2000);const count=expired;await timer.advance(2000);assert.equal(expired,count,'Confirmed recovery cancels old expiry');
  polling.stop();flow.dispose();
});
test('multiple resume signals share a read and a pending operation keeps its response authoritative',async()=>{
  const timer=clock();let resolve;let reads=0;
  const slow=new Promise(r=>{resolve=r;});const updates=[];
  const flow=createRequestFlow(action=>action==='status'?(reads++,slow):Promise.resolve({...state,sharing:{enabled:false}}),
    {onStatus:s=>updates.push(s),onReadError:()=>{},onPending:()=>{},onActionError:()=>{}});
  const polling=createStatusPolling({...timer,read:flow.read,delay:()=>2000});
  polling.start();polling.wake();polling.wake();
  await flow.run('set-sharing',{enabled:false});resolve(state);
  await timer.advance(0);assert.equal(updates[0].sharing.enabled,false,'Old read cannot roll back the operation');
  polling.stop();flow.dispose();
});

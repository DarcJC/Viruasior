const {test}=require('node:test');
const assert=require('node:assert/strict');
const {inspectContexts}=require('./assert-venus-contexts.cjs');
const context=(index,id)=>`VENUS_CONTEXT_BEGIN index=${index} id=${id}
VENUS_HOST_BEGIN uid=10278 abi=arm64 protocol=1.1.3
${index===1?'VENUS_RENDERER_INITIALIZED=1\n':''}VENUS_DEVICE=Mali-G2-Ultra-NX MC16 type=1 vendor=5045 api=4211033
VENUS_VK_RESULT=vkCreateDevice:0
${Array.from({length:16},(_,i)=>`VENUS_WORD=${i} actual=${index*100+i} expected=${index*100+i}`).join('\n')}
VENUS_COMPUTE_READBACK=PASS words=16
VENUS_CONTEXT_END index=${index} id=${id} exit=0`;
const good=`VENUS_REPEAT_BEGIN count=3
${context(1,1)}
${context(2,2)}
${context(3,1)}
VENUS_HOST_END exit=0
VENUS_REPEAT_END exit=0`;
test('requires three real readbacks with one renderer and recycled context identity',()=>{
  assert.equal(inspectContexts(good,0,10278).passed,true);
});
test('rejects a single historical compute result',()=>{
  assert.equal(inspectContexts(context(1,1)+'\nVENUS_HOST_END exit=0',0,10278).passed,false);
});
test('rejects rebuilding the renderer between contexts and wrong ownership',()=>{
  assert.equal(inspectContexts(good.replace('VENUS_CONTEXT_BEGIN index=2','VENUS_RENDERER_INITIALIZED=1\nVENUS_CONTEXT_BEGIN index=2'),0,10278).passed,false);
  assert.equal(inspectContexts(good.replaceAll('uid=10278','uid=0'),0,10278).passed,false);
});
test('requires independent values and all sixteen GPU words for every context',()=>{
  assert.equal(inspectContexts(good.replace('actual=200 expected=200','actual=0 expected=200'),0,10278).passed,false);
  assert.equal(inspectContexts(good.replace('VENUS_WORD=8 actual=208 expected=208',''),0,10278).passed,false);
  assert.equal(inspectContexts(good.replace(context(3,1),context(1,1).replaceAll('index=1','index=3')),0,10278).passed,false);
});
test('rejects missing completion, native errors and failed process exit',()=>{
  assert.equal(inspectContexts(good.replace('VENUS_REPEAT_END exit=0',''),0,10278).passed,false);
  assert.equal(inspectContexts(good+'\nVENUS_FAILURE stage=cleanup',0,10278).passed,false);
  assert.equal(inspectContexts(good,1,10278).passed,false);
});
test('a later incomplete attempt cannot borrow old success',()=>{
  assert.equal(inspectContexts(good+'\nVENUS_REPEAT_BEGIN count=3\n'+context(1,1),0,10278).passed,false);
});

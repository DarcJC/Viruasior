const fs=require('node:fs');

function inspectContexts(log,exit,uid) {
  const start=log.lastIndexOf('VENUS_REPEAT_BEGIN count=3');
  const text=start>=0 ? log.slice(start) : '';
  const contexts=[...text.matchAll(/VENUS_CONTEXT_BEGIN index=(\d+) id=(\d+)([\s\S]*?)VENUS_CONTEXT_END index=\1 id=\2 exit=(\d+)/g)];
  const values=[];
  const valid=contexts.length===3 && contexts.every((ctx,i)=>{
    const body=ctx[3];
    const words=[...body.matchAll(/VENUS_WORD=(\d+) actual=(\d+) expected=(\d+)/g)];
    values.push(words.map(word=>word[3]).join(','));
    return Number(ctx[1])===i+1 && Number(ctx[2])===[1,2,1][i] && ctx[4]==='0' &&
      new RegExp(`VENUS_HOST_BEGIN uid=${uid}(?:\\s|$)`).test(body) &&
      /VENUS_DEVICE=[^\n]*Mali[^\n]* type=1 vendor=5045(?:\s|$)/.test(body) &&
      /VENUS_VK_RESULT=vkCreateDevice:0(?:\s|$)/.test(body) &&
      /VENUS_COMPUTE_READBACK=PASS words=16(?:\s|$)/.test(body) &&
      words.length===16 && new Set(words.map(word=>word[1])).size===16 &&
      words.every(word=>Number(word[1])<16 && word[2]===word[3]);
  });
  const requirements={
    ordinaryOwner:Number.isInteger(uid) && uid%100000>=10000 && uid%100000<=19999,
    processExit:exit===0,
    singleRenderer:(text.match(/VENUS_RENDERER_INITIALIZED=1/g)||[]).length===1 &&
      !!contexts[0]?.[3].includes('VENUS_RENDERER_INITIALIZED=1'),
    contextReadbacks:valid && (text.match(/VENUS_CONTEXT_BEGIN/g)||[]).length===3 && new Set(values).size===3,
    completed:/VENUS_HOST_END exit=0[\s\S]*VENUS_REPEAT_END exit=0(?:\s|$)/.test(text.slice(contexts.at(-1)?.index??0)),
    noNativeFailure:!/VENUS_FAILURE|CPU_FENCE_FAILED|Fatal signal|VENUS_HOST_END exit=[1-9]/.test(text),
  };
  const missing=Object.keys(requirements).filter(key=>!requirements[key]);
  return {passed:missing.length===0,missing,
    scope:'Direct host Venus context lifetime and compute only; AVF guest graphics/WSI/Surface/input remain separate'};
}
module.exports={inspectContexts};
if(require.main===module) {
  const report=inspectContexts(fs.readFileSync(process.argv[2],'utf8'),Number(process.argv[3]),Number(process.argv[4]));
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
  process.exitCode=report.passed ? 0 : 1;
}

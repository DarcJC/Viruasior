'use strict';
const fs=require('node:fs');
const {resolveApplicationId,parsePackageArgs,validateApplicationId}=require('./application-id.cjs');
function inspectCapture(text,applicationId) {
 validateApplicationId(applicationId);
 const one=expression=>{const m=[...text.matchAll(expression)];return m.length===1?m[0][1]:null;};
 const active=one(/^\s*Pointer Capture: (\w+), seq=\d+\s*$/gm);
 const requested=one(/^\s*Pointer Capture Mode Requested: (\w+)\s*$/gm);
 const owner=one(/^\s*Current Window with Pointer Capture: (.+)\s*$/gm)?.trim();
 return {passed:active==='ABSOLUTE'&&requested==='ABSOLUTE'&&
  /^[a-f0-9]+ /.test(owner??'')&&owner?.slice(owner.indexOf(' ')+1)===`${applicationId}/pro.darc.viruaisor.MainActivity`,active,requested,owner};
}
module.exports={inspectCapture};
if(require.main===module){const {args,packageName}=parsePackageArgs(process.argv.slice(2));if(args.length!==1)throw Error('Expected capture file and optional --package');const report=inspectCapture(fs.readFileSync(args[0],'utf8'),resolveApplicationId({packageName}));console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;}

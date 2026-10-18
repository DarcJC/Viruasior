'use strict';
const fs=require('node:fs');
function inspectCapture(text) {
 const one=expression=>{const m=[...text.matchAll(expression)];return m.length===1?m[0][1]:null;};
 const active=one(/^\s*Pointer Capture: (\w+), seq=\d+\s*$/gm);
 const requested=one(/^\s*Pointer Capture Mode Requested: (\w+)\s*$/gm);
 const owner=one(/^\s*Current Window with Pointer Capture: (.+)\s*$/gm)?.trim();
 return {passed:active==='ABSOLUTE'&&requested==='ABSOLUTE'&&
  /^[a-f0-9]+ pro\.darc\.viruaisor\/pro\.darc\.viruaisor\.MainActivity$/.test(owner??''),active,requested,owner};
}
module.exports={inspectCapture};
if(require.main===module){const report=inspectCapture(fs.readFileSync(process.argv[2],'utf8'));console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;}

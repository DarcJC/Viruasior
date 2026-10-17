const fs=require('node:fs');

function inspectCleanup(log,processes,layers) {
  const decoded=log.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
  const lease=[...decoded.matchAll(/DESKTOP_LEASE_READY token=([0-9a-f]{32}) uid=(\d+) pid=(\d+)/g)].at(-1);
  const session=lease ? decoded.slice(lease.index) : decoded;
  const end=lease && new RegExp(`DESKTOP_LEASE_END token=${lease[1]} native_exit=([0-9]+)(?:\\s|$)`).exec(session);
  const outcome=end ? session.slice(end.index+end[0].length) : '';
  const requirements={
    ownedLease:!!lease && Number(lease[2])%100000>=10000 && Number(lease[2])%100000<=19999,
    stopped:/status=Stopped(?:\s|$)/.test(session),
    matchingEnd:!!end,
    nativeExit:!!end && end[1]==='0',
    rollback:/ROLLBACK=PASS(?:\s|$)/.test(outcome),
    noVmOrHelper:!(/\bcrosvm(?:_|\b)|\bvirtmgr(?:_|\b)|libvenus_overla|pro\.darc\.viruaisor:root:/i.test(processes)),
    noDisplayLayer:!layers.includes('ViruaisorRootDisplay'),
  };
  const missing=Object.keys(requirements).filter(key=>!requirements[key]);
  if (/DESKTOP_LEASE_CLEANUP_FAILED|ROLLBACK=FAIL|detach failed|operation exceeded/i.test(session)) missing.push('cleanupError');
  return {passed:missing.length===0,missing,token:lease?.[1]??null,
    scope:'Lease output and process/layer teardown only; compare external APEX hashes, grants and policy separately'};
}
module.exports={inspectCleanup};
if (require.main===module) {
  const report=inspectCleanup(...process.argv.slice(2,5).map(path=>fs.readFileSync(path,'utf8')));
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
  process.exitCode=report.passed ? 0 : 1;
}

const fs = require('node:fs');

function inspectDesktop(log) {
  const decoded=log.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
  const lease=[...decoded.matchAll(/DESKTOP_LEASE_READY token=([0-9a-f]{32}) uid=(\d+) pid=(\d+)/g)].at(-1);
  const clean=lease ? decoded.slice(lease.index) : decoded;
  const execution=lease && clean.split('\n').find(line=>line.includes(`DESKTOP_EXECUTION_VERIFIED token=${lease[1]}`));
  const plasmaMatch=/VIRUAISOR_PLASMA_HARDWARE_READY=true/.exec(clean);
  const afterPlasma=plasmaMatch ? clean.slice(plasmaMatch.index+plasmaMatch[0].length) : '';
  const syncMatch=/VIRUAISOR_DISPLAY_SYNC_READY ctx=\d+ rings=[1-9]\d*(?=\r?$)/m.exec(afterPlasma);
  const afterSync=syncMatch ? afterPlasma.slice(syncMatch.index+syncMatch[0].length) : '';
  let frameMatch=null;
  for (const candidate of afterSync.matchAll(/FRAME_SOURCE frame=\d+ path=dmabuf-sync-mmap[^\n]*colored=(\d+) opaque=(\d+)[^\n]*hash=[0-9a-f]+/g)) {
    if (Number(candidate[1])>=10000 && Number(candidate[2])>=500000) {
      frameMatch=candidate;
      break;
    }
  }
  const afterFrame=frameMatch ? afterSync.slice(frameMatch.index+frameMatch[0].length) : '';
  const requirements={
    ownedLease:!!lease && Number(lease[2])%100000>=10000 && Number(lease[2])%100000<=19999,
    execution:!!execution && /name=crosvm_default(?:\s|$)/.test(execution) && /caps=0(?:\s|$)/.test(execution),
    venusCapset:/VIRUAISOR_VENUS_RENDERER_READY capset4_size=[1-9][0-9]*/.test(clean),
    venusDriver:/driverID\s*=\s*(?:VK_)?DRIVER_ID_MESA_VENUS|driverName\s*=\s*venus/i.test(clean),
    hardwareGl:/GL_RENDERER=[^\n]*zink[^\n]*mali/i.test(clean),
    shaderReadback:/GPU_DRAW_READBACK_PASS/.test(clean),
    compositorActive:/Compositing is active/.test(clean),
    compositorGl:/Compositing Type:\s*OpenGL/.test(clean),
    compositorRenderer:/OpenGL renderer string:[^\n]*zink[^\n]*mali/i.test(clean),
    plasmaReady:!!plasmaMatch,
    surfaceAttached:/Root Direct Surface attached/.test(clean),
    rendererSynchronized:!!syncMatch,
    postReadyNonBlackFrame:!!frameMatch,
    framePostedAfterSample:/FRAME_POSTED count=[1-9][0-9]*/.test(afterFrame),
  };
  const failed=/llvmpipe|lavapipe|swiftshader|softpipe|GPU_DRAW_READBACK_FAIL|Native window (?:format\/conversion\/post|post) failed|Unqualified AHardwareBuffer scanout|Fatal signal|DESKTOP_LEASE_CLEANUP_FAILED/i.test(clean);
  const missing=Object.keys(requirements).filter(key=>!requirements[key]);
  if (failed) missing.push('errorOrSoftwareRenderer');
  return {passed:missing.length===0,missing,token:lease?.[1]??null,
    scope:'GPU rendering, compositor, renderer synchronization and a post-ready full-size non-black frame sample; exercise input separately'};
}
module.exports={inspectDesktop};
if (require.main === module) {
  const input=process.argv[2]==='-' ? fs.readFileSync(0,'utf8') : fs.readFileSync(process.argv[2],'utf8');
  const report=inspectDesktop(input);
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
  process.exitCode=report.passed ? 0 : 1;
}

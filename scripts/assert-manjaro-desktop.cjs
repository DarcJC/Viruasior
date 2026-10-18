const fs = require('node:fs');

function inspectDesktop(log, pixels) {
  const decoded=log.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g,'');
  const lease=[...decoded.matchAll(/DESKTOP_LEASE_READY token=([0-9a-f]{32}) uid=(\d+) pid=(\d+)/g)].at(-1);
  const clean=lease ? decoded.slice(lease.index) : decoded;
  const execution=lease && clean.split('\n').find(line=>line.includes(`DESKTOP_EXECUTION_VERIFIED token=${lease[1]}`));
  const plasmaMatch=/VIRUAISOR_PLASMA_HARDWARE_READY=true/.exec(clean);
  const afterPlasma=plasmaMatch ? clean.slice(plasmaMatch.index+plasmaMatch[0].length) : '';
  const syncMatch=/VIRUAISOR_DISPLAY_SYNC_READY ctx=\d+ rings=[1-9]\d*(?=\r?$)/m.exec(afterPlasma);
  const frameMatch=/FRAME_POSTED count=[1-9]\d* path=root-surface-control cpuCopies=0 width=[1-9]\d* height=[1-9]\d*/.exec(afterPlasma);
  const requirements={
    ownedLease:!!lease && Number(lease[2])%100000>=10000 && Number(lease[2])%100000<=19999,
    execution:!!execution && /name=crosvm_default(?:\s|$)/.test(execution) && /caps=0(?:\s|$)/.test(execution),
    venusCapset:/VIRUAISOR_VENUS_RENDERER_READY capset4_size=[1-9][0-9]*/.test(clean),
    venusDriver:/driverID\s*=\s*(?:VK_)?DRIVER_ID_MESA_VENUS|driverName\s*=\s*venus/i.test(clean),
    hardwareGl:/GL_RENDERER=[^\n]*zink[^\n]*mali/i.test(clean),
    shaderReadback:/GPU_DRAW_READBACK_PASS/.test(clean),
    compositorActive:/Compositing is active/.test(clean),
    compositorOpenGl:/Compositing Type: OpenGL/.test(clean) && /OpenGL renderer string:[^\n]*zink[^\n]*mali/i.test(clean),
    compositorPolicy:/VIRUAISOR_KWIN_COMPOSITING=opengl/.test(clean),
    plasmaReady:!!plasmaMatch,
    surfaceAttached:/Root Direct Surface attached/.test(clean),
    rendererSynchronized:!!syncMatch,
    postReadyDirectFrame:!!frameMatch,
    visiblePixels:pixels?.passed===true && pixels.leaseToken===lease?.[1] &&
      pixels.samples===400 && pixels.lit>=280 && pixels.colors>=12 && /^[a-f0-9]{64}$/.test(pixels.sha256??''),
    qualifiedPanelComposition:pixels?.composition==='CLIENT',
  };
  const failed=/llvmpipe|lavapipe|swiftshader|softpipe|GPU_DRAW_READBACK_FAIL|Native window (?:format\/conversion\/post|post) failed|Unqualified AHardwareBuffer scanout|Fatal signal|DESKTOP_LEASE_CLEANUP_FAILED|DIRECT_SCANOUT_FALLBACK|cpuCopies=[1-9]|FRAME_SOURCE|KWIN_COMPOSITING=disabled|\[DEBUG-/i.test(clean);
  const missing=Object.keys(requirements).filter(key=>!requirements[key]);
  if (failed) missing.push('errorOrSoftwareRenderer');
  return {passed:missing.length===0,missing,token:lease?.[1]??null,
    scope:'KWin OpenGL on Venus, synchronized direct AHB without CPU copies, and same-session screenshot pixels. Physical panel colours and input require separate verification.'};
}
module.exports={inspectDesktop};
if (require.main === module) {
  const input=process.argv[2]==='-' ? fs.readFileSync(0,'utf8') : fs.readFileSync(process.argv[2],'utf8');
  const pixels=process.argv[3] ? JSON.parse(fs.readFileSync(process.argv[3],'utf8')) : null;
  const report=inspectDesktop(input,pixels);
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
  process.exitCode=report.passed ? 0 : 1;
}

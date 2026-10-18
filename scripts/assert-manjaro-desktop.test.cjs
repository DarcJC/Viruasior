const {test}=require('node:test');
const assert=require('node:assert/strict');
const {inspectDesktop}=require('./assert-manjaro-desktop.cjs');
const token='a'.repeat(32);
const pixels={passed:true,leaseToken:token,samples:400,lit:380,colors:40,sha256:'1'.repeat(64),composition:'CLIENT'};
const positive=`DESKTOP_LEASE_READY token=${token} uid=10278 pid=123
DESKTOP_EXECUTION_VERIFIED token=${token} name=crosvm_default caps=0
VIRUAISOR_VENUS_RENDERER_READY capset4_size=4096
driverID = DRIVER_ID_MESA_VENUS
GL_RENDERER=zink Vulkan Mali
GPU_DRAW_READBACK_PASS
Compositing is active
Compositing Type: OpenGL
OpenGL renderer string: zink Vulkan Venus Mali
VIRUAISOR_KWIN_COMPOSITING=opengl
VIRUAISOR_PLASMA_HARDWARE_READY=true
Root Direct Surface attached
VIRUAISOR_DISPLAY_SYNC_READY ctx=2 rings=1
FRAME_POSTED count=240 path=root-surface-control cpuCopies=0 width=1280 height=720`;
test('requires KWin OpenGL, direct AHB and same-session screenshot pixels',()=>{
  assert.equal(inspectDesktop(positive,pixels).passed,true);
  assert.equal(inspectDesktop(positive.replace('name=crosvm_default caps=0','caps=0 ns=123 name=crosvm_default'),pixels).passed,true);
});
test('every milestone and actual pixel proof are required',()=>{
  for(const line of positive.split('\n')) assert.equal(inspectDesktop(positive.replace(line,''),pixels).passed,false,line);
  for(const proof of [null,{...pixels,leaseToken:'b'.repeat(32)},{...pixels,lit:20},{...pixels,colors:2},{...pixels,passed:false},{...pixels,composition:'DEVICE'}])
    assert.equal(inspectDesktop(positive,proof).passed,false);
});
test('a root VM, stale session and a pre-ready post are rejected',()=>{
  assert.equal(inspectDesktop(positive.replace('uid=10278','uid=0'),pixels).passed,false);
  assert.equal(inspectDesktop(positive+'\nDESKTOP_LEASE_READY token='+ 'b'.repeat(32)+' uid=10278 pid=456',pixels).passed,false);
  const lines=positive.split('\n');const post=lines.pop();
  assert.equal(inspectDesktop(post+'\n'+lines.join('\n'),pixels).passed,false);
});
test('CPU fallbacks, inactive compositing and diagnostic sampling cannot pass',()=>{
  for(const failure of ['DIRECT_SCANOUT_FALLBACK status=-22','cpuCopies=1','FRAME_SOURCE frame=181 path=ahb-lock colored=921600',
    'VIRUAISOR_KWIN_COMPOSITING=disabled','GL_RENDERER=llvmpipe','Native window post failed','[DEBUG-probe]'])
    assert.equal(inspectDesktop(positive+'\n'+failure,pixels).passed,false,failure);
});

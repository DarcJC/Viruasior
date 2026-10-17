const {test}=require('node:test');
const assert=require('node:assert/strict');
const {inspectDesktop}=require('./assert-manjaro-desktop.cjs');
const token='a'.repeat(32);
const positive=`DESKTOP_LEASE_READY token=${token} uid=10278 pid=123
DESKTOP_EXECUTION_VERIFIED token=${token} name=crosvm_default caps=0
VIRUAISOR_VENUS_RENDERER_READY capset4_size=4096
driverID = DRIVER_ID_MESA_VENUS
GL_RENDERER=zink Vulkan Mali
GPU_DRAW_READBACK_PASS
Compositing is active
Compositing Type: OpenGL
OpenGL renderer string: zink Vulkan Mali
VIRUAISOR_PLASMA_HARDWARE_READY=true
Root Direct Surface attached
VIRUAISOR_DISPLAY_SYNC_READY ctx=2 rings=1
FRAME_SOURCE frame=181 path=dmabuf-sync-mmap fd=607 offset=0 stride=5120 fourcc=34325258 colored=123456 opaque=921600 hash=1234567890abcdef
FRAME_POSTED count=240`;
test('requires Venus rendering, Plasma compositing and actual posts in the same owned session',()=>{
  assert.equal(inspectDesktop(positive).passed,true);
});
test('accepts the real native execution field order and rejects root VM ownership',()=>{
  assert.equal(inspectDesktop(positive.replace('name=crosvm_default caps=0','caps=0 ns=123 dev=1 ino=99 name=crosvm_default')).passed,true);
  assert.equal(inspectDesktop(positive.replace('uid=10278','uid=0')).passed,false);
});
test('headless compute and Surface attachment cannot masquerade as a desktop',()=>{
  assert.equal(inspectDesktop('VENUS_GUEST_RESULT=PASS physicalGpu=true\nRoot Direct Surface attached').passed,false);
});
test('each missing graphics milestone rejects a partial result',()=>{
  for (const line of positive.split('\n')) assert.equal(inspectDesktop(positive.replace(line,'')).passed,false,line);
});
test('a stale execution nonce or software renderer is rejected',()=>{
  assert.equal(inspectDesktop(positive.replace(`VERIFIED token=${token}`,`VERIFIED token=${'b'.repeat(32)}`)).passed,false);
  assert.equal(inspectDesktop(positive+'\nGL_RENDERER=llvmpipe').passed,false);
  assert.equal(inspectDesktop(positive+'\nNative window format/conversion/post failed').passed,false);
});
test('graphics from an earlier session cannot satisfy a newly started lease',()=>{
  const later='b'.repeat(32);
  const restart=`\nDESKTOP_LEASE_READY token=${later} uid=10278 pid=456\nDESKTOP_EXECUTION_VERIFIED token=${later} name=crosvm_default caps=0`;
  assert.equal(inspectDesktop(positive+restart).passed,false);
});
test('only a synchronized full-size non-black frame after Plasma readiness passes',()=>{
  assert.equal(inspectDesktop(positive.replace('colored=123456','colored=6580')).passed,false);
  assert.equal(inspectDesktop(positive.replace('opaque=921600','opaque=336')).passed,false);
  const ready='VIRUAISOR_PLASMA_HARDWARE_READY=true';
  const sync='VIRUAISOR_DISPLAY_SYNC_READY ctx=2 rings=1';
  const frame='FRAME_SOURCE frame=181 path=dmabuf-sync-mmap fd=607 offset=0 stride=5120 fourcc=34325258 colored=123456 opaque=921600 hash=1234567890abcdef';
  const reordered=positive.replace(ready,'__READY__').replace(frame,ready).replace('__READY__',frame);
  assert.equal(inspectDesktop(reordered).passed,false);
  assert.equal(inspectDesktop(positive.replace('path=dmabuf-sync-mmap','path=ahb-lock')).passed,false);
});

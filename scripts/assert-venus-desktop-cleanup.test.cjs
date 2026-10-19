const {test}=require('node:test');
const assert=require('node:assert/strict');
const {inspectCleanup:inspect}=require('./assert-venus-desktop-cleanup.cjs');
const inspectCleanup=(...args)=>inspect(...args,'pro.darc.viruaisor');
const token='a'.repeat(32);
const log=`DESKTOP_LEASE_READY token=${token} uid=10278 pid=123
DESKTOP_EXECUTION_VERIFIED token=${token} name=crosvm_default caps=0
status=Stopped detail=VM stopped
DESKTOP_LEASE_END token=${token} native_exit=0
ROLLBACK=PASS`;
const processes='LABEL USER PID NAME\nu:r:untrusted_app:s0 u0_a278 123 pro.darc.viruaisor:venus_vm';
const layers='StatusBar\nNavigationBar';
test('renamed root bridge remains part of the cleanup gate',()=>{
  assert.equal(inspect(log,processes+'\norg.example.renamed:root:libsu',layers,'org.example.renamed').passed,false);
});

test('accepts verified teardown of the latest owned Venus session',()=>{
  assert.equal(inspectCleanup(log,processes,layers).passed,true);
});
test('VM stopped alone cannot prove runtime rollback',()=>{
  for(const omitted of ['DESKTOP_LEASE_END','ROLLBACK=PASS','status=Stopped']) {
    assert.equal(inspectCleanup(log.split('\n').filter(line=>!line.includes(omitted)).join('\n'),processes,layers).passed,false);
  }
});
test('rejects a stale nonce and a failed native exit',()=>{
  assert.equal(inspectCleanup(log.replace(`LEASE_END token=${token}`,`LEASE_END token=${'b'.repeat(32)}`),processes,layers).passed,false);
  assert.equal(inspectCleanup(log.replace('native_exit=0','native_exit=1'),processes,layers).passed,false);
});
test('earlier successful rollback cannot satisfy the latest session',()=>{
  const later='b'.repeat(32);
  assert.equal(inspectCleanup(log+`\nDESKTOP_LEASE_READY token=${later} uid=10278 pid=456\nstatus=Stopped`,processes,layers).passed,false);
});
test('requires rollback after the matching end record and rejects cleanup errors',()=>{
  assert.equal(inspectCleanup('ROLLBACK=PASS\n'+log.replace('\nROLLBACK=PASS',''),processes,layers).passed,false);
  for(const error of ['DESKTOP_LEASE_CLEANUP_FAILED','ROLLBACK=FAIL','detach failed','operation exceeded']) {
    assert.equal(inspectCleanup(log+'\n'+error,processes,layers).passed,false,error);
  }
});
test('rejects remaining VM, native helper, privileged bridge or Surface layer',()=>{
  for(const process of ['crosvm_default','virtmgr','virtmgr_uaisor:venus_vm','libvenus_overlay_probe.so','libvenus_overla','pro.darc.viruaisor:root:libsu']) {
    assert.equal(inspectCleanup(log,processes+'\nu:r:ksu:s0 root 456 '+process,layers).passed,false,process);
  }
  assert.equal(inspectCleanup(log,processes,layers+'\nViruaisorRootDisplay').passed,false);
});
test('does not accept root as the VM owner',()=>{
  assert.equal(inspectCleanup(log.replace('uid=10278','uid=0'),processes,layers).passed,false);
});

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..');
const token='a'.repeat(32);

test('cleanup waits for the current Venus end record after processes have exited',()=>{
  const dir=fs.mkdtempSync(path.join(root,'.pi/device-debug/cleanup-gate-test-'));
  try {
    fs.writeFileSync(path.join(dir,'policy-before.txt'),'Enforcing\nnull\nnull\n');
    fs.writeFileSync(path.join(dir,'logcat.txt'),`DESKTOP_LEASE_READY token=${token} uid=10278 pid=123\n`);
    const trace=path.join(dir,'calls.txt');
    const counter=path.join(dir,'counter.txt');
    const mock=path.join(dir,'mock.cjs');
    const adb=path.join(dir,'adb.sh');
    fs.writeFileSync(adb,'#!/usr/bin/env bash\n"$TEST_NODE" "$TEST_MOCK" "$@"\n',{mode:0o755});
    fs.writeFileSync(mock,`
const fs=require('node:fs');
const args=process.argv.slice(2).join(' ');
fs.appendFileSync(process.env.TEST_TRACE,args+'\\n');
if(args.startsWith('shell date ')) console.log('10-16 04:00:00.000');
else if(args==='shell ps -AZ') console.log('u:r:untrusted_app:s0 u0_a278 123 pro.darc.viruaisor:venus_vm');
else if(args.includes('SurfaceFlinger')) console.log('StatusBar');
else if(args.includes('getenforce')) console.log('Enforcing\\nnull\\nnull');
else if(args.startsWith('logcat ')) {
  const count=(fs.existsSync(process.env.TEST_COUNTER)?Number(fs.readFileSync(process.env.TEST_COUNTER,'utf8')):0)+1;
  fs.writeFileSync(process.env.TEST_COUNTER,String(count));
  console.log('status=Stopped detail=VM stopped');
  if(count>=2) console.log('DESKTOP_LEASE_END token=${token} native_exit=0\\nROLLBACK=PASS');
}
`);
    const bash=process.env.TEST_BASH || (process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
    const result=spawnSync(bash,[path.join(root,'scripts/test-root-display-cleanup.sh').replace(/\\/g,'/'),path.relative(root,dir).replace(/\\/g,'/')],{
      cwd:root,encoding:'utf8',timeout:15000,
      env:{...process.env,PACKAGE:'pro.darc.viruaisor',ADB:adb.replace(/\\/g,'/'),NODE:process.execPath.replace(/\\/g,'/'),
        TEST_NODE:process.execPath.replace(/\\/g,'/'),TEST_MOCK:mock.replace(/\\/g,'/'),TEST_TRACE:trace,TEST_COUNTER:counter},
    });
    if(result.error) throw result.error;
    assert.equal(result.status,0,result.stderr);
    assert.ok(Number(fs.readFileSync(counter,'utf8'))>=2);
    assert.equal(JSON.parse(fs.readFileSync(path.join(dir,'venus-cleanup-report.json'),'utf8')).passed,true);
    assert.match(fs.readFileSync(trace,'utf8'),/DEBUG_STOP/);
  } finally {fs.rmSync(dir,{recursive:true,force:true});}
});

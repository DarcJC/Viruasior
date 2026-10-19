const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const bash=process.env.TEST_BASH||(process.platform==='win32'?'C:/Program Files/Git/bin/bash.exe':'bash');
const portable=value=>value.replace(/\\/g,'/');

test('real smoke script targets a renamed package with the stable activity namespace',()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'viruaisor-app-target-'));
  try {
    const trace=path.join(directory,'calls.txt'),mock=path.join(directory,'mock.cjs'),adb=path.join(directory,'adb.sh');
    fs.writeFileSync(adb,'#!/usr/bin/env bash\n"$TEST_NODE" "$TEST_MOCK" "$@"\n',{mode:0o755});
    fs.writeFileSync(mock,`const fs=require('node:fs');const args=process.argv.slice(2).join(' ');fs.appendFileSync(process.env.TEST_TRACE,args+'\\n');if(args==='exec-out uiautomator dump /dev/tty')console.log('text="Available (capabilities: 0x3)" text="VM stopped"');`);
    const env={...process.env,PACKAGE:'org.example.environment',ADB:portable(adb),NODE:portable(process.execPath),TEST_NODE:portable(process.execPath),TEST_MOCK:portable(mock),TEST_TRACE:trace};
    const result=spawnSync(bash,[portable(path.join(__dirname,'device-smoke.sh')),'--package','org.example.renamed'],{encoding:'utf8',timeout:15000,env});
    assert.equal(result.status,0,result.stderr);
    const calls=fs.readFileSync(trace,'utf8');
    assert.match(calls,/am force-stop org\.example\.renamed/);
    assert.match(calls,/am start -W -n org\.example\.renamed\/pro\.darc\.viruaisor\.MainActivity/);
    assert.match(calls,/pidof org\.example\.renamed:venus_vm/);
    fs.unlinkSync(trace);
    const rejected=spawnSync(bash,[portable(path.join(__dirname,'device-smoke.sh')),'--package','org.example.app;id'],{encoding:'utf8',timeout:15000,env});
    assert.equal(rejected.status,2,rejected.stderr);
    assert.equal(fs.existsSync(trace),false,'invalid package must fail before invoking adb');
  } finally {fs.rmSync(directory,{recursive:true,force:true});}
});

test('Android-only inventory rejects missing or unsafe packages before any operation',()=>{
  for(const packageName of ['', 'org.example.app\nevil', 'org.example.app/../../other', 'org..example']) {
    const result=spawnSync(bash,[portable(path.join(__dirname,'inspect-gpu-guest-offline.sh'))],{encoding:'utf8',timeout:5000,env:{...process.env,PACKAGE:packageName}});
    assert.equal(result.status,2,result.stderr);
    assert.match(result.stderr,/package|applicationId/i);
    assert.doesNotMatch(result.stderr,/mktemp|run-as|mount:/);
  }
});

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {resolveApplicationId,parsePackageArgs,validateApplicationId}=require('./application-id.cjs');

test('runtime package follows APK metadata and validated explicit overrides',()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'viruaisor-package-'));
  const metadataPath=path.join(directory,'output-metadata.json');
  try {
    fs.writeFileSync(metadataPath,JSON.stringify({applicationId:'org.example.renamed'}));
    assert.equal(resolveApplicationId({metadataPath,env:{}}),'org.example.renamed');
    assert.equal(resolveApplicationId({metadataPath,env:{PACKAGE:'org.example.environment'}}),'org.example.environment');
    assert.equal(resolveApplicationId({metadataPath,env:{PACKAGE:'bad;name'},packageName:'org.example.explicit'}),'org.example.explicit');
    for(const applicationId of ['','one','org..app','org.1app','org.app/evil','org.app;id','org.app:root']) {
      assert.throws(()=>validateApplicationId(applicationId));
      assert.throws(()=>resolveApplicationId({metadataPath,env:{PACKAGE:applicationId}}));
    }
    fs.writeFileSync(metadataPath,'{}');
    assert.throws(()=>resolveApplicationId({metadataPath,env:{}}),/applicationId/);
    fs.unlinkSync(metadataPath);
    assert.throws(()=>resolveApplicationId({metadataPath,env:{}}),/Build.*APK|metadata/i);
  } finally {fs.rmSync(directory,{recursive:true,force:true});}
});
test('package option extraction preserves script arguments and rejects ambiguity',()=>{
  assert.deepEqual(parsePackageArgs(['180','--package','org.example.app','--guest-inventory']),{args:['180','--guest-inventory'],packageName:'org.example.app'});
  assert.deepEqual(parsePackageArgs(['--package=org.example.app']),{args:[],packageName:'org.example.app'});
  assert.throws(()=>parsePackageArgs(['--package']),/value/);
  assert.throws(()=>parsePackageArgs(['--package','org.example.app','--package=org.example.other']),/once/);
});

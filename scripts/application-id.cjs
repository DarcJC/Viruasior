'use strict';
const fs=require('node:fs');
const path=require('node:path');
const defaultMetadataPath=path.join(__dirname,'../app/build/outputs/apk/debug/output-metadata.json');

function validateApplicationId(value) {
  if(typeof value!=='string' || value.length>255 || !/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value))
    throw Error('Invalid applicationId: expected Android package segments without paths or shell syntax');
  return value;
}
function parsePackageArgs(input) {
  const args=[];
  let packageName;
  for(let i=0;i<input.length;i++) {
    const arg=input[i];
    if(arg==='--package' || arg.startsWith('--package=')) {
      if(packageName!==undefined) throw Error('--package may appear only once');
      const value=arg==='--package' ? input[++i] : arg.slice('--package='.length);
      if(value===undefined) throw Error('--package requires a value');
      packageName=validateApplicationId(value);
    } else args.push(arg);
  }
  return {args,packageName};
}
function resolveApplicationId({packageName,env=process.env,metadataPath=defaultMetadataPath}={}) {
  if(packageName!==undefined) return validateApplicationId(packageName);
  if(env.PACKAGE!==undefined) return validateApplicationId(env.PACKAGE);
  let metadata;
  try {metadata=JSON.parse(fs.readFileSync(metadataPath,'utf8'));}
  catch(error) {throw Error(`Cannot read APK metadata at ${metadataPath}. Build the debug APK or pass PACKAGE/--package. ${error.message}`);}
  if(typeof metadata.applicationId!=='string') throw Error(`APK metadata has no applicationId: ${metadataPath}`);
  return validateApplicationId(metadata.applicationId);
}
module.exports={resolveApplicationId,parsePackageArgs,validateApplicationId};
if(require.main===module) {
  try {
    const parsed=parsePackageArgs(process.argv.slice(2));
    if(parsed.args.length) throw Error('Usage: node scripts/application-id.cjs [--package application.id]');
    console.log(resolveApplicationId(parsed));
  } catch(error) {console.error(error.message);process.exitCode=2;}
}

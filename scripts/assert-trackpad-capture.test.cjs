'use strict';
const test=require('node:test'), assert=require('node:assert/strict');
const {inspectCapture:inspect}=require('./assert-trackpad-capture.cjs');
const inspectCapture=text=>inspect(text,'pro.darc.viruaisor');
const dump=mode=>`  Pointer Capture: ${mode}, seq=67
  Pointer Capture Mode Requested: ${mode}
  Current Window with Pointer Capture: 2df259e pro.darc.viruaisor/pro.darc.viruaisor.MainActivity`;
test('API 37 relative capture reproduces the physical mouse-only failure',()=>assert.equal(inspectCapture(dump('RELATIVE')).passed,false));
test('absolute capture must be active, requested and owned by this app',()=>{
 assert.equal(inspectCapture(dump('ABSOLUTE')).passed,true);
 for(const text of ['',dump('ABSOLUTE').replace('Requested: ABSOLUTE','Requested: RELATIVE'),
  dump('ABSOLUTE').replace('2df259e pro.darc.viruaisor','2df259e other.app'),
  dump('ABSOLUTE')+'\n  Pointer Capture: RELATIVE, seq=68']) assert.equal(inspectCapture(text).passed,false);
});
test('installed package changes independently of the activity class namespace',()=>{
 const renamed=dump('ABSOLUTE').replace(' pro.darc.viruaisor/',' org.example.renamed/');
 assert.equal(inspect(renamed,'org.example.renamed').passed,true);
 assert.equal(inspect(dump('ABSOLUTE'),'org.example.renamed').passed,false);
});

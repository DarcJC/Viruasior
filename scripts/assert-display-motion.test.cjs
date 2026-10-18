'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {png}=require('./test-fixtures/surface-png.cjs');
const {inspectMotion}=require('./assert-display-motion.cjs');
const surface=` - Output Layer 0x1(ViruaisorRootDisplay#123)
 Region visibleRegion (count=1)
 [0, 0, 400, 300]
 hwc: layer=0x1 composition=CLIENT (1)`;
const frame=(offset,black=false)=>png(400,300,(x,y)=>{
 if(black && ((x>360 && y<30)||(x<70 && y>220))) return [0,0,0];
 if(y>45 && y<90 && x>30+offset && x<150+offset) {
  return [[255,0,0],[0,255,0],[0,0,255]][Math.floor((x-31-offset)/40)];
 }
 return [40,90,100];
});
test('moving colour probe and bright corners pass on the own CLIENT layer',()=>{
 assert.equal(inspectMotion([frame(0),frame(8),frame(16)],surface).passed,true);
});
test('captured corner-block pattern fails despite a healthy centre',()=>{
 assert.equal(inspectMotion([frame(0,true),frame(8,true),frame(16,true)],surface).passed,false);
});
test('static pictures, missing probe, other layers and DEVICE composition fail',()=>{
 for(const [frames,text] of [
  [[frame(0),frame(0),frame(0)],surface],
  [[png(400,300,()=>[40,90,100])],surface],
  [[frame(0),frame(8),frame(16)],surface.replace('ViruaisorRootDisplay','OtherApp')],
  [[frame(0),frame(8),frame(16)],surface.replace('CLIENT','DEVICE')],
 ]) assert.equal(inspectMotion(frames,text).passed,false);
});
test('colour managed RGB strips still qualify as the probe',()=>{
 const tone=data=>png(400,300,(x,y)=>{
  if(y>45&&y<90&&x>30+data&&x<150+data) return [[234,51,35],[117,251,76],[0,0,245]][Math.floor((x-31-data)/40)];
  return [40,90,100];
 });
 assert.equal(inspectMotion([tone(0),tone(8),tone(16)],surface).passed,true);
});

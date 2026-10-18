const {test}=require('node:test');
const assert=require('node:assert/strict');
const zlib=require('node:zlib');
const {inspectSurfacePixels,inspectSurfaceComposition}=require('./assert-surface-pixels.cjs');

function png(width,height,pixel) {
  const header=Buffer.alloc(13); header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=2;
  const chunk=(type,data)=>{const size=Buffer.alloc(4);size.writeUInt32BE(data.length);return Buffer.concat([size,Buffer.from(type),data,Buffer.alloc(4)]);};
  const raw=Buffer.alloc(height*(width*3+1));
  for(let y=0;y<height;y++) for(let x=0;x<width;x++) {
    const color=pixel(x,y);for(let c=0;c<3;c++)raw[y*(width*3+1)+1+x*3+c]=color[c];
  }
  return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
const xml='<hierarchy><node class="pro.darc.viruaisor.display.DesktopSurfaceView" package="pro.darc.viruaisor" bounds="[0,0][100,100]"/></hierarchy>';
test('black, startup logos and a flat placeholder cannot qualify as the desktop',()=>{
  for(const fn of [()=>[0,0,0],()=>[255,255,255],(x,y)=>x>47&&x<53&&y>47&&y<53?[255,255,255]:[0,0,0]])
    assert.equal(inspectSurfacePixels(png(100,100,fn),xml).passed,false);
});
test('samples only the app viewport and requires varied visible pixels',()=>{
  const image=png(100,100,(x,y)=>[x*2,y*2,(x+y)%256]);
  const result=inspectSurfacePixels(image,xml);
  assert.equal(result.passed,true);assert.equal(result.lit,400);assert.ok(result.colors>=12);
  assert.equal(inspectSurfacePixels(png(100,100,(x)=>x<50?[0,0,0]:[255,255,255]),xml.replace('[100,100]','[50,100]')).passed,false);
});
test('ambiguous, missing, out-of-bounds and malformed captures fail closed',()=>{
  const image=png(100,100,()=>[0,0,0]);
  for(const ui of ['',xml.replace('[100,100]','[101,100]'),xml+xml])
    assert.throws(()=>inspectSurfacePixels(image,ui));
  assert.throws(()=>inspectSurfacePixels(Buffer.from('not a PNG'),xml));
});
module.exports={png,xml};
test('physical-output gate checks this root layer, never another client-composed window',()=>{
  const block=type=>` - Output Layer 0x1(ViruaisorRootDisplay#123)\n hwc: layer=0x1 composition=${type} (1)\n`;
  const other=' - Output Layer 0x2(AnotherWindow#456)\n hwc: layer=0x2 composition=CLIENT (1)\n';
  assert.equal(inspectSurfaceComposition(block('CLIENT')+other).passed,true);
  assert.equal(inspectSurfaceComposition(block('DEVICE')+other).passed,false);
  assert.equal(inspectSurfaceComposition(other).passed,false);
  assert.equal(inspectSurfaceComposition(block('CLIENT')+block('CLIENT')).passed,false);
});

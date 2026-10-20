// Check a 1x CDP screenshot of guest/chromium/color-regression.html.
// This catches non-coherent upload corruption in Chromium's GPU Canvas path.
const fs=require('node:fs'),assert=require('node:assert/strict');
const {decodePng}=require('./assert-surface-pixels.cjs');
const im=decodePng(fs.readFileSync(process.argv[2]));
assert.ok(im.width>=1000 && im.height>=1000,'Capture at deviceScaleFactor=1 with at least a 1000x1000 viewport');
const colors=[[255,0,0],[0,255,0],[0,0,255],[255,255,255],[0,0,0],[0,255,255],[255,0,255],[255,255,0]];
const bad=[];let samples=0;
function check(x,y,want){
 const off=(y*im.width+x)*im.channels,got=[...im.pixels.subarray(off,off+3)];samples++;
 if(got.some((v,i)=>Math.abs(v-want[i])>2))bad.push({x,y,got,want});
}
for(let row=0;row<8;row++)for(let col=0;col<8;col++){
 check(60+col*100,60+row*100,colors[(row+col)%8]);
}
for(let col=0;col<8;col++)check(60+col*100,880,colors[col].map(v=>Math.round((v+255)/2)));
for(let y=50;y<700;y+=100)check(5,y,[191,191,255]);
for(let y=60;y<1000;y+=200)check(900,y,[255,255,255]);
console.log(JSON.stringify({samples,mismatches:bad.length,examples:bad.slice(0,5)}));
assert.equal(bad.length,0,'Chromium rendered unexpected colors or geometry');

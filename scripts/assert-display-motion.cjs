#!/usr/bin/env node
'use strict';
// Acceptance for the controlled RGB moving-window probe over the bright wallpaper.
// A stopped VM/app UI or a static screenshot must never qualify as a corner fix.
const fs=require('node:fs');
const {decodePng,inspectSurfaceComposition}=require('./assert-surface-pixels.cjs');
function inspectMotion(pngs,surface) {
 try {
  if(pngs.length<3 || !inspectSurfaceComposition(surface).passed) throw Error('Need three motion frames and one own CLIENT layer');
  const block=surface.split(/(?=\s*- Output Layer )/).find(s=>/\(ViruaisorRootDisplay#\d+\)/.test(s));
  const match=/Region visibleRegion[^\n]*\n\s*\[\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\]/.exec(block??'');
  if(!match) throw Error('Missing own visible bounds');
  const [l,t,r,b]=match.slice(1).map(Number), frames=[];
  for(const data of pngs) {
   const image=decodePng(data);
   if(l>=r || t>=b || r>image.width || b>image.height) throw Error('Invalid visible bounds');
   const rgb=(x,y)=>image.pixels.subarray((y*image.width+x)*image.channels,(y*image.width+x)*image.channels+3);
   const black=(x0,y0,x1,y1)=>{
    let count=0;
    for(let y=0;y<40;y++)for(let x=0;x<40;x++) {
     const c=rgb(l+Math.floor((r-l)*(x0+(x1-x0)*(x+.5)/40)),t+Math.floor((b-t)*(y0+(y1-y0)*(y+.5)/40)));
     if(c[0]+c[1]+c[2]<12)count++;
    }
    return count/1600;
   };
   const counts=[0,0,0];let redX=0;
   for(let y=t;y<b;y+=Math.max(1,Math.floor((b-t)/300)))for(let x=l;x<r;x+=Math.max(1,Math.floor((r-l)/400))) {
    const c=rgb(x,y);
    for(let k=0;k<3;k++)if(c[k]>180&&c[k]>1.8*c[(k+1)%3]&&c[k]>1.8*c[(k+2)%3]) {counts[k]++;if(k===0)redX+=x;}
   }
   frames.push({topRightBlack:black(.91,.035,.985,.085),bottomLeftBlack:black(.025,.76,.16,.89),colors:counts,redX:redX/counts[0]});
  }
  const motion=Math.max(...frames.map(f=>f.redX))-Math.min(...frames.map(f=>f.redX));
  return {passed:motion>=1&&frames.every(f=>Math.max(f.topRightBlack,f.bottomLeftBlack)<.02&&Math.min(...f.colors)>10),bounds:[l,t,r,b],motion,frames};
 } catch(error) {return {passed:false,error:error.message};}
}
module.exports={inspectMotion};
if(require.main===module) {
 const report=inspectMotion(process.argv.slice(3).map(p=>fs.readFileSync(p)),fs.readFileSync(process.argv[2],'utf8'));
 console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;
}

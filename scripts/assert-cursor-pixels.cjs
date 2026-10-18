// Compare opaque guest cursor pixels at the actual hotspot after display scaling.
const fs=require('node:fs');
const {decodePng}=require('./assert-surface-pixels.cjs');
const cursor=JSON.parse(fs.readFileSync(process.argv[2],'utf8').split(/\r?\n/).find(x=>x.startsWith('{')));
const image=decodePng(fs.readFileSync(process.argv[3]));
const guestWidth=Number(process.argv[4]),guestHeight=Number(process.argv[5]);
const bounds=process.argv.slice(6).map(Number);
if(bounds.length!==4 || !guestWidth || !guestHeight) throw Error('Need guest size and actual viewport l,t,r,b');
const [l,t,r,b]=bounds,sx=(r-l)/guestWidth,sy=(b-t)/guestHeight;
let tested=0,matched=0;
for(let y=1;y<cursor.height-1;y++)for(let x=1;x<cursor.width-1;x++) {
 const p=cursor.pixels[y*cursor.width+x]>>>0;
 // Avoid antialiasing/edge interpolation by checking solid, equal neighbours.
 if((p>>>24)!==255 || ![-1,1,-cursor.width,cursor.width].every(d=>(cursor.pixels[y*cursor.width+x+d]>>>0)===p)) continue;
 const px=Math.floor(l+(cursor.x-cursor.xhot+x+.5)*sx),py=Math.floor(t+(cursor.y-cursor.yhot+y+.5)*sy);
 if(px<l || px>=r || py<t || py>=b) continue;
 const i=(py*image.width+px)*image.channels;
 const expected=[p>>>16&255,p>>>8&255,p&255];
 tested++;
 if(expected.every((v,k)=>Math.abs(v-image.pixels[i+k])<=24)) matched++;
}
const report={passed:tested>=10&&matched/tested>=.9,tested,matched,position:[cursor.x,cursor.y],hotspot:[cursor.xhot,cursor.yhot]};
console.log(JSON.stringify(report));process.exitCode=report.passed?0:1;

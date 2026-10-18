const fs=require('node:fs');
const zlib=require('node:zlib');
const crypto=require('node:crypto');

function decodePng(png) {
  if(png.length<33 || png.subarray(0,8).toString('hex')!=='89504e470d0a1a0a') throw Error('Invalid PNG');
  let width=0,height=0,channels=0;
  const compressed=[];
  for(let offset=8;offset+12<=png.length;) {
    const length=png.readUInt32BE(offset), type=png.toString('ascii',offset+4,offset+8);
    if(length>png.length-offset-12) throw Error('Truncated PNG');
    const data=png.subarray(offset+8,offset+8+length);
    if(type==='IHDR') {
      width=data.readUInt32BE(0);height=data.readUInt32BE(4);
      channels=data[9]===2?3:data[9]===6?4:0;
      if(data.length!==13 || data[8]!==8 || !channels || data[10] || data[11] || data[12] ||
         !width || !height || width>16384 || height>16384 || width*height>16_777_216)
        throw Error('Unsupported screenshot PNG');
    } else if(type==='IDAT') compressed.push(data);
    offset+=length+12;
    if(type==='IEND') break;
  }
  if(!channels || !compressed.length) throw Error('Incomplete PNG');
  const stride=width*channels;
  const raw=zlib.inflateSync(Buffer.concat(compressed),{maxOutputLength:(stride+1)*height});
  if(raw.length!==(stride+1)*height) throw Error('Invalid PNG pixel size');
  const pixels=Buffer.alloc(stride*height);
  const paeth=(a,b,c)=>{const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;};
  for(let y=0;y<height;y++) {
    const filter=raw[y*(stride+1)];
    if(filter>4) throw Error('Invalid PNG filter');
    for(let x=0;x<stride;x++) {
      const a=x>=channels?pixels[y*stride+x-channels]:0;
      const b=y?pixels[(y-1)*stride+x]:0;
      const c=y&&x>=channels?pixels[(y-1)*stride+x-channels]:0;
      const prediction=[0,a,b,(a+b)>>1,paeth(a,b,c)][filter];
      pixels[y*stride+x]=(raw[y*(stride+1)+1+x]+prediction)&255;
    }
  }
  return {width,height,channels,pixels};
}

function inspectSurfacePixels(png,xml) {
  const image=decodePng(png);
  const nodes=[...xml.matchAll(/<node\b[^>]*>/g)].map(m=>m[0]).filter(node=>
    /\bpackage="pro\.darc\.viruaisor"/.test(node)&&
    /\bclass="(?:android\.view\.SurfaceView|pro\.darc\.viruaisor\.display\.DesktopSurfaceView)"/.test(node));
  if(nodes.length!==1) throw Error('Expected one visible Viruaisor SurfaceView');
  const match=/\bbounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(nodes[0]);
  if(!match) throw Error('Missing SurfaceView bounds');
  const [left,top,right,bottom]=match.slice(1).map(Number);
  if(left<0 || top<0 || right>image.width || bottom>image.height || right-left<32 || bottom-top<32)
    throw Error('Invalid screenshot viewport');
  let lit=0;
  const colors=new Set();
  for(let y=1;y<=20;y++) for(let x=1;x<=20;x++) {
    const px=left+Math.floor((right-left)*(0.1+0.8*x/21));
    const py=top+Math.floor((bottom-top)*(0.1+0.8*y/21));
    const index=(py*image.width+px)*image.channels;
    const [r,g,b]=image.pixels.subarray(index,index+3);
    if(r+g+b>12) lit++;
    colors.add(`${r>>4},${g>>4},${b>>4}`);
  }
  return {passed:lit>=280&&colors.size>=12,lit,colors:colors.size,samples:400,
    bounds:[left,top,right,bottom],sha256:crypto.createHash('sha256').update(png).digest('hex')};
}
function inspectSurfaceComposition(text) {
  const blocks=[];
  let current=null;
  for(const line of text.split(/\r?\n/)) {
    if(/^\s*- Output Layer /.test(line)) {
      if(current) blocks.push(current);
      const id=/\(ViruaisorRootDisplay#(\d+)\)/.exec(line);
      current=id ? {id:id[1],lines:[]} : null;
    } else if(current) current.lines.push(line);
  }
  if(current) blocks.push(current);
  const type=blocks.length===1 ? /hwc:[^\n]*\bcomposition=(\w+)/.exec(blocks[0].lines.join('\n'))?.[1] : null;
  return {passed:type==='CLIENT',type:type??'unverified'};
}
module.exports={inspectSurfacePixels,decodePng,inspectSurfaceComposition};
if(require.main===module) {
  try {
    const report=inspectSurfacePixels(fs.readFileSync(process.argv[2]),fs.readFileSync(process.argv[3],'utf8'));
    const token=process.argv[4];
    if(token && !/^[a-f0-9]{32}$/.test(token)) throw Error('Invalid lease token');
    report.leaseToken=token??null;
    if(process.argv[5]) {
      const composition=inspectSurfaceComposition(fs.readFileSync(process.argv[5],'utf8'));
      report.composition=composition.type;
      report.passed=report.passed && composition.passed;
    }
    process.stdout.write(JSON.stringify(report)+'\n');
    process.exitCode=report.passed?0:1;
  } catch(error) {process.stdout.write(JSON.stringify({passed:false,error:error.message})+'\n');process.exitCode=1;}
}

const zlib=require('node:zlib');
function png(width,height,pixel) {
  const header=Buffer.alloc(13);header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=2;
  const chunk=(type,data)=>{const size=Buffer.alloc(4);size.writeUInt32BE(data.length);return Buffer.concat([size,Buffer.from(type),data,Buffer.alloc(4)]);};
  const raw=Buffer.alloc(height*(width*3+1));
  for(let y=0;y<height;y++) for(let x=0;x<width;x++) {
    const color=pixel(x,y);for(let c=0;c<3;c++)raw[y*(width*3+1)+1+x*3+c]=color[c];
  }
  return Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
}
const xml='<hierarchy><node class="pro.darc.viruaisor.display.DesktopSurfaceView" package="pro.darc.viruaisor" bounds="[0,0][100,100]"/></hierarchy>';
module.exports={png,xml};

// Check a 1x CDP screenshot of guest/chromium/path-regression.html.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const {decodePng} = require('./assert-surface-pixels.cjs');
const im = decodePng(fs.readFileSync(process.argv[2]));
assert.ok(im.width >= 1000 && im.height >= 1000, 'Capture a 1x viewport of at least 1000x1000');
const points = [
  [80, 140, [255, 0, 0]], [120, 180, [255, 0, 0]], [160, 140, [255, 0, 0]],
  [320, 140, [0, 128, 0]], [360, 180, [0, 128, 0]], [400, 140, [0, 128, 0]],
  [560, 140, [0, 0, 255]], [600, 180, [0, 0, 255]], [640, 140, [0, 0, 255]],
  [120, 350, [0, 0, 0]], [300, 350, [128, 0, 128]],
  [5, 200, [255, 255, 255]], [500, 500, [255, 255, 255]],
];
const bad = points.flatMap(([x, y, want]) => {
  const off = (y * im.width + x) * im.channels;
  const got = [...im.pixels.subarray(off, off + 3)];
  return got.some((v, i) => Math.abs(v - want[i]) > 2) ? [{x, y, got, want}] : [];
});
console.log(JSON.stringify({samples: points.length, mismatches: bad.length, examples: bad.slice(0, 5)}));
assert.equal(bad.length, 0, 'Instanced SVG paths disappeared or rendered with the wrong color');

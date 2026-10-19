// Evaluate real SurfaceFlinger presentation timestamps, not producer callbacks.
const fs = require('node:fs');
const [file, rateText, durationText = '60'] = process.argv.slice(2);
const hz = Number(rateText), duration = Number(durationText);
if (!file || ![60, 120].includes(hz) || !Number.isFinite(duration) || duration < 30)
    throw Error('Usage: node scripts/assert-desktop-cadence.cjs REPORT.json 60|120 [seconds >= 30]');
const report = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const failures = [];
if (!Number.isFinite(report.seconds) || report.seconds < duration - 1)
    failures.push('insufficient capture duration');
if (!Number.isFinite(report.fps) || report.fps < hz * 0.995 || report.fps > hz * 1.005)
    failures.push('presentation rate is outside 0.5% of the target');
if (!Number.isFinite(report.intervalMs?.p99) || report.intervalMs.p99 > 1200 / hz)
    failures.push('at least 1% of intervals exceed the target frame budget by 20%');
console.log(JSON.stringify({passed: failures.length === 0, hz, duration,
    fps: report.fps, p99Ms: report.intervalMs?.p99,
    maxMs: report.intervalMs?.max, over20ms: report.over20ms, failures}, null, 2));
if (failures.length) process.exitCode = 1;

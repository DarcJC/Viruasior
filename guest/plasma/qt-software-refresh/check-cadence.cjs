// Each log is captured after >=22 seconds at the verified mode and contains
// cumulative QML cadence output plus xrandr --current and ps -eo pid,args.
const fs = require('node:fs');
const [rate, ...files] = process.argv.slice(2);
const hz = Number(rate);
if (![60, 120].includes(hz) || !files.length)
    throw Error('Usage: check-cadence.cjs 60|120 log [log ...]');
const results = files.map(file => {
    const data = fs.readFileSync(file, 'utf8');
    if (!data.includes('Loading backend software')) throw Error('Expected software backend: ' + file);
    const samples = [...data.matchAll(/VIRUAISOR_QML_CADENCE swaps=(\d+) changes=(\d+) ms=(\d+)/g)]
        .slice(-3).map(m => ({ swaps: +m[1], changes: +m[2], ms: +m[3] }));
    if (samples.length !== 3) throw Error('Insufficient settled samples: ' + file);
    const pass = samples.every(s => s.ms >= 4500 && s.ms <= 5500
        && s.swaps / s.ms * 1000 >= hz * .9 && s.swaps / s.ms * 1000 <= hz * 1.1);
    return { file, hz, pass, samples };
});
console.log(JSON.stringify(results, null, 2));
if (results.some(r => !r.pass)) process.exitCode = 1;

const fs = require('node:fs');
const assert = require('node:assert/strict');
assert.equal(process.argv.length, 3, 'Usage: node check-client-clock.cjs clock.log');
const log = fs.readFileSync(process.argv[2], 'utf8');
const events = [...log.matchAll(/COMPLETE count=(\d+) kind=0 mode=\d+ ust=(\d+) msc=(\d+)/g)]
    .map(match => ({count: +match[1], ust: BigInt(match[2]), msc: BigInt(match[3])}));
assert(events.length >= 2, 'Need two completed-pixmap clock samples');
for (const event of events) {
    assert(event.ust > 0n && event.msc > 0n, 'X11 completed a frame with a zero presentation clock');
}
for (let index = 1; index < events.length; index++) {
    assert(events[index].ust > events[index - 1].ust && events[index].msc > events[index - 1].msc,
        'Presentation clock did not advance');
}
const qml = [...log.matchAll(/QML_CADENCE swaps=(\d+) changes=\d+ ms=(\d+)/g)]
    .map(match => ({swaps: +match[1], ms: +match[2]}));
assert(qml.length >= 2, 'Need two QML animation timer samples');
for (const sample of qml.slice(1)) {
    assert(sample.ms >= 4500 && sample.ms <= 5500, `Five-second animation took ${sample.ms} real ms`);
}
console.log(JSON.stringify({passed: true, events: events.length, qml}, null, 2));

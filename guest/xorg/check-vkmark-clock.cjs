// Run a windowed MAILBOX clear scene with present-clock-probe.so preloaded.
// This checks the Present protocol; use an uninstrumented run for FPS results.
const fs = require('node:fs');
const assert = require('node:assert/strict');

const log = fs.readFileSync(process.argv[2], 'utf8');
const events = [...log.matchAll(/VIRUAISOR_PRESENT_PROBE COMPLETE count=\d+ kind=0 mode=(\d+) ust=(\d+) msc=(\d+)/g)]
  .map(([, mode, ust, msc]) => ({ mode: Number(mode), ust: BigInt(ust), msc: BigInt(msc) }));
assert(events.length >= 5, 'Need at least five sampled Present completions');
for (let i = 0; i < events.length; i++) {
  const event = events[i];
  assert(event.ust > 0n && event.msc > 0n, 'Windowed Present returned a zero clock');
  if (i) {
    assert(event.ust >= events[i - 1].ust && event.msc >= events[i - 1].msc,
      'Present clock moved backwards');
  }
}
assert(events.at(-1).msc > events[0].msc, 'Present clock did not advance');
assert(events.some(event => event.mode === 2), 'No superseded MAILBOX frame was retired');
assert(/vkmark Score: \d+/.test(log), 'vkmark did not finish');
console.log(`PASS: ${events.length} sampled completions, advancing clock and retired MAILBOX frames`);

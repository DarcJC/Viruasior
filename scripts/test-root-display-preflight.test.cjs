#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

// Drive the real shell entrypoint, but never adb/a device. Only its screen gate is mocked.
const script = path.resolve(__dirname, 'test-root-direct-surface.sh').replace(/\\/g, '/');
const bash = process.env.TEST_BASH || (process.platform === 'win32'
  ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');

function run(windowDump, ready) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'viruaisor-display-gate-'));
  try {
    const trace = path.join(dir, 'calls.txt');
    const mock = path.join(dir, 'mock.cjs');
    const adb = path.join(dir, 'adb.sh');
    fs.writeFileSync(adb, '#!/usr/bin/env bash\n"$TEST_NODE" "$TEST_MOCK" "$@"\n', { mode: 0o755 });
    fs.writeFileSync(mock, `
const fs = require('node:fs');
const args = process.argv.slice(2).join(' ');
fs.appendFileSync(process.env.TEST_TRACE, args + '\\n');
if (args === 'shell dumpsys window policy') console.log(process.env.TEST_WINDOW);
else if (args.startsWith('shell date ')) console.log('10-14 10:50:00.000');
else if (args.includes('getenforce')) console.log('Enforcing\\nnull\\nnull');
else if (args.startsWith('logcat ') && process.env.TEST_READY === 'true') {
  console.log('I ViruaisorRootDisplay: Root Direct Surface attached');
  console.log('I ViruaisorState: Guest agent ready; desktop requested');
}
`);
    const result = spawnSync(bash, [script, '1'], {
      cwd: dir,
      encoding: 'utf8',
      timeout: 15000,
      env: {
        ...process.env,
        ADB: adb.replace(/\\/g, '/'),
        TEST_NODE: process.execPath.replace(/\\/g, '/'),
        TEST_MOCK: mock.replace(/\\/g, '/'),
        TEST_TRACE: trace,
        TEST_WINDOW: windowDump,
        TEST_READY: String(ready),
      },
    });
    if (result.error) throw result.error;
    return { ...result, calls: fs.existsSync(trace) ? fs.readFileSync(trace, 'utf8') : '' };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function windowState(showing, interactive, screen = 'SCREEN_STATE_ON') {
  return `PhoneWindowManager\n    KeyguardServiceDelegate\n      showing=${showing}\n      screenState=${screen}\n      interactiveState=${interactive}\n    Looper state:\n`;
}

for (const [name, dump] of [
  ['sleeping locked device (captured timeout regression)', windowState(true, 'INTERACTIVE_STATE_SLEEP', 'SCREEN_STATE_OFF')],
  ['awake but still locked device', windowState(true, 'INTERACTIVE_STATE_AWAKE')],
  ['unknown window-policy format', 'An unrecognized window policy dump\n'],
  ['screen off despite reported awake/unlocked state', windowState(false, 'INTERACTIVE_STATE_AWAKE', 'SCREEN_STATE_OFF')],
  ['ambiguous repeated keyguard fields', windowState(false, 'INTERACTIVE_STATE_AWAKE').replace('      showing=false', '      showing=true\n      showing=false')],
]) {
  test(name + ' refuses to start a VM', () => {
    const r = run(dump, false);
    assert.equal(r.status, 1, r.stderr);
    assert.match(r.stderr, /screen.*unlock|unlock.*screen/i);
    assert.doesNotMatch(r.calls, /shell am start/);
  });
}

test('awake unlocked device preserves the VM/Surface/desktop checks', () => {
  const r = run(windowState(false, 'INTERACTIVE_STATE_AWAKE'), true);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.calls, /shell dumpsys window policy/);
  assert.match(r.calls, /shell am start.*DEBUG_START/);
  assert.match(r.stdout, /Surface attached and guest desktop requested/);
  assert.match(r.stdout, /NOT YET PROVEN: guest frames visible/);
});

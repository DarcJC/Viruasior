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

function run(windowDump, ready, failure = '', omitDesktopMarker = false) {
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
  console.log('I ViruaisorRootDisplay: DESKTOP_LEASE_READY token=${'a'.repeat(32)} uid=10278 pid=123');
  console.log('I ViruaisorRootDisplay: DESKTOP_EXECUTION_VERIFIED token=${'a'.repeat(32)} name=crosvm_default caps=0');
  console.log('I ViruaisorRootDisplay: Root Direct Surface attached');
  if (process.env.TEST_OMIT_DESKTOP_MARKER !== 'true') {
    console.log('I ViruaisorState: Guest agent ready; desktop requested');
  }
  console.log('I virtmgr: VIRUAISOR_VENUS_RENDERER_READY capset4_size=160');
  console.log('I virtmgr: driverID = DRIVER_ID_MESA_VENUS');
  console.log('I virtmgr: GL_RENDERER=zink Vulkan 1.3 (Virtio-GPU Venus (Mali-G2-Ultra-NX MC16))');
  console.log('I virtmgr: GPU_DRAW_READBACK_PASS');
  console.log('I virtmgr: Compositing is not active');
  console.log('I virtmgr: VIRUAISOR_PLASMA_HARDWARE_READY=true');
  console.log('I virtmgr: VIRUAISOR_KWIN_COMPOSITING=disabled');
  console.log('I ViruaisorVenusRenderer: VIRUAISOR_DISPLAY_SYNC_READY ctx=2 rings=1');
  console.log('I ViruaisorVenusDisplay: FRAME_SOURCE frame=181 path=ahb-image-blast fd=607 offset=0 stride=5120 fourcc=34325258 colored=123456 opaque=921600 hash=1234567890abcdef');
  console.log('I ViruaisorVenusDisplay: FRAME_POSTED count=240');
}
else if (args.startsWith('logcat ') && process.env.TEST_FAILURE) {
  console.log(process.env.TEST_FAILURE);
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
        TEST_FAILURE: failure,
        TEST_OMIT_DESKTOP_MARKER: String(omitDesktopMarker),
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

test('awake unlocked device requires the complete hardware desktop gate', () => {
  const r = run(windowState(false, 'INTERACTIVE_STATE_AWAKE'), true);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.calls, /shell dumpsys window policy/);
  assert.match(r.calls, /shell am start.*DEBUG_START/);
  assert.match(r.stdout, /renderer synchronization and a post-ready non-black Direct Surface frame verified/);
  assert.match(r.stdout, /VISUAL CHECK REQUIRED/);
});

test('Plasma hardware ready satisfies desktop start when early request log was missed', () => {
  const r = run(windowState(false, 'INTERACTIVE_STATE_AWAKE'), true, '', true);
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /renderer synchronization and a post-ready non-black Direct Surface frame verified/);
});

for (const failure of [
  'I virtmgr: MESA: error: ZINK: vkCreateImage failed (VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT)',
  'I virtmgr: (EE) AddScreen/ScreenInit failed for driver 0',
]) {
  test(`captured graphics startup failure exits immediately: ${failure}`, () => {
    const r = run(windowState(false, 'INTERACTIVE_STATE_AWAKE'), false, failure);
    assert.equal(r.status, 1, r.stderr);
    assert.match(r.stderr, /root display\/VM error/i);
    assert.doesNotMatch(r.stderr, /timeout/i);
  });
}

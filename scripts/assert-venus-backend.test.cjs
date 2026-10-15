const test = require('node:test');
const assert = require('node:assert/strict');
const {inspectVenusBackend} = require('./assert-venus-backend.cjs');
const launch = '10-15 01:34:13.926 24789 24794 I virtmgr : Running crosvm with args: ["--gpu=backend=virglrenderer,context-types=venus,egl=true,gles=true,surfaceless=true,vulkan=true"]';
const rejection = '10-15 01:34:13.983 24803 24805 E crosvm  : Failed to create virtio gpu worker thread: invalid rutabaga build parameters';
const exit = '10-15 01:34:14.073 24789 24804 I virtmgr : crosvm(24803) exited with status signal: 6 (SIGABRT)';
test('captures native Venus build rejection despite a green instrumentation wrapper', () => {
    assert.equal(inspectVenusBackend([launch, rejection, exit, 'OK (1 test)'].join('\n')).status, 'blocked-host-renderer');
});
test('successful instrumentation or a different renderer cannot establish Venus availability', () => {
    assert.equal(inspectVenusBackend('OK (1 test)').status, 'inconclusive');
    assert.equal(inspectVenusBackend([launch.replace('virglrenderer', 'gfxstream'), rejection, exit].join('\n')).status, 'inconclusive');
});
test('ambiguous launches and unrelated process exits do not establish the build gap', () => {
    assert.equal(inspectVenusBackend([launch, launch, rejection, exit].join('\n')).status, 'inconclusive');
    assert.equal(inspectVenusBackend([launch, rejection, exit.replace('(24803)', '(999)')].join('\n')).status, 'inconclusive');
});

const test = require('node:test');
const assert = require('node:assert/strict');
const {inspectNativeEnumeration} = require('./assert-guest-vulkan-enumeration.cjs');

const device = `uid=1000(droid) gid=100(users) groups=100(users),991(render)
RENDER_NODE_READ_WRITE
GPU0:
 deviceName = Mali-G2-Ultra-NX MC16
 vendorID = 0x13b5
 driverID = DRIVER_ID_ARM_PROPRIETARY
 deviceType = PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU`;
const good = `DIAG_NATIVE_COMPARE_BEGIN
NATIVE_FULL_GROUPS
${device}
NATIVE_FULL_EXIT=0
NATIVE_RENDER_ONLY
${device}
NATIVE_RENDER_EXIT=0
DIAG_NATIVE_COMPARE_END`;

// Redacted captured regression: diagnostic collector exited 0, both native children crashed.
const crashed = ` DIAG_NATIVE_COMPARE_BEGIN
 NATIVE_FULL_GROUPS
 Segmentation fault
 NATIVE_FULL_EXIT=139
 NATIVE_RENDER_ONLY
 Segmentation fault
 NATIVE_RENDER_EXIT=139
 DIAG_NATIVE_COMPARE_END`;

test('replays native segfaults despite a successful collector', () => {
    const result=inspectNativeEnumeration(crashed);
    assert.equal(result.ok,false);
    assert.match(result.reason,/NATIVE_FULL_EXIT=139/);
});

test('successful enumeration cannot be promoted to workload or presentation', () => {
    const result=inspectNativeEnumeration(good);
    assert.equal(result.ok,true);
    assert.equal(result.scope,'enumeration_only');
    assert.equal(result.workloadProven,false);
    assert.equal(result.presentationProven,false);
});

test('one group control passing cannot hide the other crashing', () => {
    assert.equal(inspectNativeEnumeration(good.replace('NATIVE_RENDER_EXIT=0','NATIVE_RENDER_EXIT=139')).ok,false);
});

test('successful child exits without GPU identities do not pass', () => {
    assert.equal(inspectNativeEnumeration(good.replaceAll(device,'')).ok,false);
});

test('software device with a vendor-like name cannot pass', () => {
    assert.equal(inspectNativeEnumeration(good.replaceAll('PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU','PHYSICAL_DEVICE_TYPE_CPU')).ok,false);
    assert.equal(inspectNativeEnumeration(good.replaceAll('DRIVER_ID_ARM_PROPRIETARY','DRIVER_ID_MESA_LLVMPIPE')).ok,false);
});

test('root or unverified node access cannot pass as the non-root control', () => {
    assert.equal(inspectNativeEnumeration(good.replaceAll('uid=1000(droid)','uid=0(root)')).ok,false);
    assert.equal(inspectNativeEnumeration(good.replaceAll('RENDER_NODE_READ_WRITE','')).ok,false);
});

test('duplicate or conflicting child status is rejected', () => {
    assert.equal(inspectNativeEnumeration(good.replace('NATIVE_FULL_EXIT=0','NATIVE_FULL_EXIT=139\nNATIVE_FULL_EXIT=0')).ok,false);
});

test('device details outside the selected child output cannot substitute', () => {
    assert.equal(inspectNativeEnumeration(device+'\n'+good.replaceAll(device,'')).ok,false);
});

test('multiple devices or incomplete comparison markers fail closed', () => {
    assert.equal(inspectNativeEnumeration(good.replaceAll(device,device+'\nGPU1:')).ok,false);
    assert.equal(inspectNativeEnumeration(good.replace('DIAG_NATIVE_COMPARE_END','')).ok,false);
    assert.equal(inspectNativeEnumeration('DIAG_NATIVE_COMPARE_BEGIN\n'+good).ok,false);
});

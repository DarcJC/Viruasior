#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');
const display = fs.readFileSync(path.join(
  repo, 'native', 'venus-display', 'android_display.cpp'), 'utf8');
const baseline = fs.readFileSync(path.join(
  repo, 'scripts', 'build-venus-crosvm-baseline.sh'), 'utf8');
const connector = fs.readFileSync(path.join(
  repo, 'app', 'src', 'main', 'java', 'pro', 'darc', 'viruaisor',
  'display', 'DisplayBackend.kt'), 'utf8');
const rootService = fs.readFileSync(path.join(
  repo, 'app', 'src', 'main', 'java', 'pro', 'darc', 'viruaisor',
  'display', 'RootDisplayService.kt'), 'utf8');
const rootSurface = fs.readFileSync(path.join(
  repo, 'app', 'src', 'main', 'java', 'pro', 'darc', 'viruaisor',
  'display', 'RootOwnedSurface.kt'), 'utf8');
const desktopSurface = fs.readFileSync(path.join(
  repo, 'app', 'src', 'main', 'java', 'pro', 'darc', 'viruaisor',
  'display', 'DesktopSurfaceView.kt'), 'utf8');

test('root-owned scanout is posted through the visible BLAST producer queue', () => {
  assert.match(display, /#include <android\/surface_control\.h>/);
  assert.match(display, /ASurfaceControl_createFromWindow/);
  assert.match(display, /ASurfaceTransaction_setBuffer/);
  assert.match(display, /ASurfaceTransaction_setGeometry/);
  assert.match(display, /ASURFACE_TRANSACTION_VISIBILITY_SHOW/);
  assert.match(display, /FRAME_POSTED count=%llu path=surface-control/);
  assert.match(display,
    /AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY/,
    'SurfaceFlinger-compatible composer buffers must be accepted');
  assert.match(display, /SURFACE_BUFFER_REJECTED/,
    'descriptor rejection must identify the reason in device logs');
  assert.match(display, /ensureControlLocked/);
  assert.ok((display.match(/ensureControlLocked\(\)/g) || []).length >= 3,
    'both surface attachment and late display configuration must create the control layer');
  assert.match(display, /#include <android\/hardware_buffer_aidl\.h>/);
  assert.match(display, /AParcel_readStrongBinder/);
  assert.match(display, /AIBinder_associateClass\(frame_sink, frame_sink_class\)/,
    'the Java frame sink proxy must be associated before preparing a native transaction');
  assert.match(display, /AHardwareBuffer_writeToParcel/);
  assert.match(display, /FRAME_POSTED count=%llu path=root-surface-control/);
  assert.match(connector, /frameSink: IBinder\? = null/);
  assert.match(rootService, /setSurface\(binder, owned\.surface, frameSink\)/);
  assert.match(rootService, /attachInterface\(null, FRAME_SINK_DESCRIPTOR\)/,
    'the Java frame sink must publish the descriptor expected by the NDK proxy class');
  assert.match(rootService, /code == (?:IBinder\.)?INTERFACE_TRANSACTION[\s\S]*super\.onTransact\(code, data, reply, flags\)/,
    'the sink must answer the descriptor query used by AIBinder_associateClass');
  assert.match(rootService, /data\.enforceInterface\(FRAME_SINK_DESCRIPTOR\)/,
    'the Java frame sink must consume the token emitted by AIBinder_prepareTransaction');
  assert.match(rootService, /HardwareBuffer\.CREATOR\.createFromParcel\(data\)/);
  assert.match(rootService, /owned\.present\(buffer\)/);
  assert.match(rootSurface,
    /setName\("ViruaisorRootDisplayProducer"\)[\s\S]*api\.create\(producerControl, width, height\)/,
    'the BLAST producer must use a layer separate from the direct-buffer fallback');
  assert.doesNotMatch(rootSurface, /api\.create\(control, width, height\)/,
    'SurfaceControl.setBuffer must not target the same layer managed by BLAST');
  assert.match(rootSurface,
    /private val producerControl: SurfaceControl/,
    'the root-owned surface must retain the BLAST producer layer');
  assert.match(rootSurface,
    /setVisibility\(producerControl, true\)/,
    'the layer receiving ANativeWindow posts must be visible');
  assert.match(rootSurface,
    /setVisibility\(child, false\)/,
    'the stale direct-buffer fallback must not cover the BLAST producer');
  assert.match(rootSurface,
    /getMethod\([\s\S]*"setTrustedOverlay", SurfaceControl::class\.java, Boolean::class\.javaPrimitiveType/,
    'the root-owned top layer must be trusted so Android forwards touches to the app window');
  assert.match(rootSurface,
    /setTrustedOverlay\.invoke\(transaction, producerControl, true\)/);
  assert.match(desktopSurface, /setZOrderOnTop\(true\)/,
    'the Direct Surface must be composed above the opaque app window');
  assert.match(baseline, /link-arg=-landroid/);

  const flip = display.slice(display.indexOf('android_display_flip_to_dmabuf'));
  const acquire = flip.indexOf('viruaisor_ahb_acquire_from_fd');
  const submit = flip.indexOf('presentHardwareBufferLocked', acquire);
  const cpuLock = flip.indexOf('AHardwareBuffer_lock', acquire);
  assert.ok(acquire >= 0 && cpuLock > acquire && submit > cpuLock,
    'the synchronized BLAST producer path must run before direct-buffer fallback');
  assert.match(flip.slice(acquire, submit),
    /root_producer[\s\S]*AHardwareBuffer_lock/,
    'a root-owned surface must map and post through its producer queue');
});

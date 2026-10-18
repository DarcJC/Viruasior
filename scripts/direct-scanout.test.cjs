const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const read=p=>fs.readFileSync(require('node:path').join(__dirname,'..',p),'utf8');

test('root scanout submits its AHB before any CPU mapper and has no sampling on the normal path',()=>{
  const source=read('native/venus-display/android_display.cpp');
  const flip=source.slice(source.indexOf('android_display_flip_to_dmabuf'));
  assert.ok(flip.indexOf('presentHardwareBufferLocked') < flip.indexOf('AHardwareBuffer_lock'));
  assert.doesNotMatch(flip,/if \(rgba_image && !root_producer\)/);
  const submit=source.slice(source.indexOf('int presentHardwareBufferLocked'),source.indexOf('int presentLocked'));
  assert.doesNotMatch(submit,/AHardwareBuffer_lock|sampleFrame/);
  assert.match(submit,/ASurfaceTransaction_setBufferWithRelease/);
});

test('guest modesetting preserves the Android attachment',()=>{
  const source=read('native/venus-display/android_display.cpp');
  const destroy=source.slice(source.indexOf('void destroy_android_surface'),source.indexOf('uint32_t get_android_surface_buffer'));
  assert.doesNotMatch(destroy,/surface->detach/);
  const root=read('app/src/main/java/pro/darc/viruaisor/display/RootOwnedSurface.kt');
  const present=root.slice(root.indexOf('fun present'),root.indexOf('fun close'));
  assert.match(present,/setCrop\(child, source\)/,'crop coordinates must be in the AHB pixel space after a modeset');
  assert.doesNotMatch(present,/setCrop\(child, destination\)/);
});

test('displayable AHB allocations include composer usage',()=>{
  assert.match(read('native/venus-crosvm/venus_gralloc.h').split('venus_gralloc_allocate_rgba_image')[1],/AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY/);
});

test('the callback Binder is serialized before the variable-length native buffer payload',()=>{
  const source=read('native/venus-display/android_display.cpp');
  const wire=source.slice(source.indexOf('if (frameSink) {',source.indexOf('int presentHardwareBufferLocked')),source.indexOf('AStatus *header',source.indexOf('int presentHardwareBufferLocked')));
  assert.ok(wire.indexOf('AParcel_writeStrongBinder') < wire.indexOf('AHardwareBuffer_writeToParcel'));
});

test('the root layer compensates the qualified BGRA scanout with a compositor color transform',()=>{
  const root=read('app/src/main/java/pro/darc/viruaisor/display/RootOwnedSurface.kt');
  assert.match(root,/setColorTransform\.invoke\(transaction, child,/);
  assert.match(root,/floatArrayOf\(0f,0f,1f, 0f,1f,0f, 1f,0f,0f\)/);
  assert.match(root,/setCornerRadius\.invoke\(transaction, child, CLIENT_COMPOSITION_RADIUS_PX\)/,
    'keep the affected HWC away from this layer; screenshot-only checks missed its green physical output');
});

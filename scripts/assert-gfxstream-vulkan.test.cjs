const {test} = require('node:test');
const assert = require('node:assert/strict');
const {inspectGfxstreamVulkan} = require('./assert-gfxstream-vulkan.cjs');
const launch = (backend,vulkan=true,contexts=true,glCompositor=true) => `10-14 10:03:36.933 3561 3576 I virtmgr : virtualizationmanager::crosvm: Running crosvm with args: ["--gpu=backend=${backend}${glCompositor ? ',egl=true,gles=true' : ''},surfaceless=true${vulkan ? ',vulkan=true' : ''}${contexts ? ',context-types=gfxstream-gles:gfxstream-vulkan:gfxstream-composer' : ''}"]`;
const initialized = '10-14 10:03:37.103 3587 3607 I GFXSTREAM: Gfxstream initialized successfully!';
const feature = state => `10-14 10:03:37.001 3587 3607 I GFXSTREAM: Vulkan: ${state} (rendererFlags & STREAM_RENDERER_FLAGS_USE_VK_BIT)`;
const trace = (...lines) => lines.join('\n');

test('confirms the host gate without claiming guest GPU execution', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),feature('enabled'),initialized)).ok,true);
});
test('replays device backend-only regression with Vulkan disabled', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream',false),feature('disabled'),initialized)).ok,false);
});
test('the feature log alone cannot replace an explicit Vulkan argument', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream',false),feature('enabled'),initialized)).ok,false);
});
test('requires the compatible GL compositor and an explicit gfxstream capset list', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream',true,true,false),feature('enabled'),initialized)).ok,false);
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream',true,false),feature('enabled'),initialized)).ok,false);
});
test('a 2D backend cannot pass from unrelated gfxstream messages', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('2d'),feature('enabled'),initialized)).ok,false);
});
test('requires actual initialization and the effective Vulkan feature flag', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),feature('enabled'))).ok,false);
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),initialized)).ok,false);
});
test('rejects ambiguous launches and conflicting feature records', () => {
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),launch('gfxstream'),feature('enabled'),initialized)).ok,false);
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),feature('enabled'),feature('disabled'),initialized)).ok,false);
});
test('guest console output cannot substitute for host feature evidence', () => {
    const spoof = line => `I virtmgr : Console(2050): ${line}`;
    assert.equal(inspectGfxstreamVulkan(trace(launch('gfxstream'),spoof(feature('enabled')),spoof(initialized))).ok,false);
    assert.equal(inspectGfxstreamVulkan(trace(spoof(launch('gfxstream')),feature('enabled'),initialized)).ok,false);
});

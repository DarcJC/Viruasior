#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const patch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-memory-requirements-fix.patch',
), 'utf8');
const importPatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-import-memory-type.patch',
), 'utf8');
const resourceImportPatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-resource-import-type.patch',
), 'utf8');
const resourcePnextPatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-resource-pnext.patch',
), 'utf8');
const descriptorTracePatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-descriptor-trace.patch',
), 'utf8');
const candidateTracePatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-image-candidate-trace.patch',
), 'utf8');
const optimalSourcePatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-optimal-source-sharing.patch',
), 'utf8');
const modifierSourcePatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-modifier-source-sharing.patch',
), 'utf8');
const guestLayoutPatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-guest-layout.patch',
), 'utf8');
const hostFormatPatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-ahb-rgba-host-format.patch',
), 'utf8');
const grallocHeader = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'venus_gralloc.h',
), 'utf8');

test('zero-sized Mali AHB requirements are virtualized for the linear guest image', () => {
  const applied = patch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied, /__builtin_mul_overflow/,
    'pixel storage size calculations must reject overflow');
  assert.match(applied, /requirements->size == 0/,
    'nonzero native requirements must remain authoritative');
  assert.match(applied, /requirements->alignment = 4096/,
    'the synthesized allocation must preserve page alignment');
  assert.match(applied, /VIRUAISOR_AHB_IMAGE_REQUIREMENTS_SYNTHETIC/,
    'device evidence must distinguish the compatibility result');
  assert.match(applied, /vkr_android_fix_ahb_image_requirements\(image,[\s\S]*pMemoryRequirements\)/,
    'the legacy query must use the same compatibility rule');
  assert.match(applied, /vkr_android_fix_ahb_image_requirements\([\s\S]*memoryRequirements\)/,
    'the Vulkan 1.1 query used by Zink must use the compatibility rule');
});

test('AHB import uses the memory types reported for the imported buffer', () => {
  const applied = importPatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied, /const uint32_t compatible = properties\.memoryTypeBits/,
    'AHB properties are authoritative because image requirements are undefined before bind');
  assert.doesNotMatch(applied,
    /properties\.memoryTypeBits\s*&\s*requirements\.memoryTypeBits/,
    'the pre-bind image query must not reject an otherwise importable AHB memory type');
});

test('resource-backed AHB import preserves the host memory type through allocation', () => {
  const applied = resourceImportPatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied,
    /driver_mem_type_index = alloc_info->memoryTypeIndex;\s*uses_ahb_import = true;/,
    'the common allocation path must use the type selected from AHB properties');
});

test('image diagnostics expose format, tiling and external handle type', () => {
  assert.match(candidateTracePatch, /VIRUAISOR_IMAGE_CANDIDATE/);
  assert.match(candidateTracePatch, /format=%u tiling=%u usage=%x external=%x/);
});

test('optimal exported BGRA images use formatted AHB storage at the source', () => {
  const applied = optimalSourcePatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied,
    /VK_IMAGE_TILING_LINEAR \|\|\s*args->pCreateInfo->tiling == VK_IMAGE_TILING_OPTIMAL/,
    'both scanout imports and optimal export sources must use image-shaped AHBs');
  assert.match(applied, /VIRUAISOR_AHB_OPTIMAL_SOURCE/,
    'device evidence must identify this source-sharing path');
});

test('DRM modifier export sources are translated without leaking modifier pNext state', () => {
  const applied = modifierSourcePatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied, /VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT/);
  assert.match(applied,
    /vkr_pnext_set_next\(prev_of_modifier,\s*vkr_pnext_get_next\(modifier_info\)\)/,
    'the host AHB create must not receive DRM modifier layout metadata');
  assert.match(applied,
    /if \(modifier_info\)\s*vkr_pnext_set_next\(prev_of_modifier, modifier_info\)/,
    'the guest command must be restored after host image creation');
  assert.match(applied, /VIRUAISOR_AHB_MODIFIER_SOURCE/);
});

test('translated modifier images answer guest layout queries without calling Mali', () => {
  const applied = guestLayoutPatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied, /guest_subresource_layout_valid/);
  assert.match(applied,
    /original_tiling == VK_IMAGE_TILING_LINEAR[\s\S]*guest_modifier_valid && guest_modifier == 0/,
    'linear and DRM_FORMAT_MOD_LINEAR exports must receive a synthetic guest layout');
  assert.match(applied, /UINT64_C\(4\)[\s\S]*UINT64_C\(63\)[\s\S]*~UINT64_C\(63\)/,
    'the synthetic BGRA row pitch must use the device gralloc 64-byte alignment');
  assert.match(applied, /\*args->pLayout = image->guest_subresource_layout;[\s\S]*return;/,
    'legacy layout queries must return before the driver call');
  assert.match(applied,
    /args->pLayout->subresourceLayout = image->guest_subresource_layout;[\s\S]*return;/,
    'layout2 queries must return before the driver call');
  assert.match(applied, /VIRUAISOR_AHB_GUEST_MODIFIER_QUERY/,
    'the original DRM modifier must remain visible to the guest');
});

test('resource-backed AHB imports remove the guest-only resource node', () => {
  const applied = resourcePnextPatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied,
    /vkr_pnext_set_next\(prev_of_res_info, res_info->pNext\);[\s\S]*local_ahb_import\.pNext = alloc_info->pNext;[\s\S]*alloc_info->pNext = &local_ahb_import;/,
    'the guest-only resource node must be removed before AHB import becomes the chain head');
});

test('AHB imports report Android and Vulkan format metadata', () => {
  assert.match(descriptorTracePatch, /AHardwareBuffer_describe/);
  assert.match(descriptorTracePatch, /VkAndroidHardwareBufferFormatPropertiesANDROID/);
  assert.match(descriptorTracePatch, /VIRUAISOR_AHB_DESCRIPTOR/);
});

test('formatted AHBs use the Vulkan-compatible Android RGBA format', () => {
  const applied = hostFormatPatch
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(grallocHeader,
    /\.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM/,
    'the Android allocator must return a core VkFormat instead of an external-only format');
  assert.doesNotMatch(grallocHeader, /AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM/,
    'the implementation-defined Android BGRA value is not importable as a core B8G8R8A8 image');
  assert.match(applied,
    /const VkFormat original_format = create_info->format;/,
    'the guest format must be preserved around host image creation');
  assert.match(applied,
    /create_info->format = VK_FORMAT_R8G8B8A8_UNORM;/,
    'the host Vulkan image must match the RGBA AHardwareBuffer');
  assert.match(applied,
    /create_info->format = original_format;/,
    'guest-visible create info must be restored after the host call');
  assert.match(applied,
    /image->format = VK_FORMAT_R8G8B8A8_UNORM;/,
    'later AHB allocation and import bookkeeping must use the host format');
  assert.match(applied, /VIRUAISOR_AHB_HOST_FORMAT/,
    'device evidence must expose the translated format pair');
});

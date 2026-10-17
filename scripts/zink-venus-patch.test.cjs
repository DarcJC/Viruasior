#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const patchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'zink-venus-upload-coherence.patch');
const hostPatchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'android-ahb-image-sharing.patch');
const tilingPatchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'android-ahb-optimal-tiling.patch');
const hostFormatPatchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'android-ahb-rgba-host-format.patch');
const grallocHeaderPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'venus_gralloc.h');

test('private Zink patch declares linear DMA-BUF images for host AHB translation', () => {
  const source = fs.readFileSync(patchPath, 'utf8');
  const host = fs.readFileSync(hostPatchPath, 'utf8');
  const tiling = fs.readFileSync(tilingPatchPath, 'utf8');
  const hostFormat = fs.readFileSync(hostFormatPatchPath, 'utf8');
  const gralloc = fs.readFileSync(grallocHeaderPath, 'utf8');

  assert.match(source, /diff --git a\/src\/gallium\/drivers\/zink\/zink_resource\.c/);
  assert.match(source, /viruaisor_linear_dmabuf/);
  assert.match(source, /modifier_count = viruaisor_linear_dmabuf \? 0 : 1/);
  assert.match(source, /!screen->driver_workarounds\.can_do_invalid_linear_modifier &&\s*\+\s*!viruaisor_linear_dmabuf/);
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_DMABUF_IMPORT/);
  assert.match(source, /VIRUAISOR_ZINK_IMAGE_CREATE/);
  assert.match(source, /viruaisor_scanout_dmabuf/);
  assert.match(source, /ici\.tiling = VK_IMAGE_TILING_LINEAR/);
  assert.match(source, /alloc_info->export_types = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT/);
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_SCANOUT_EXPORT/);
  assert.match(source, /VIRUAISOR_ZINK_EXTERNAL_IMAGE_DECLARATION/);
  assert.match(source, /if \(viruaisor_import_dmabuf\) \{\s*\+\s*alloc_info->external = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;\s*\+\s*alloc_info->export_types = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT;/);
  assert.match(source, /VIRUAISOR_ZINK_DMA_BUF_IMPORT_HANDLE/);
  assert.match(source, /ici\.pNext = &emici/);
  assert.match(source, /viruaisor_import_dmabuf/);
  assert.match(source, /viruaisor_import_dmabuf[\s\S]*ici\.flags \|= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT/);
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_IMPORT_COMPATIBLE_FLAGS/);
  assert.match(source, /align64\(reqs->size, 64 \* 1024\)/);
  assert.match(source, /zink_bo_create\(screen, allocation_size/);
  assert.match(source, /!alloc_info->whandle[\s\S]*alloc_info->export_types & VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT/);
  assert.match(source, /VIRUAISOR_ZINK_DMABUF_ALLOCATION/);

  assert.match(host, /VK_ANDROID_external_memory_android_hardware_buffer/);
  assert.match(host, /external->handleTypes =\s*\+\s*VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID/);
  assert.match(hostFormat, /venus_gralloc_allocate_rgba_image/);
  assert.match(gralloc, /AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM/);
  assert.match(host, /VkImportAndroidHardwareBufferInfoANDROID/);
  assert.match(host, /viruaisor_ahb_acquire_from_fd/);
  assert.match(host, /VIRUAISOR_AHB_IMAGE_IMPORT/);
  assert.match(tiling, /create_info->tiling = VK_IMAGE_TILING_OPTIMAL/);
  assert.match(tiling, /create_info->tiling = original_tiling/);
  assert.match(tiling, /VIRUAISOR_IMAGE_EXTERNAL_AHB_RESULT/);
});

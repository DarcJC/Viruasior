#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const patchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'zink-venus-upload-coherence.patch');

test('private Zink patch keeps invalid-modifier DMA-BUF imports on linear tiling', () => {
  const source = fs.readFileSync(patchPath, 'utf8');

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
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_SCANOUT_ALLOCATION_ONLY/);
  assert.match(source, /viruaisor_import_dmabuf/);
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_IMPORT_ALLOCATION_ONLY/);
  assert.match(source, /viruaisor_import_dmabuf[\s\S]*ici\.flags \|= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT/);
  assert.match(source, /VIRUAISOR_ZINK_LINEAR_IMPORT_COMPATIBLE_FLAGS/);
  assert.match(source, /align64\(reqs->size, 64 \* 1024\)/);
  assert.match(source, /zink_bo_create\(screen, allocation_size/);
  assert.match(source, /!alloc_info->whandle[\s\S]*alloc_info->export_types & VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT/);
  assert.match(source, /VIRUAISOR_ZINK_DMABUF_ALLOCATION/);
});

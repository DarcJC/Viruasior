#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const patchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'android-ahb-resource-export-chain.patch');
const buildPath = path.resolve(__dirname, 'build-venus-crosvm-candidate.sh');

test('resource AHB imports hide guest export metadata from the Mali allocation chain', () => {
  const source = fs.readFileSync(patchPath, 'utf8');
  const build = fs.readFileSync(buildPath, 'utf8');

  assert.match(source, /uses_ahb_import && export_info && export_info->handleTypes/);
  assert.match(source, /vkr_find_prev_struct\([\s\S]*VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO/);
  assert.match(source, /vkr_pnext_set_next\(prev_of_export_info, export_info->pNext\)/);
  assert.match(source, /VIRUAISOR_AHB_RESOURCE_EXPORT_STRIPPED/);
  assert.doesNotMatch(source, /export_info\s*=\s*NULL/,
    'the renderer must retain the guest export contract for later fd export');
  assert.match(build, /apply_renderer_patch android-ahb-resource-export-chain VIRUAISOR_AHB_RESOURCE_EXPORT_STRIPPED/);
});

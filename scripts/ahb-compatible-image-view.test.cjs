#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const patchPath = path.resolve(__dirname, '..', 'native', 'venus-crosvm',
  'android-ahb-compatible-image-view.patch');
const buildPath = path.resolve(__dirname, 'build-venus-crosvm-candidate.sh');

test('AHB compatible view preserves the guest format on the host view', () => {
  const source = fs.readFileSync(patchPath, 'utf8');
  const build = fs.readFileSync(buildPath, 'utf8');
  const applied = source
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');

  assert.match(applied, /const bool ahb_image =/);
  assert.match(applied, /const VkFormat host_format = guest_format;/);
  assert.match(applied, /VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW/);
  assert.match(build,
    /apply_renderer_patch android-ahb-compatible-image-view VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW src\/venus\/vkr_image\.c/);
});

#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');
const patchPath = path.join(repo, 'native', 'venus-crosvm',
  'android-ahb-image-view-format.patch');
const buildPath = path.join(repo, 'scripts', 'build-venus-crosvm-candidate.sh');

test('AHB host format translation also translates image views', () => {
  const source = fs.readFileSync(patchPath, 'utf8');
  const build = fs.readFileSync(buildPath, 'utf8');

  assert.match(source, /vkr_android_ahb_view_format/);
  assert.match(source, /VK_FORMAT_B8G8R8A8_UNORM[\s\S]*VK_FORMAT_R8G8B8A8_UNORM/);
  assert.match(source, /VK_FORMAT_B8G8R8A8_SRGB[\s\S]*VK_FORMAT_R8G8B8A8_SRGB/);
  assert.match(source, /vkr_image_from_handle\(create_info->image\)/);
  assert.match(source, /create_info->format = host_format/);
  assert.match(source, /create_info->format = guest_format/);
  assert.match(source, /VIRUAISOR_AHB_IMAGE_VIEW/);
  assert.match(source, /VIRUAISOR_AHB_IMAGE_BIND/);
  assert.match(build, /apply_renderer_patch android-ahb-image-view-format VIRUAISOR_AHB_IMAGE_VIEW src\/venus\/vkr_image\.c/);
});

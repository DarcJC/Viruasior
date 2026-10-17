#!/usr/bin/env node
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');
const patchPath = path.join(repo, 'native', 'venus-crosvm',
  'android-ahb-write-path-trace.patch');
const buildPath = path.join(repo, 'scripts', 'build-venus-crosvm-candidate.sh');

test('renderer traces every Vulkan path that can write a translated AHB image', () => {
  const source = fs.readFileSync(patchPath, 'utf8');
  const build = fs.readFileSync(buildPath, 'utf8');

  assert.match(source, /android_hardware_buffer_image/);
  assert.match(source, /image_memory_id/);
  assert.match(source, /VIRUAISOR_AHB_WRITE_COMMAND kind=%s memory=%/);
  assert.match(source, /VIRUAISOR_AHB_WRITE_COMMAND kind=%s/);
  assert.match(source, /"dynamic-rendering"/);
  assert.match(source, /"VIRUAISOR_AHB_WRITE_COMMAND kind=render-pass"/);
  for (const kind of ['copy-image', 'blit-image', 'copy-buffer-image',
    'clear-color', 'resolve-image']) {
    assert.match(source, new RegExp(`"${kind}"`));
  }
  assert.match(build, /apply_renderer_patch android-ahb-write-path-trace VIRUAISOR_AHB_WRITE_COMMAND src\/venus\/vkr_command_buffer\.c/);
});

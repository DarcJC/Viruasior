#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = process.argv[2];
if (!root) {
  console.error('Usage: node scripts/assert-crosvm-gpu-trace.cjs <crosvm-root>');
  process.exit(2);
}

const commandSource = fs.readFileSync(
  path.join(root, 'devices/src/virtio/gpu/mod.rs'), 'utf8');
const scanoutSource = fs.readFileSync(
  path.join(root, 'devices/src/virtio/gpu/virtio_gpu.rs'), 'utf8');
const displaySource = fs.readFileSync(
  path.join(root, 'gpu_display/src/gpu_display_android.rs'), 'utf8');
const rutabagaRoot = path.resolve(root, '..', 'rutabaga');
const rutabagaCoreSource = fs.readFileSync(
  path.join(rutabagaRoot, 'src/rutabaga_core.rs'), 'utf8');
const virglRendererSource = fs.readFileSync(
  path.join(rutabagaRoot, 'src/virgl_renderer.rs'), 'utf8');

for (const operation of ['create', 'destroy', 'attach', 'detach']) {
  assert.match(commandSource,
    new RegExp(`VIRUAISOR_CONTEXT_COMMAND op=${operation}`),
    `missing ${operation} context command trace`);
}
assert.match(commandSource, /VIRUAISOR_CONTEXT_CREATE_FAILED ctx_id=\{\} init=\{:#x\}/,
  'missing error-level context creation trace');
assert.match(commandSource, /VIRUAISOR_BLOB_CREATE_FAILED ctx_id=\{\} resource_id=\{\}/,
  'missing blob creation failure trace');
assert.match(commandSource, /VIRUAISOR_SET_SCANOUT_BLOB_FAILED scanout_id=\{\} resource_id=\{\}/,
  'missing blob scanout failure trace');
assert.match(commandSource, /VIRUAISOR_RESOURCE_FLUSH_FAILED resource_id=\{\}/,
  'missing resource flush failure trace');
assert.match(scanoutSource, /VIRUAISOR_SCANOUT_FLIP resource_id=\{\}/,
  'missing resource-to-import scanout trace');
assert.match(displaySource, /VIRUAISOR_DISPLAY_IMPORT import_id=\{\}/,
  'missing Android display import trace');
assert.match(displaySource, /VIRUAISOR_DISPLAY_FLIP import_id=\{\}/,
  'missing Android display flip trace');
assert.match(rutabagaCoreSource,
  /pub fn display_flush\(&self\)[\s\S]*component\.display_flush\(\)/,
  'missing default renderer display synchronization dispatch');
assert.match(virglRendererSource,
  /fn display_flush\(&self\)[\s\S]*virgl_renderer_context_wait_idle/,
  'missing Venus renderer idle synchronization');
const syncIndex = scanoutSource.indexOf('rutabaga.display_flush()');
const flipIndex = scanoutSource.indexOf('.flip_to(surface_id, import_id');
assert.ok(syncIndex >= 0 && flipIndex > syncIndex,
  'Venus display synchronization must complete before scanout flip');
if (scanoutSource.includes('VIRUAISOR_SCANOUT_IMPORT_GENERATION')) {
  assert.match(scanoutSource,/owner_surface == surface_id/);
}
if (scanoutSource.includes('VIRUAISOR_DISPLAY_IMPORT_RELEASE')) {
  assert.match(displaySource,/fn release_import\(&mut self, import_id: u32, surface_id: u32\)/,
    'DisplayT passes import id first, surface id second');
}
assert.equal((scanoutSource.match(/rutabaga\.display_flush\(\)/g)||[]).length,1,
  'incremental patch application must not duplicate the blocking renderer synchronization');

console.log('PASS: crosvm scanout synchronizes Venus before traced Android display flips');

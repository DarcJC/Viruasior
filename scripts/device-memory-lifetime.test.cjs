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
  'android-memory-detach-free.patch',
), 'utf8');
const lifetimePatch = fs.readFileSync(path.join(
  __dirname,
  '..',
  'native',
  'venus-crosvm',
  'android-shmem-trace.patch',
), 'utf8');

test('freeing exported device memory does not resolve an object after detaching it', () => {
  const freeStart = patch.indexOf('vkr_dispatch_vkFreeMemory');
  const freeBody = patch.slice(freeStart)
    .split('\n')
    .filter((line) => !line.startsWith('-'))
    .join('\n')
    .replace(/^\+/gm, '');
  const driverFreeIndex = freeBody.indexOf('dev->proc_table.FreeMemory');

  assert.match(lifetimePatch, /_mesa_hash_table_remove\(ctx->object_table, entry\)/,
    'exported memory must detach from the shared lookup table before destruction');
  assert.ok(freeStart >= 0 && driverFreeIndex >= 0,
    'the detached allocation must be freed through its already-resolved host handle');
  assert.doesNotMatch(freeBody,
    /vkr_device_memory_destroy_driver_handle\s*\(/,
    'the generated helper performs a second object-table lookup after detach');
  assert.match(freeBody,
    /FreeMemory\(dev->base\.handle\.device,\s*mem->base\.handle\.device_memory,\s*NULL\)/s,
    'the host driver call must use the device and memory handles saved before detach');
});

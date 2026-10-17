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
  'android-memory-publish-order.patch',
), 'utf8');

test('device memory metadata is initialized before the object can be exported', () => {
  const patched = patch
    .split('\n')
    .filter((line) => !line.startsWith('-') && !line.startsWith('+++'))
    .join('\n');

  const create = patched.indexOf('vkr_device_memory_create(ctx, args)');
  const androidBuffer = patched.indexOf('mem->android_buffer = android_buffer');
  const allocationSize = patched.indexOf('mem->allocation_size = alloc_info->allocationSize');
  const publish = patched.indexOf('vkr_device_add_object(ctx, dev, &mem->base)');

  assert.ok(create >= 0, 'allocation must use the unpublished create helper');
  assert.ok(androidBuffer > create, 'the retained AHardwareBuffer must be stored after allocation');
  assert.ok(allocationSize > androidBuffer, 'the Vulkan allocation size must be stored before publication');
  assert.ok(publish > allocationSize, 'object-table publication must be the final initialization step');
  assert.doesNotMatch(patched, /vkr_device_memory_create_and_add\s*\(/,
    'the generated helper exposes a partially initialized object');
});

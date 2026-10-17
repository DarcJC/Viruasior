#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');

const sourcePath = process.argv[2];
if (!sourcePath) {
  console.error('Usage: node scripts/assert-venus-device-teardown-order.cjs <vkr_device.c>');
  process.exit(2);
}

const source = fs.readFileSync(sourcePath, 'utf8');
const start = source.indexOf('vkr_device_destroy(struct vkr_context *ctx');
const end = source.indexOf('\nstatic void\nvkr_dispatch_vkDestroyDevice', start);
assert.ok(start >= 0 && end > start, 'vkr_device_destroy body was not found');
const body = source.slice(start, end);
const cleanup = body.indexOf('vkr_device_object_destroy(ctx, dev, obj)');
const destroy = body.indexOf('mtx_destroy(&dev->object_mutex)');
assert.ok(cleanup >= 0 && destroy >= 0, 'expected object cleanup and mutex destruction');
assert.ok(cleanup < destroy,
  'device object mutex must remain alive while vkr_device_object_destroy removes tracked objects');
console.log('PASS: Venus device object mutex outlives tracked object cleanup');

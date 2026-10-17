#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const repo = path.join(__dirname, '..');
const display = fs.readFileSync(path.join(
  repo, 'native', 'venus-display', 'android_display.cpp'), 'utf8');
const gate = fs.readFileSync(path.join(
  repo, 'scripts', 'assert-manjaro-desktop.cjs'), 'utf8');
const deviceTest = fs.readFileSync(path.join(
  repo, 'scripts', 'test-root-direct-surface.sh'), 'utf8');

test('Android graphics-buffer mapping precedes the generic DMA-BUF fallback', () => {
  const start = display.indexOf('android_display_flip_to_dmabuf');
  const body = display.slice(start);
  const acquire = body.indexOf('viruaisor_ahb_acquire_from_fd');
  const lock = body.indexOf('AHardwareBuffer_lock', acquire);
  const unlock = body.indexOf('AHardwareBuffer_unlock', lock);
  const mmap = body.indexOf('mmap(', unlock);

  assert.ok(start >= 0 && acquire >= 0 && lock > acquire && unlock > lock && mmap > unlock,
    'the Mali gralloc mapper must get first chance to expose completed GPU writes');
  assert.match(body, /description\.format == AHARDWAREBUFFER_FORMAT_BLOB/,
    'the legacy pinned BLOB-backed scanout allocation remains a qualified fallback');
  assert.match(body, /description\.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM/,
    'the Vulkan-compatible shared RGBA image allocation must be accepted by the mapper path');
  assert.doesNotMatch(body, /AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM/,
    'the consumer must not require the external-only Android BGRA format');
  assert.match(body, /AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN/,
    'the AHardwareBuffer lock must match the graphics allocation CPU-read usage');
  assert.match(body, /"ahb-image-lock"/,
    'device evidence must distinguish frames read from the shared graphics image');
  assert.match(body, /"ahb-lock"/,
    'device evidence must retain the legacy BLOB mapper identity');
});

test('strict desktop gates accept the BLAST producer and synchronized mapper fallbacks', () => {
  assert.match(gate, /path=\(\?:ahb-image-blast\|ahb-image-lock\|ahb-lock\|dmabuf-sync-mmap\)/);
  assert.match(deviceTest, /path=\(ahb-image-blast\|ahb-image-lock\|ahb-lock\|dmabuf-sync-mmap\)/);
});

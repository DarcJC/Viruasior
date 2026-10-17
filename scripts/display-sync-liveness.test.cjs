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
  'android-display-sync.patch',
), 'utf8');

test('display refresh drains Venus rings without blocking their queue submissions', () => {
  assert.match(patch, /vkr_context_wait_ring_seqno/,
    'the CPU read must not race commands still queued in the async Venus ring');
  assert.match(patch, /VIRUAISOR_DISPLAY_SYNC_BOUNDED_RING_DRAIN/,
    'the incremental renderer build must distinguish the bounded ring-drain implementation');
  const code = patch.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\+?\s*\/\/.*$/gm, '');
  assert.doesNotMatch(code, /DeviceWaitIdle|QueueWaitIdle|queue->vk_mutex/,
    'blocking a Vulkan device or queue during display refresh can expire the guest ring liveness check');

  const lockIndex = code.indexOf('mtx_lock(&ctx->ring_mutex)');
  const unlockIndex = code.indexOf('mtx_unlock(&ctx->ring_mutex)', lockIndex);
  const waitIndex = code.indexOf('vkr_context_wait_ring_seqno', lockIndex);
  assert.ok(lockIndex >= 0 && unlockIndex > lockIndex && waitIndex > unlockIndex,
    'ring_mutex must be released before waiting for a ring to advance');
  assert.match(patch, /vkr_context_wait_ring_seqno_timeout/,
    'display refresh must stop waiting before a ring command that depends on future work deadlocks');
  assert.match(code, /ret == thrd_timeout\s*\|\|\s*ret == thrd_busy/,
    'Mesa\'s POSIX C11 shim reports pthread condition-variable timeouts as thrd_busy');
  assert.doesNotMatch(code,
    /vkr_context_wait_ring_seqno\(wait->ctx,\s*ring,\s*tail\)/,
    'display refresh must not use the transport protocol\'s unbounded ring wait');

  const normalized = patch.replace(/^\+/gm, '');
  assert.match(normalized,
    /bool\nrender_state_wait_idle\(uint32_t ctx_id\)\n\{\s*struct vkr_renderer_wait \*wait;\s*\{\s*SCOPE_LOCK_RENDERER\(\);\s*wait = vkr_renderer_prepare_wait_idle\(ctx_id\);\s*\}\s*return vkr_renderer_finish_wait_idle\(wait\);\s*\}/,
    'renderer_mutex must protect the snapshot but be released before rings are awaited');
});

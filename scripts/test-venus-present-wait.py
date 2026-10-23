"""Exercise the actual Venus WSI completion function with a fake GPU boundary."""
from pathlib import Path
import subprocess
import sys
import tempfile

source = (Path(sys.argv[1]) / 'src/virtio/vulkan/vn_queue.c').read_text()
start = source.index('vn_queue_wsi_present(struct')
end = source.index('\nstatic VkResult\nvn_queue_submit(', start)
return_type = source[:start].rsplit('\n', 2)[-2].split()[-1]
function = source[start:end]
invoke = ('vn_queue_wsi_present(s); return VK_SUCCESS;' if return_type == 'void'
          else 'return vn_queue_wsi_present(s);')
harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#define VK_SUCCESS 0
#define VK_ERROR_DEVICE_LOST -4
#define VK_NULL_HANDLE 0
#define VN_DEBUG(x) 0
#define vn_log(...) ((void)0)
#define VN_CS_ENCODER_INITIALIZER_LOCAL(data, size) {0}
typedef int VkResult;
typedef uintptr_t VkFence;
typedef void *VkDevice;
struct vk_device { int unused; };
struct vn_renderer { struct { bool has_implicit_fencing; } info; };
struct vn_device { struct { struct vk_device base; } base; struct vn_renderer *renderer; void *primary_ring; };
struct vk_queue { struct { struct vk_device *device; } base; };
struct vn_queue_submission {
 struct vk_queue *queue_handle;
 struct { void *base_bo; } *wsi_mem;
 struct { unsigned ring_idx; bool ring_seqno_valid; uint64_t ring_seqno; } external_payload;
 VkFence fence_handle;
};
struct vn_renderer_submit_batch { unsigned ring_idx; const void *cs_data; unsigned cs_size; };
struct vn_renderer_submit { void **bos; unsigned bo_count; const void *batches; unsigned batch_count; };
struct vn_cs_encoder { int unused; };
static struct vk_queue *vk_queue_from_handle(struct vk_queue *q) { return q; }
static VkDevice vk_device_to_handle(struct vk_device *d) { return d; }
static uint64_t vn_ring_get_id(void *r) { return 0; }
static void vn_encode_vkWaitRingSeqnoMESA(struct vn_cs_encoder *e, int a, uint64_t b, uint64_t c) {}
static unsigned vn_cs_encoder_get_len(struct vn_cs_encoder *e) { return 0; }
static int idle_calls, fence_calls, renderer_calls, completion_result;
static VkFence waited_fence;
static VkResult vn_QueueWaitIdle(struct vk_queue *q) { idle_calls++; return completion_result; }
static VkResult vn_WaitForFences(VkDevice d, unsigned n, const VkFence *f, bool all, uint64_t timeout) {
 assert(n == 1 && all && timeout == UINT64_MAX);
 fence_calls++; waited_fence = *f; return completion_result;
}
static VkResult vn_renderer_submit(struct vn_renderer *r, const struct vn_renderer_submit *s) {
 renderer_calls++; return completion_result;
}
''' + '\nstatic ' + return_type + '\n' + function + r'''
static VkResult invoke(struct vn_queue_submission *s) { ''' + invoke + r''' }
int main(void) {
 struct vn_renderer renderer = {0};
 struct vn_device dev = {.renderer = &renderer};
 struct vk_queue queue = {.base.device = &dev.base.base};
 struct vn_queue_submission s = {.queue_handle = &queue, .fence_handle = 42};
 assert(invoke(&s) == VK_SUCCESS);
 assert(!idle_calls && !fence_calls && !renderer_calls);
 struct { void *base_bo; } memory = {0};
 s.wsi_mem = (void *)&memory;
 assert(invoke(&s) == VK_SUCCESS);
 if (fence_calls != 1 || waited_fence != 42 || idle_calls) {
   puts("FAIL: completed present must wait for its existing fence without a second submission");
   return 1;
 }
 completion_result = VK_ERROR_DEVICE_LOST;
 assert(invoke(&s) == VK_ERROR_DEVICE_LOST);
 s.fence_handle = VK_NULL_HANDLE;
 assert(invoke(&s) == VK_ERROR_DEVICE_LOST);
 assert(idle_calls == 1);
 renderer.info.has_implicit_fencing = true;
 completion_result = VK_SUCCESS;
 assert(invoke(&s) == VK_SUCCESS);
 assert(renderer_calls == 1 && idle_calls == 1 && fence_calls == 2);
 puts("PASS: WSI waits for real completion, handles fence-less submits, and propagates failures");
}
'''
with tempfile.TemporaryDirectory(prefix='viruaisor-present-wait-') as tmp:
    path = Path(tmp)
    (path / 'test.c').write_text(harness)
    subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Wno-unused-parameter',
                    '-Wno-unused-function', str(path / 'test.c'), '-o', str(path / 'test')], check=True)
    subprocess.run([str(path / 'test')], check=True)

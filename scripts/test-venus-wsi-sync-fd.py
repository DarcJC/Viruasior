"""Exercise Venus' real semaphore export and WSI callback selection."""
from pathlib import Path
import subprocess
import sys
import tempfile

directory = Path(sys.argv[1]) / 'src/virtio/vulkan'
queue = (directory / 'vn_queue.c').read_text()
new_helper = 'static VkResult\nvn_get_semaphore_fd('
start = queue.index(new_helper if new_helper in queue else 'VkResult\nvn_GetSemaphoreFdKHR(')
functions = queue[start:queue.index('/* event commands */', start)]
wsi_export = ('vn_wsi_GetSemaphoreFdKHR' if 'vn_wsi_GetSemaphoreFdKHR(' in functions
              else 'vn_GetSemaphoreFdKHR')
harness = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#define VN_USE_WSI_PLATFORM 1
#define VN_TRACE_FUNC() ((void)0)
#define VK_SUCCESS 0
#define VK_ERROR_DEVICE_LOST -4
#define VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT 2
#define VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_RESOURCE_INFO_MESA 3
#define VN_SYNC_TYPE_DEVICE_ONLY 0
#define VN_SYNC_TYPE_IMPORTED_SYNC_FD 1
typedef int VkResult;
struct vn_device;
struct vn_semaphore;
typedef struct vn_device *VkDevice;
typedef struct vn_semaphore *VkSemaphore;
typedef struct { VkSemaphore semaphore; int handleType; } VkSemaphoreGetFdInfoKHR;
typedef struct { int sType; VkSemaphore semaphore; unsigned resourceId; } VkImportSemaphoreResourceInfoMESA;
struct vn_sync_payload { int type, fd; };
struct vn_semaphore { struct vn_sync_payload *payload; struct vn_sync_payload permanent, temporary; int external_payload; };
struct vn_renderer { struct { bool has_implicit_fencing; } info; };
struct vn_physical_device { struct { bool semaphore_exportable, semaphore_importable; } renderer_sync_fd; };
struct vn_device { struct vn_renderer *renderer; struct vn_physical_device *physical_device; void *instance, *primary_ring; };
static int waits, creates, consumes, imports, released_fd, create_result;
static bool desktop_optin;
static struct vn_device *vn_device_from_handle(VkDevice d) { return d; }
static struct vn_semaphore *vn_semaphore_from_handle(VkSemaphore s) { return s; }
static VkResult vn_error(void *instance, VkResult result) { return result; }
static bool vn_desktop_async_sync_fd(struct vn_device *dev) { return desktop_optin; }
static VkResult vn_create_sync_file(struct vn_device *dev, int *payload, int *fd) {
 creates++; if (create_result) return create_result; *fd = 17; return VK_SUCCESS;
}
static int sync_wait(int fd, int timeout) { assert(fd == 17 && timeout == -1); waits++; return 0; }
static void vn_async_vkImportSemaphoreResourceMESA(void *ring, VkDevice d, const VkImportSemaphoreResourceInfoMESA *i) {
 assert(i->resourceId == 0); imports++;
}
static void vn_async_vkWaitSemaphoreResourceMESA(void *ring, VkDevice d, VkSemaphore s) { consumes++; }
static void vn_sync_payload_release(struct vn_device *d, struct vn_sync_payload *p) {
 released_fd = p->fd; p->fd = -1;
}
''' + functions + r'''
int main(void) {
 struct vn_renderer renderer = {0};
 struct vn_physical_device physical = {.renderer_sync_fd = {true, true}};
 struct vn_device dev = {.renderer = &renderer, .physical_device = &physical};
 struct vn_semaphore sem = {.temporary = {VN_SYNC_TYPE_IMPORTED_SYNC_FD, -1}};
 sem.payload = &sem.permanent;
 VkSemaphoreGetFdInfoKHR info = {.semaphore = &sem, .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
 int fd = -1;
 assert(''' + wsi_export + r'''(&dev, &info, &fd) == VK_SUCCESS);
 if (waits || fd != 17 || creates != 1 || consumes != 1) {
   puts("FAIL: WSI must transfer its real pending completion fd without a producer CPU wait"); return 1;
 }
 assert(vn_GetSemaphoreFdKHR(&dev, &info, &fd) == VK_SUCCESS);
 assert(waits == 1 && fd == 17); // ordinary external API retains compatibility
 desktop_optin = true;
 assert(vn_GetSemaphoreFdKHR(&dev, &info, &fd) == VK_SUCCESS);
 assert(waits == 1 && fd == 17);
 desktop_optin = false;
 renderer.info.has_implicit_fencing = true;
 assert(vn_GetSemaphoreFdKHR(&dev, &info, &fd) == VK_SUCCESS);
 assert(waits == 1);
 renderer.info.has_implicit_fencing = false;
 sem.temporary.fd = 23; sem.payload = &sem.temporary;
 assert(''' + wsi_export + r'''(&dev, &info, &fd) == VK_SUCCESS);
 assert(fd == 23 && creates == 4 && imports == 1 && consumes == 5);
 assert(released_fd == -1 && sem.payload == &sem.permanent);
 create_result = VK_ERROR_DEVICE_LOST; fd = -1;
 assert(''' + wsi_export + r'''(&dev, &info, &fd) == VK_ERROR_DEVICE_LOST);
 assert(fd == -1 && consumes == 5 && waits == 1);
 puts("PASS: WSI transfers pending/imported fds; ordinary exports and errors retain their semantics");
}
'''
wsi = (directory / 'vn_wsi.c').read_text()
configuration = wsi[wsi.index('   physical_dev->wsi_device.supports_modifiers = true;'):]
configuration = configuration[:configuration.index('\n   return VK_SUCCESS;')]
selection = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void ordinary(void) {}
static void vn_wsi_GetSemaphoreFdKHR(void) {}
struct wsi_device { bool supports_modifiers, viruaisor_ahb_unorm_wsi; unsigned semaphore_export_handle_types; void (*GetSemaphoreFdKHR)(void); };
struct physical_device { struct wsi_device wsi_device; struct { struct { void *wsi_device; } base; } base; };
static void configure(struct physical_device *physical_dev) {
''' + configuration + r'''
}
int main(void) {
 for (unsigned ahb = 0; ahb < 2; ahb++) {
   for (unsigned option = 0; option < 3; option++) {
     struct physical_device physical = {.wsi_device = {.semaphore_export_handle_types = 2, .GetSemaphoreFdKHR = ordinary}};
     if (ahb) setenv("VIRUAISOR_AHB_UNORM_WSI", "1", 1); else unsetenv("VIRUAISOR_AHB_UNORM_WSI");
     if (!option) unsetenv("VIRUAISOR_WSI_DMA_BUF_SYNC"); else setenv("VIRUAISOR_WSI_DMA_BUF_SYNC", option == 1 ? "1" : "0", 1);
     configure(&physical);
     bool async = ahb && option == 1;
     assert(physical.wsi_device.GetSemaphoreFdKHR == (async ? vn_wsi_GetSemaphoreFdKHR : ordinary));
     assert(physical.wsi_device.semaphore_export_handle_types == (ahb && !async ? 0u : 2u));
   }
 }
 puts("PASS: async callback requires both the paired AHB transport and an exact opt-in");
}
'''
with tempfile.TemporaryDirectory(prefix='viruaisor-wsi-syncfd-') as tmp:
    path = Path(tmp)
    for name, code in [('export', harness), ('selection', selection)]:
        (path / f'{name}.c').write_text(code)
        subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Wno-unused-parameter',
                        '-Wno-unused-function', str(path / f'{name}.c'), '-o', str(path / name)], check=True)
        subprocess.run([str(path / name)], check=True)

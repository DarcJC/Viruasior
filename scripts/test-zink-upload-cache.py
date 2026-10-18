"""Compile the real Zink allocation prefix with minimal Vulkan/screen fixtures.

This tests eligibility after Zink has appended its own Vulkan allocation
structures. It deliberately includes the actual call-site ordering.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit('Usage: test-zink-upload-cache.py MESA_SOURCE')
directory = Path(sys.argv[1]) / 'src/gallium/drivers/zink'
text = (directory / 'zink_bo.c').read_text()
function = text.split('bo_create_internal(', 1)[1].split('\n{', 1)[1]
prefix = function.split('\n   if (!bo)', 1)[0]
source = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "zink_venus_upload_policy.h"
#define VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT 2
#define VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO 1
#define VK_STRUCTURE_TYPE_MEMORY_PRIORITY_ALLOCATE_INFO_EXT 2
#define VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO 3
#define VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT 2
#define VK_MEMORY_PROPERTY_HOST_COHERENT_BIT 4
#define VK_DRIVER_ID_MESA_VENUS 22
#define ZINK_ALLOC_NO_SUBALLOC 1
#define MAX2(a,b) ((a)>(b)?(a):(b))
#define align64(a,b) (((a)+(b)-1)&~((b)-1))
#define get_optimal_alignment(screen,size,alignment) (alignment)
#define mesa_loge(...) ((void)0)
typedef uint32_t VkMemoryPropertyFlags;
struct zink_bo { int unused; };
typedef struct { int sType; const void *pNext; unsigned flags, deviceMask; } VkMemoryAllocateFlagsInfo;
typedef struct { int sType; const void *pNext; float priority; } VkMemoryPriorityAllocateInfoEXT;
typedef struct { int sType; const void *pNext; uint64_t allocationSize; unsigned memoryTypeIndex; } VkMemoryAllocateInfo;
struct zink_screen { struct {
  bool have_KHR_buffer_device_address, have_EXT_memory_priority;
  struct { unsigned driverID; } driver_props;
  struct { unsigned vendorID; struct { unsigned minMemoryMapAlignment; } limits; } props;
  struct { struct { unsigned propertyFlags, heapIndex; } memoryTypes[1]; struct { uint64_t size; } memoryHeaps[1]; } mem_props;
} info; };
static bool eligible(struct zink_screen *screen, const void *pNext, unsigned flags) {
 uint64_t size = 32768;
 unsigned alignment = 4096, mem_type_idx = 0;
''' + prefix + r'''
 return init_pb_cache;
}
int main(void) {
 struct zink_screen screen = {0};
 screen.info.driver_props.driverID = VK_DRIVER_ID_MESA_VENUS;
 screen.info.props.vendorID = 0x13b5;
 screen.info.props.limits.minMemoryMapAlignment = 4096;
 screen.info.mem_props.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
 screen.info.mem_props.memoryHeaps[0].size = 1ull << 30;
 setenv("VIRUAISOR_ZINK_SMALL_UPLOAD_ALLOCATIONS", "1", 1);
 screen.info.have_KHR_buffer_device_address = true;
 if (!eligible(&screen, NULL, 0)) {
   puts("FAIL: ordinary private upload becomes uncacheable after the internal BDA extension");
   return 1;
 }
 screen.info.have_EXT_memory_priority = true;
 assert(eligible(&screen, NULL, 0));
 assert(!eligible(&screen, &screen, 0)); // imported/exported/dedicated caller chain
 assert(!eligible(&screen, NULL, ZINK_ALLOC_NO_SUBALLOC));
 screen.info.mem_props.memoryTypes[0].propertyFlags |= VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
 assert(!eligible(&screen, NULL, 0));
 screen.info.mem_props.memoryTypes[0].propertyFlags = 0;
 assert(!eligible(&screen, NULL, 0));
 screen.info.mem_props.memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
 screen.info.props.vendorID = 0x1234;
 assert(!eligible(&screen, NULL, 0));
 screen.info.props.vendorID = 0x13b5;
 screen.info.driver_props.driverID = 1;
 assert(!eligible(&screen, NULL, 0));
 screen.info.driver_props.driverID = VK_DRIVER_ID_MESA_VENUS;
 setenv("VIRUAISOR_ZINK_SMALL_UPLOAD_ALLOCATIONS", "0", 1);
 assert(!eligible(&screen, NULL, 0));
 unsetenv("VIRUAISOR_ZINK_SMALL_UPLOAD_ALLOCATIONS");
 assert(!eligible(&screen, NULL, 0));
 screen.info.have_KHR_buffer_device_address = false;
 screen.info.have_EXT_memory_priority = false;
 assert(eligible(&screen, NULL, 0)); // original no-extension behavior
 assert(!eligible(&screen, &screen, 0));
 puts("PASS: actual allocation prefix preserves private-upload reuse and external/driver/option boundaries");
}
'''
with tempfile.TemporaryDirectory(prefix='viruaisor-zink-cache-test-') as tmp:
    path = Path(tmp)
    (path/'test.c').write_text(source)
    subprocess.run(['gcc','-std=gnu11','-Wall','-Wextra','-Wno-unused-variable',
                    '-Wno-unused-parameter','-I'+str(directory),str(path/'test.c'),'-o',str(path/'test')],check=True)
    subprocess.run([str(path/'test')],check=True)

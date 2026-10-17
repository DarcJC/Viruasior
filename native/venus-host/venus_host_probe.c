#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <linux/dma-buf.h>
#include <linux/dma-heap.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include "virglrenderer.h"
#include "vn_protocol_driver.h"
#include "probe_shader.h"

static _Atomic uint32_t context_id = 1;
#define CTX context_id
#define REPLY_BYTES 65536
#define REPLY_FLAG VK_COMMAND_GENERATE_REPLY_BIT_EXT
#define HANDLE(type, id) ((type)(uintptr_t)(id))

static pthread_mutex_t fence_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t fence_condition = PTHREAD_COND_INITIALIZER;
static uint64_t completed_fence, next_fence;
static uint8_t command_bytes[REPLY_BYTES];
static struct vn_cs_encoder enc;
static struct vn_cs_decoder dec;
static void *reply_mapping;
static const char *stage = "init";
static bool renderer_initialized;
#ifdef VENUS_TLS_TRACE
/* Diagnostic pass-through: identify the owner of each pthread destructor before
 * a possible ICD-side unload. No key is deleted or destructor suppressed. */
int pthread_key_create(pthread_key_t *key, void (*destructor)(void *)) {
    static int (*real_create)(pthread_key_t *, void (*)(void *));
    if (!real_create) real_create = dlsym(RTLD_NEXT, "pthread_key_create");
    if (!real_create) return ENOSYS;
    int result = real_create(key, destructor);
    if (destructor) {
        Dl_info owner = {0};
        dladdr((void *)destructor, &owner);
        fprintf(stderr, "VENUS_TLS_KEY=%u destructor=%p module=%s offset=%llu\n", result ? UINT32_MAX : *key, (void *)destructor, owner.dli_fname ? owner.dli_fname : "unknown", (unsigned long long)((uintptr_t)destructor - (uintptr_t)owner.dli_fbase));
    }
    return result;
}
#endif
static void on_log(enum virgl_log_level_flags level, const char *message, void *cookie) {
    (void)cookie;
    fprintf(stderr, "VENUS_RENDERER_LOG level=%u %s", level, message);
}

static int sync_dma_buf(int fd, uint64_t flags) {
    struct dma_buf_sync sync = {.flags = flags};
    int result;
    do { result = ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync); } while (result < 0 && errno == EINTR);
    if (result) fprintf(stderr, "VENUS_DMA_SYNC_FAILED flags=%llu errno=%d\n", (unsigned long long)flags, errno);
    return result;
}

static void on_fence(void *cookie, uint32_t ctx, uint32_t ring, uint64_t fence) {
    (void)cookie;
    if (ctx != CTX || ring != 0) return;
    pthread_mutex_lock(&fence_mutex);
    if (fence > completed_fence) completed_fence = fence;
    pthread_cond_broadcast(&fence_condition);
    pthread_mutex_unlock(&fence_mutex);
}

static int submit(void) {
    if (enc.fatal || enc.length % 4) return -1;
    if (virgl_renderer_submit_cmd(enc.data, CTX, (int)(enc.length / 4))) return -1;
    uint64_t fence = ++next_fence;
    if (virgl_renderer_context_create_fence(CTX, 0, 0, fence)) return -1;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 5;
    pthread_mutex_lock(&fence_mutex);
    int result = 0;
    while (completed_fence < fence && result == 0)
        result = pthread_cond_timedwait(&fence_condition, &fence_mutex, &deadline);
    pthread_mutex_unlock(&fence_mutex);
    if (result) { fprintf(stderr, "CPU_FENCE_FAILED=%d\n", result); return -1; }
    dec = (struct vn_cs_decoder){.data = reply_mapping, .size = REPLY_BYTES};
    return 0;
}

static void begin(const char *name) {
    stage = name;
    printf("VENUS_STAGE=%s\n", stage);
    enc = (struct vn_cs_encoder){.data = command_bytes, .capacity = sizeof(command_bytes)};
    vn_encode_vkSeekReplyCommandStreamMESA(&enc, 0, 0);
}

#define REQUIRE(condition) do { if (!(condition)) { fprintf(stderr, "VENUS_FAILURE stage=%s expression=%s\n", stage, #condition); goto cleanup; } } while (0)
#define VOID_CALL(name, ...) do { begin(#name); vn_encode_##name(&enc, REPLY_FLAG, __VA_ARGS__); REQUIRE(submit() == 0); vn_decode_##name##_reply(&dec, __VA_ARGS__); REQUIRE(!dec.fatal); } while (0)
#define VK_CALL(name, ...) do { begin(#name); vn_encode_##name(&enc, REPLY_FLAG, __VA_ARGS__); REQUIRE(submit() == 0); VkResult r = vn_decode_##name##_reply(&dec, __VA_ARGS__); printf("VENUS_VK_RESULT=%s:%d\n", #name, r); REQUIRE(!dec.fatal && r == VK_SUCCESS); } while (0)

static int probe_context(uint32_t ordinal) {
    bool context = false, reply_created = false, memory_created = false, imported_created = false;
    void *data_mapping = NULL;
    int exported_fd = -1, result = 1;
    uint64_t mapped_size = 0;
    printf("VENUS_HOST_BEGIN uid=%u abi=arm64 protocol=1.1.3\n", (unsigned)getuid());
    /* On this OEM stack libcrypto registers a pthread-key destructor, then is
     * unloaded before renderer-thread exit. Pin its code, not the callback/key:
     * preserve normal destruction while keeping the target executable. This
     * compatibility gate is deliberately local to this bounded worker process. */
    void *crypto_pin = dlopen("/system/lib64/libcrypto.so", RTLD_NOW | RTLD_LOCAL | RTLD_NODELETE);
    if (!crypto_pin) fprintf(stderr, "VENUS_CRYPTO_PIN_FAILED=%s\n", dlerror());
    REQUIRE(crypto_pin != NULL);
    printf("VENUS_CRYPTO_MODULE=PINNED_PROCESS_LIFETIME\n");
    virgl_set_log_callback(on_log, NULL, NULL);
    static struct virgl_renderer_callbacks callbacks = {.version = VIRGL_RENDERER_CALLBACKS_VERSION, .write_context_fence = on_fence};
    uint32_t flags = VIRGL_RENDERER_VENUS | VIRGL_RENDERER_NO_VIRGL |
        VIRGL_RENDERER_RENDER_SERVER | VIRGL_RENDERER_THREAD_SYNC |
        VIRGL_RENDERER_ASYNC_FENCE_CB | VIRGL_RENDERER_USE_EXTERNAL_BLOB;
    if (!renderer_initialized) {
        REQUIRE(virgl_renderer_init(NULL, flags, &callbacks) == 0);
        renderer_initialized = true;
        printf("VENUS_RENDERER_INITIALIZED=1\n");
    }
    uint32_t cap_version = 0, cap_size = 0;
    virgl_renderer_get_cap_set(4, &cap_version, &cap_size);
    printf("VENUS_CAPSET=4 version=%u size=%u\n", cap_version, cap_size);
    REQUIRE(cap_size > 0);
    REQUIRE(virgl_renderer_context_create_with_flags(CTX, 4, 14, "viruaisor-host") == 0);
    context = true;
    struct virgl_renderer_resource_create_blob_args reply = {
        .res_handle = 1, .ctx_id = CTX, .blob_mem = VIRGL_RENDERER_BLOB_MEM_HOST3D,
        .blob_flags = VIRGL_RENDERER_BLOB_FLAG_USE_MAPPABLE, .blob_id = 0, .size = REPLY_BYTES,
    };
    REQUIRE(virgl_renderer_resource_create_blob(&reply) == 0);
    reply_created = true;
    REQUIRE(virgl_renderer_resource_map(1, &reply_mapping, &mapped_size) == 0 && mapped_size >= REPLY_BYTES);
    VkCommandStreamDescriptionMESA reply_stream = {.resourceId = 1, .offset = 0, .size = REPLY_BYTES};
    enc = (struct vn_cs_encoder){.data = command_bytes, .capacity = sizeof(command_bytes)};
    vn_encode_vkSetReplyCommandStreamMESA(&enc, 0, &reply_stream);
    REQUIRE(submit() == 0);
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "Viruaisor Venus host", .apiVersion = VK_API_VERSION_1_1};
    VkInstanceCreateInfo instance_info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app};
    VkInstance instance = HANDLE(VkInstance, 1);
    VK_CALL(vkCreateInstance, &instance_info, NULL, &instance);
    uint32_t device_count = 1;
    VkPhysicalDevice physical = HANDLE(VkPhysicalDevice, 2);
    VK_CALL(vkEnumeratePhysicalDevices, instance, &device_count, &physical);
    REQUIRE(device_count == 1);
    VkPhysicalDeviceProperties properties = {0};
    VOID_CALL(vkGetPhysicalDeviceProperties, physical, &properties);
    printf("VENUS_DEVICE=%s type=%u vendor=%u api=%u\n", properties.deviceName, properties.deviceType, properties.vendorID, properties.apiVersion);
    REQUIRE(properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU && properties.vendorID == 0x13b5 && strstr(properties.deviceName, "Mali"));
    uint32_t family_count = 32;
    VkQueueFamilyProperties families[32] = {0};
    VOID_CALL(vkGetPhysicalDeviceQueueFamilyProperties, physical, &family_count, families);
    uint32_t family = UINT32_MAX;
    for (uint32_t i = 0; i < family_count; i++) if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { family = i; break; }
    REQUIRE(family != UINT32_MAX);
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_create = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority};
    VkDeviceCreateInfo device_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_create};
    VkDevice device = HANDLE(VkDevice, 3);
    VK_CALL(vkCreateDevice, physical, &device_info, NULL, &device);
    VkDeviceQueueTimelineInfoMESA timeline = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_TIMELINE_INFO_MESA, .ringIdx = 1};
    VkDeviceQueueInfo2 queue_info = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_INFO_2, .pNext = &timeline, .queueFamilyIndex = family};
    VkQueue queue = HANDLE(VkQueue, 4);
    VOID_CALL(vkGetDeviceQueue2, device, &queue_info, &queue);
    VkPhysicalDeviceExternalBufferInfo external_query = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO, .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkExternalBufferProperties external_properties = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES};
    VOID_CALL(vkGetPhysicalDeviceExternalBufferProperties, physical, &external_query, &external_properties);
    printf("VENUS_EXTERNAL_BUFFER features=%u compatible=%u exportFromImported=%u\n", external_properties.externalMemoryProperties.externalMemoryFeatures, external_properties.externalMemoryProperties.compatibleHandleTypes, external_properties.externalMemoryProperties.exportFromImportedHandleTypes);
    external_query.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    VOID_CALL(vkGetPhysicalDeviceExternalBufferProperties, physical, &external_query, &external_properties);
    printf("VENUS_EXTERNAL_OPAQUE features=%u compatible=%u exportFromImported=%u\n", external_properties.externalMemoryProperties.externalMemoryFeatures, external_properties.externalMemoryProperties.compatibleHandleTypes, external_properties.externalMemoryProperties.exportFromImportedHandleTypes);
    VkExternalMemoryBufferCreateInfo external_buffer = {.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO, .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBufferCreateInfo buffer_info = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .pNext = &external_buffer, .size = 4096, .usage = external_query.usage, .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer = HANDLE(VkBuffer, 5);
    VK_CALL(vkCreateBuffer, device, &buffer_info, NULL, &buffer);
    VkMemoryRequirements requirements = {0};
    VOID_CALL(vkGetBufferMemoryRequirements, device, buffer, &requirements);
    printf("VENUS_MEMORY_REQUIREMENTS size=%llu alignment=%llu bits=%u\n", (unsigned long long)requirements.size, (unsigned long long)requirements.alignment, requirements.memoryTypeBits);
    VkPhysicalDeviceMemoryProperties memory_properties = {0};
    VOID_CALL(vkGetPhysicalDeviceMemoryProperties, physical, &memory_properties);
    VkImportMemoryResourceInfoMESA import_info = {.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_RESOURCE_INFO_MESA, .resourceId = 3};
    uint64_t allocation_size = requirements.size;
    const char *allocator = getenv("VENUS_ALLOCATOR");
    if (allocator && strcmp(allocator, "dmaheap") == 0) {
        stage = "dmaheap-allocation";
        int heap = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
        if (heap < 0) fprintf(stderr, "VENUS_DMAHEAP_OPEN errno=%d\n", errno);
        REQUIRE(heap >= 0);
        allocation_size = (allocation_size + getpagesize() - 1) & ~(uint64_t)(getpagesize() - 1);
        struct dma_heap_allocation_data heap_alloc = {.len = allocation_size, .fd_flags = O_RDWR | O_CLOEXEC};
        int allocated = ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &heap_alloc);
        int saved_errno = errno;
        close(heap);
        if (allocated) fprintf(stderr, "VENUS_DMAHEAP_ALLOC errno=%d\n", saved_errno);
        REQUIRE(allocated == 0);
        struct virgl_renderer_resource_import_blob_args imported = {.res_handle = 3, .blob_mem = VIRGL_RENDERER_BLOB_MEM_HOST3D, .fd_type = 1, .fd = (int)heap_alloc.fd, .size = allocation_size};
        int imported_result = virgl_renderer_resource_import_blob(&imported);
        if (imported_result) close((int)heap_alloc.fd);
        REQUIRE(imported_result == 0);
        imported_created = true;
        virgl_renderer_ctx_attach_resource(CTX, 3);
        VkMemoryResourcePropertiesMESA resource_properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_RESOURCE_PROPERTIES_MESA};
        VK_CALL(vkGetMemoryResourcePropertiesMESA, device, 3, &resource_properties);
        requirements.memoryTypeBits &= resource_properties.memoryTypeBits;
        printf("VENUS_ALLOCATOR=dmaheap importedBits=%u\n", resource_properties.memoryTypeBits);
    }
    uint32_t memory_type = UINT32_MAX;
    const uint32_t coherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | (imported_created ? 0 : VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    for (uint32_t i = 0; i < memory_properties.memoryTypeCount; i++) {
        printf("VENUS_MEMORY_TYPE=%u flags=%u compatible=%u\n", i, memory_properties.memoryTypes[i].propertyFlags, !!(requirements.memoryTypeBits & (1u << i)));
        if (memory_type == UINT32_MAX && (requirements.memoryTypeBits & (1u << i)) && (memory_properties.memoryTypes[i].propertyFlags & coherent) == coherent) memory_type = i;
    }
    REQUIRE(memory_type != UINT32_MAX);
    VkDeviceMemory memory = HANDLE(VkDeviceMemory, 6);
    VkMemoryAllocateInfo allocate = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = imported_created ? &import_info : NULL, .allocationSize = allocation_size, .memoryTypeIndex = memory_type};
    VK_CALL(vkAllocateMemory, device, &allocate, NULL, &memory);
    VK_CALL(vkBindBufferMemory, device, buffer, memory, 0);
    struct virgl_renderer_resource_create_blob_args blob = {
        .res_handle = 2, .ctx_id = CTX, .blob_mem = VIRGL_RENDERER_BLOB_MEM_HOST3D,
        .blob_flags = VIRGL_RENDERER_BLOB_FLAG_USE_MAPPABLE, .blob_id = 6, .size = allocate.allocationSize,
    };
    stage = "memory-export";
    if (imported_created) {
        int imported_fd = -1;
        uint32_t imported_type = 0;
        REQUIRE(virgl_renderer_resource_export_blob(3, &imported_type, &imported_fd) == 0);
        struct virgl_renderer_resource_import_blob_args imported = {.res_handle = 2, .blob_mem = VIRGL_RENDERER_BLOB_MEM_HOST3D, .fd_type = imported_type, .fd = imported_fd, .size = allocate.allocationSize};
        int imported_result = virgl_renderer_resource_import_blob(&imported);
        if (imported_result) close(imported_fd);
        REQUIRE(imported_result == 0);
    } else {
        REQUIRE(virgl_renderer_resource_create_blob(&blob) == 0);
    }
    memory_created = true;
    uint32_t fd_type = 0;
    REQUIRE(virgl_renderer_resource_export_blob(2, &fd_type, &exported_fd) == 0 && exported_fd >= 0);
    struct stat fd_stat;
    REQUIRE(fstat(exported_fd, &fd_stat) == 0);
    printf("VENUS_MEMORY_EXPORT=PASS fdType=%u size=%llu\n", fd_type, (unsigned long long)allocate.allocationSize);
    stage = "memory-map";
    REQUIRE(virgl_renderer_resource_map(2, &data_mapping, &mapped_size) == 0 && mapped_size >= 4096 && data_mapping);
    printf("VENUS_MEMORY_MAP=PASS size=%llu\n", (unsigned long long)mapped_size);
    uint32_t *words = data_mapping;
    uint32_t expected[16];
    REQUIRE(fd_type != 1 || sync_dma_buf(exported_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE) == 0);
    for (uint32_t i = 0; i < 16; i++) {
        words[i] = 0xa5a50000u + i + (ordinal - 1) * 0x01000000u;
        expected[i] = ((words[i] ^ (0x9e3779b9u + i * 0x045d9f3bu)) * 1664525u) + 1013904223u;
    }
    REQUIRE(fd_type != 1 || sync_dma_buf(exported_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE) == 0);
    VkDescriptorSetLayoutBinding binding = {.binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT};
    VkDescriptorSetLayoutCreateInfo set_layout_info = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 1, .pBindings = &binding};
    VkDescriptorSetLayout set_layout = HANDLE(VkDescriptorSetLayout, 7);
    VK_CALL(vkCreateDescriptorSetLayout, device, &set_layout_info, NULL, &set_layout);
    VkPipelineLayoutCreateInfo pipeline_layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1, .pSetLayouts = &set_layout};
    VkPipelineLayout pipeline_layout = HANDLE(VkPipelineLayout, 8);
    VK_CALL(vkCreatePipelineLayout, device, &pipeline_layout_info, NULL, &pipeline_layout);
    VkShaderModuleCreateInfo shader_info = {.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, .codeSize = probe_shader_spv_len, .pCode = (const uint32_t *)probe_shader_spv};
    VkShaderModule shader = HANDLE(VkShaderModule, 9);
    VK_CALL(vkCreateShaderModule, device, &shader_info, NULL, &shader);
    VkComputePipelineCreateInfo compute = {.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_COMPUTE_BIT, .module = shader, .pName = "main"}, .layout = pipeline_layout};
    VkPipeline pipeline = HANDLE(VkPipeline, 10);
    VK_CALL(vkCreateComputePipelines, device, VK_NULL_HANDLE, 1, &compute, NULL, &pipeline);
    VkDescriptorPoolSize pool_size = {.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 1};
    VkDescriptorPoolCreateInfo pool_info = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = 1, .poolSizeCount = 1, .pPoolSizes = &pool_size};
    VkDescriptorPool pool = HANDLE(VkDescriptorPool, 11);
    VK_CALL(vkCreateDescriptorPool, device, &pool_info, NULL, &pool);
    VkDescriptorSetAllocateInfo set_allocate = {.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, .descriptorPool = pool, .descriptorSetCount = 1, .pSetLayouts = &set_layout};
    VkDescriptorSet set = HANDLE(VkDescriptorSet, 12);
    VK_CALL(vkAllocateDescriptorSets, device, &set_allocate, &set);
    VkDescriptorBufferInfo buffer_descriptor = {.buffer = buffer, .offset = 0, .range = 64};
    VkWriteDescriptorSet write = {.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set, .dstBinding = 0, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &buffer_descriptor};
    VOID_CALL(vkUpdateDescriptorSets, device, 1, &write, 0, NULL);
    VkCommandPoolCreateInfo command_pool_info = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = family};
    VkCommandPool command_pool = HANDLE(VkCommandPool, 13);
    VK_CALL(vkCreateCommandPool, device, &command_pool_info, NULL, &command_pool);
    VkCommandBufferAllocateInfo command_allocate = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = command_pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1};
    VkCommandBuffer command_buffer = HANDLE(VkCommandBuffer, 14);
    VK_CALL(vkAllocateCommandBuffers, device, &command_allocate, &command_buffer);
    VkCommandBufferBeginInfo command_begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    VK_CALL(vkBeginCommandBuffer, command_buffer, &command_begin);
    VOID_CALL(vkCmdBindPipeline, command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    VOID_CALL(vkCmdBindDescriptorSets, command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &set, 0, NULL);
    VOID_CALL(vkCmdDispatch, command_buffer, 1, 1, 1);
    VkMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    VOID_CALL(vkCmdPipelineBarrier, command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, NULL, 0, NULL);
    VK_CALL(vkEndCommandBuffer, command_buffer);
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence gpu_fence = HANDLE(VkFence, 15);
    VK_CALL(vkCreateFence, device, &fence_info, NULL, &gpu_fence);
    VkSubmitInfo submit_info = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &command_buffer};
    VK_CALL(vkQueueSubmit, queue, 1, &submit_info, gpu_fence);
    VK_CALL(vkWaitForFences, device, 1, &gpu_fence, VK_TRUE, 3000000000ULL);
    stage = "compute-readback";
    REQUIRE(fd_type != 1 || sync_dma_buf(exported_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ) == 0);
    for (uint32_t i = 0; i < 16; i++) {
        printf("VENUS_WORD=%u actual=%u expected=%u\n", i, words[i], expected[i]);
        REQUIRE(words[i] == expected[i]);
    }
    REQUIRE(fd_type != 1 || sync_dma_buf(exported_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ) == 0);
    printf("VENUS_COMPUTE_READBACK=PASS words=16\n");
    result = 0;
cleanup:;
#ifdef VENUS_TLS_TRACE
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[1024];
        while (fgets(line, sizeof(line), maps)) if (strstr(line, ".so")) fprintf(stderr, "VENUS_MAP %s", line);
        fclose(maps);
    }
#endif
    if (exported_fd >= 0) close(exported_fd);
    if (data_mapping) virgl_renderer_resource_unmap(2);
    if (memory_created) virgl_renderer_resource_unref(2);
    if (context) virgl_renderer_context_destroy(CTX);
    if (imported_created) virgl_renderer_resource_unref(3);
    if (reply_mapping) virgl_renderer_resource_unmap(1);
    if (reply_created) virgl_renderer_resource_unref(1);
    return result;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    bool repeat = argc == 2 && !strcmp(argv[1], "--repeat-contexts");
    if (argc != 1 && !repeat) return 64;
    const uint32_t ids[] = {1, 2, 1};
    if (repeat) printf("VENUS_REPEAT_BEGIN count=3\n");
    int result = 1;
    for (uint32_t i = 0; i < (repeat ? 3u : 1u); i++) {
        context_id = ids[i];
        reply_mapping = NULL;
        pthread_mutex_lock(&fence_mutex);
        completed_fence = next_fence = 0;
        pthread_mutex_unlock(&fence_mutex);
        if (repeat) printf("VENUS_CONTEXT_BEGIN index=%u id=%u\n", i + 1, ids[i]);
        result = probe_context(i + 1);
        if (repeat) printf("VENUS_CONTEXT_END index=%u id=%u exit=%d\n", i + 1, ids[i], result);
        if (result) break;
    }
    if (renderer_initialized) virgl_renderer_cleanup(NULL);
    printf("VENUS_HOST_END exit=%d\n", result);
    if (repeat) printf("VENUS_REPEAT_END exit=%d\n", result);
    return result;
}

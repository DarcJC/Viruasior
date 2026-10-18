/* Run inside the guest with the paired Venus ICD. The delayed GPU write must
 * survive semaphore -> DMA-BUF reservation -> semaphore handoff to a separate
 * Venus instance. Modes: baseline, async, foreign (wrong PID), nonzink.
 * All modes validate data and ordering; only async may return a pending fd.
 */
#define _POSIX_C_SOURCE 200809L
#include <linux/dma-buf.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#define CHECK(call)                                                                                \
    do {                                                                                           \
        VkResult r = (call);                                                                       \
        if (r != VK_SUCCESS) {                                                                     \
            fprintf(stderr, "FAIL %s = %d\n", #call, r);                                           \
            exit(2);                                                                               \
        }                                                                                          \
    } while (0)
static uint64_t clock_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec;
}
struct signal_args {
    VkDevice device;
    VkSemaphore gate;
};
static void *signal_later(void *opaque) {
    struct signal_args *arg = opaque;
    struct timespec wait = {.tv_nsec = 200000000};
    nanosleep(&wait, NULL);
    VkSemaphoreSignalInfo info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO, .semaphore = arg->gate, .value = 1};
    CHECK(vkSignalSemaphore(arg->device, &info));
    return NULL;
}
int main(int argc, char **argv) {
    int asynchronous = argc == 2 && !strcmp(argv[1], "async");
    if (argc != 2 || (strcmp(argv[1], "baseline") && strcmp(argv[1], "async") &&
                      strcmp(argv[1], "foreign") && strcmp(argv[1], "nonzink")))
        return 2;
    unsetenv("VIRUAISOR_ASYNC_SYNC_FD");
    unsetenv("VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID");
    if (strcmp(argv[1], "baseline")) {
        char owner[32];
        snprintf(owner, sizeof(owner), "%ld",
                 (long)getpid() + (!strcmp(argv[1], "foreign") ? 1 : 0));
        if (setenv("VIRUAISOR_DESKTOP_ASYNC_SYNC_FD_PID", owner, 1))
            return 2;
    }
    VkInstance instance;
    VkDevice device;
    VkPhysicalDevice physical;
    VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                             .pApplicationName = "viruaisor-syncfd-order",
                             .pEngineName =
                                 strcmp(argv[1], "nonzink") ? "mesa zink" : "viruaisor test",
                             .apiVersion = VK_API_VERSION_1_2};
    VkInstanceCreateInfo ic = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                               .pApplicationInfo = &app};
    CHECK(vkCreateInstance(&ic, NULL, &instance));
    uint32_t count = 1;
    CHECK(vkEnumeratePhysicalDevices(instance, &count, &physical));
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(physical, &properties);
    printf("GPU=%s\n", properties.deviceName);
    uint32_t families = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &families, NULL);
    VkQueueFamilyProperties *qp = calloc(families, sizeof(*qp));
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &families, qp);
    uint32_t family = 0;
    while (family < families && !(qp[family].queueFlags & VK_QUEUE_GRAPHICS_BIT))
        family++;
    if (family == families)
        return 2;
    free(qp);
    VkPhysicalDeviceTimelineSemaphoreFeatures timeline = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES};
    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                          .pNext = &timeline};
    vkGetPhysicalDeviceFeatures2(physical, &features);
    if (!timeline.timelineSemaphore)
        return 2;
    float priority = 1;
    VkDeviceQueueCreateInfo qc = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                  .queueFamilyIndex = family,
                                  .queueCount = 1,
                                  .pQueuePriorities = &priority};
    const char *extensions[] = {VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
                                VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
                                VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME};
    VkDeviceCreateInfo dc = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                             .pNext = &timeline,
                             .queueCreateInfoCount = 1,
                             .pQueueCreateInfos = &qc,
                             .enabledExtensionCount = 3,
                             .ppEnabledExtensionNames = extensions};
    CHECK(vkCreateDevice(physical, &dc, NULL, &device));
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkExternalMemoryBufferCreateInfo external_buffer = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkBuffer buffer;
    VkBufferCreateInfo bc = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                             .pNext = &external_buffer,
                             .size = 4096,
                             .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    CHECK(vkCreateBuffer(device, &bc, NULL, &buffer));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device, buffer, &req);
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(physical, &mp);
    uint32_t memory_type = 0;
    while (memory_type < mp.memoryTypeCount &&
           (!(req.memoryTypeBits & (1u << memory_type)) ||
            !(mp.memoryTypes[memory_type].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)))
        memory_type++;
    if (memory_type == mp.memoryTypeCount)
        return 2;
    VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .buffer = buffer};
    VkExportMemoryAllocateInfo export_memory = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicated,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkMemoryAllocateInfo allocation = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                       .pNext = &export_memory,
                                       .allocationSize = req.size,
                                       .memoryTypeIndex = memory_type};
    VkDeviceMemory memory;
    CHECK(vkAllocateMemory(device, &allocation, NULL, &memory));
    CHECK(vkBindBufferMemory(device, buffer, memory, 0));
    PFN_vkGetMemoryFdKHR get_memory_fd =
        (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device, "vkGetMemoryFdKHR");
    if (!get_memory_fd)
        return 2;
    VkMemoryGetFdInfoKHR memory_fd_info = {.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR,
                                           .memory = memory,
                                           .handleType =
                                               VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    int dmabuf = -1;
    CHECK(get_memory_fd(device, &memory_fd_info, &dmabuf));
    if (dmabuf < 0)
        return 2;
    void *mapped;
    CHECK(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, &mapped));
    memset(mapped, 0, 4096);
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, .memory = memory, .size = VK_WHOLE_SIZE};
    CHECK(vkFlushMappedMemoryRanges(device, 1, &range));
    VkCommandPool pool;
    VkCommandPoolCreateInfo pc = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                  .queueFamilyIndex = family};
    CHECK(vkCreateCommandPool(device, &pc, NULL, &pool));
    VkCommandBuffer command;
    VkCommandBufferAllocateInfo ca = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                      .commandPool = pool,
                                      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                      .commandBufferCount = 1};
    CHECK(vkAllocateCommandBuffers(device, &ca, &command));
    VkCommandBufferBeginInfo begin = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    CHECK(vkBeginCommandBuffer(command, &begin));
    vkCmdFillBuffer(command, buffer, 0, 4096, 0x13579bdf);
    VkMemoryBarrier barrier = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                               .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                               .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1,
                         &barrier, 0, NULL, 0, NULL);
    CHECK(vkEndCommandBuffer(command));
    VkSemaphoreTypeCreateInfo type = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO,
                                      .semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE,
                                      .initialValue = 0};
    VkSemaphoreCreateInfo sc = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, .pNext = &type};
    VkSemaphore gate, done;
    CHECK(vkCreateSemaphore(device, &sc, NULL, &gate));
    VkExportSemaphoreCreateInfo export = {.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
                                          .handleTypes =
                                              VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    sc.pNext = &export;
    CHECK(vkCreateSemaphore(device, &sc, NULL, &done));
    VkInstance consumer_instance;
    CHECK(vkCreateInstance(&ic, NULL, &consumer_instance));
    VkPhysicalDevice consumer_physical;
    count = 1;
    CHECK(vkEnumeratePhysicalDevices(consumer_instance, &count, &consumer_physical));
    if (count != 1)
        return 2;
    VkDevice consumer;
    CHECK(vkCreateDevice(consumer_physical, &dc, NULL, &consumer));
    VkQueue consumer_queue;
    vkGetDeviceQueue(consumer, family, 0, &consumer_queue);
    VkSemaphore imported;
    sc.pNext = NULL;
    CHECK(vkCreateSemaphore(consumer, &sc, NULL, &imported));
    VkFence consumer_fence;
    VkFenceCreateInfo fc = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    CHECK(vkCreateFence(consumer, &fc, NULL, &consumer_fence));
    uint64_t one = 1, zero = 0;
    VkTimelineSemaphoreSubmitInfo values = {.sType =
                                                VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
                                            .waitSemaphoreValueCount = 1,
                                            .pWaitSemaphoreValues = &one,
                                            .signalSemaphoreValueCount = 1,
                                            .pSignalSemaphoreValues = &zero};
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkSubmitInfo submit = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                           .pNext = &values,
                           .waitSemaphoreCount = 1,
                           .pWaitSemaphores = &gate,
                           .pWaitDstStageMask = &stage,
                           .commandBufferCount = 1,
                           .pCommandBuffers = &command,
                           .signalSemaphoreCount = 1,
                           .pSignalSemaphores = &done};
    CHECK(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE));
    struct signal_args args = {device, gate};
    pthread_t thread;
    if (pthread_create(&thread, NULL, signal_later, &args))
        return 2;
    PFN_vkGetSemaphoreFdKHR get_fd =
        (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(device, "vkGetSemaphoreFdKHR");
    if (!get_fd)
        return 2;
    VkSemaphoreGetFdInfoKHR fi = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
                                  .semaphore = done,
                                  .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT};
    int fd = -1;
    uint64_t start = clock_ns();
    VkResult exported = get_fd(device, &fi, &fd);
    uint64_t elapsed = clock_ns() - start;
    if (exported != VK_SUCCESS || fd < 0) {
        pthread_join(thread, NULL);
        fprintf(stderr, "Expected a real sync fd: result=%d fd=%d\n", exported, fd);
        return 2;
    }
    struct pollfd p = {fd, POLLIN, 0};
    int initial = poll(&p, 1, 0);
    printf("EXPORT async=%d elapsed_us=%llu initially_ready=%d\n", asynchronous,
           (unsigned long long)(elapsed / 1000), initial);
    if (asynchronous && (elapsed > 100000000 || initial != 0)) {
        pthread_join(thread, NULL);
        fprintf(stderr, "Async export did not precede signal\n");
        return 3;
    }
    if (!asynchronous && (elapsed < 100000000 || initial != 1)) {
        pthread_join(thread, NULL);
        fprintf(stderr, "Baseline did not wait\n");
        return 3;
    }
    PFN_vkImportSemaphoreFdKHR import_fd =
        (PFN_vkImportSemaphoreFdKHR)vkGetDeviceProcAddr(consumer, "vkImportSemaphoreFdKHR");
    if (!import_fd) {
        pthread_join(thread, NULL);
        return 2;
    }
    struct dma_buf_import_sync_file dma_import = {.flags = DMA_BUF_SYNC_RW, .fd = fd};
    if (ioctl(dmabuf, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, &dma_import)) {
        pthread_join(thread, NULL);
        perror("import DMA-BUF dependency");
        return 6;
    }
    struct dma_buf_export_sync_file dma_export = {.flags = DMA_BUF_SYNC_RW, .fd = -1};
    if (ioctl(dmabuf, DMA_BUF_IOCTL_EXPORT_SYNC_FILE, &dma_export)) {
        pthread_join(thread, NULL);
        perror("export DMA-BUF dependency");
        return 6;
    }
    struct pollfd dma_poll = {dma_export.fd, POLLIN, 0};
    int dma_initial = poll(&dma_poll, 1, 0);
    printf("DMA_BUF initially_ready=%d exported_fd=%d\n", dma_initial, dma_export.fd);
    if (asynchronous && dma_initial != 0) {
        pthread_join(thread, NULL);
        fprintf(stderr, "DMA-BUF lost pending dependency\n");
        return 6;
    }
    VkImportSemaphoreFdInfoKHR imported_info = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
        .semaphore = imported,
        .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
        .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        .fd = dma_export.fd};
    VkResult imported_result = import_fd(consumer, &imported_info);
    if (imported_result != VK_SUCCESS) {
        pthread_join(thread, NULL);
        CHECK(imported_result);
    }
    VkPipelineStageFlags consumer_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo consume = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                            .waitSemaphoreCount = 1,
                            .pWaitSemaphores = &imported,
                            .pWaitDstStageMask = &consumer_stage};
    uint64_t consume_start = clock_ns();
    VkResult submitted = vkQueueSubmit(consumer_queue, 1, &consume, consumer_fence);
    uint64_t consume_submit = clock_ns();
    if (submitted != VK_SUCCESS) {
        pthread_join(thread, NULL);
        CHECK(submitted);
    }
    VkResult consumed = vkWaitForFences(consumer, 1, &consumer_fence, VK_TRUE, 2000000000ull);
    uint64_t consume_done = clock_ns();
    if (consumed != VK_SUCCESS) {
        pthread_join(thread, NULL);
        CHECK(consumed);
    }
    printf("CONSUMER separate_instance=1 submit_us=%llu fence_wait_us=%llu total_us=%llu\n",
           (unsigned long long)((consume_submit - consume_start) / 1000),
           (unsigned long long)((consume_done - consume_submit) / 1000),
           (unsigned long long)((consume_done - start) / 1000));
    if (consume_done - start < 100000000ull) {
        pthread_join(thread, NULL);
        fprintf(stderr, "Consumer finished before delayed producer\n");
        return 4;
    }
    if (poll(&p, 1, 2000) != 1 || !(p.revents & POLLIN)) {
        pthread_join(thread, NULL);
        fprintf(stderr, "Completion fence did not signal\n");
        return 4;
    }
    pthread_join(thread, NULL);
    CHECK(vkInvalidateMappedMemoryRanges(device, 1, &range));
    for (unsigned i = 0; i < 1024; i++)
        if (((uint32_t *)mapped)[i] != 0x13579bdf) {
            fprintf(stderr, "STALE word=%u value=%x\n", i, ((uint32_t *)mapped)[i]);
            return 5;
        }
    CHECK(vkDeviceWaitIdle(consumer));
    vkDestroyFence(consumer, consumer_fence, NULL);
    vkDestroySemaphore(consumer, imported, NULL);
    vkDestroyDevice(consumer, NULL);
    vkDestroyInstance(consumer_instance, NULL);
    CHECK(vkDeviceWaitIdle(device));
    close(dmabuf);
    close(fd);
    vkUnmapMemory(device, memory);
    vkDestroySemaphore(device, done, NULL);
    vkDestroySemaphore(device, gate, NULL);
    vkDestroyCommandPool(device, pool, NULL);
    vkDestroyBuffer(device, buffer, NULL);
    vkFreeMemory(device, memory, NULL);
    vkDestroyDevice(device, NULL);
    vkDestroyInstance(instance, NULL);
    puts("PASS DMA-BUF dependency and separate device order all 1024 GPU-written words");
    return 0;
}

#pragma once
#include "venus_gralloc.h"
#include "venus_import_memory_policy.h"

/* Called with native memory indices, before upstream's optional index spoof.
 * A short-lived ordinary-UID Vulkan device queries the real AHB FD compatibility.
 * No command submission, system changes, index remapping or capability inflation. */
static void vkr_android_limit_memory_properties(struct vkr_physical_device *physical) {
    if (physical->properties.vendorID != 0x13b5) return;
    uint32_t masks[2] = {0, 0};
    struct vn_physical_device_proc_table *vk = &physical->proc_table;
    VkPhysicalDevice handle = physical->base.handle.physical_device;
    VkQueueFamilyProperties families[64];
    uint32_t count = 0;
    VkDevice device = VK_NULL_HANDLE;
    PFN_vkDestroyDevice destroy = NULL;
    if (!physical->KHR_external_memory_fd || !physical->EXT_external_memory_dma_buf)
        goto filter;
    vk->GetPhysicalDeviceQueueFamilyProperties(handle, &count, NULL);
    if (!count || count > 64) goto filter;
    vk->GetPhysicalDeviceQueueFamilyProperties(handle, &count, families);
    uint32_t queue = 0;
    while (queue < count && !families[queue].queueCount) ++queue;
    if (queue == count) goto filter;
    float priority = 0;
    VkDeviceQueueCreateInfo queue_info = {
        .sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex=queue, .queueCount=1, .pQueuePriorities=&priority,
    };
    const char *extensions[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
    };
    VkDeviceCreateInfo info = {
        .sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount=1, .pQueueCreateInfos=&queue_info,
        .enabledExtensionCount=2, .ppEnabledExtensionNames=extensions,
    };
    VkResult result = vk->CreateDevice(handle, &info, NULL, &device);
    if (result != VK_SUCCESS) {
        vkr_log("VIRUAISOR_MEMORY_IMPORT_QUERY_DEVICE_FAILED vk=%d", result);
        goto filter;
    }
    destroy = (PFN_vkDestroyDevice)vk->GetDeviceProcAddr(device, "vkDestroyDevice");
    PFN_vkGetMemoryFdPropertiesKHR get_properties =
        (PFN_vkGetMemoryFdPropertiesKHR)vk->GetDeviceProcAddr(device, "vkGetMemoryFdPropertiesKHR");
    /* Both are required by the successfully enabled Vulkan device/extension. */
    if (!destroy) abort(); /* broken Vulkan loader: let process exit reclaim the device */
    if (get_properties) {
        for (unsigned cached = 0; cached < 2; ++cached) {
            AHardwareBuffer *buffer = NULL;
            int fd = venus_gralloc_allocate(65536, cached != 0, &buffer);
            if (fd < 0) continue;
            VkMemoryFdPropertiesKHR properties = {
                .sType=VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR,
            };
            result = get_properties(device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, fd, &properties);
            if (result == VK_SUCCESS) masks[cached] = properties.memoryTypeBits;
            close(fd);
            venus_ahb_release(buffer);
        }
    }
    destroy(device, NULL);
filter:
    physical->android_import_type_bits = masks[0] | masks[1];
    vkr_log("VIRUAISOR_MEMORY_IMPORT_MASK uncached=%x cached=%x", masks[0], masks[1]);
    for (uint32_t i=0; i<physical->memory_properties.memoryTypeCount; ++i) {
        VkMemoryType *type = &physical->memory_properties.memoryTypes[i];
        uint32_t original = type->propertyFlags;
        type->propertyFlags = venus_import_memory_properties(i, original, masks[0], masks[1]);
        if (type->propertyFlags != original)
            vkr_log("VIRUAISOR_MEMORY_IMPORT_FILTER type=%u before=%x after=%x", i, original, type->propertyFlags);
    }
}

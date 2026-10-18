/* Guest integration regression for the exported image bounds used by Wayland. */
#include <inttypes.h>
#include <stdio.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#define CHECK(call) do { VkResult r = (call); if (r != VK_SUCCESS) { \
    fprintf(stderr, "%s failed: %d\n", #call, r); return 1; } } while (0)

static unsigned dimension(const char *text)
{
    unsigned value = 0;
    for (; *text; text++) {
        if (*text < '0' || *text > '9') return 0;
        value = value * 10 + (unsigned)(*text - '0');
        if (value > 4096) return 0;
    }
    return value;
}

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    const unsigned width = dimension(argv[1]);
    const unsigned height = dimension(argv[2]);
    if (!width || !height || width > 4096 || height > 4096) return 2;
    const VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Viruaisor AHB layout regression",
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instance_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &application,
    };
    VkInstance instance;
    CHECK(vkCreateInstance(&instance_info, NULL, &instance));
    uint32_t count = 1;
    VkPhysicalDevice physical;
    CHECK(vkEnumeratePhysicalDevices(instance, &count, &physical));
    if (!count) return 3;
    const float priority = 1;
    const VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = 0, .queueCount = 1, .pQueuePriorities = &priority,
    };
    const char *extensions[] = {
        VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
    };
    const VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = 2, .ppEnabledExtensionNames = extensions,
    };
    VkDevice device;
    CHECK(vkCreateDevice(physical, &device_info, NULL, &device));
    const VkExternalMemoryImageCreateInfo external = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    const VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &external,
        .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_B8G8R8A8_UNORM,
        .extent = {width, height, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    VkImage image;
    CHECK(vkCreateImage(device, &image_info, NULL, &image));
    VkMemoryRequirements requirements;
    vkGetImageMemoryRequirements(device, image, &requirements);
    const VkImageMemoryRequirementsInfo2 query = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, .image = image,
    };
    VkMemoryRequirements2 requirements2 = {.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
    vkGetImageMemoryRequirements2(device, &query, &requirements2);
    const VkImageSubresource subresource = {.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT};
    VkSubresourceLayout layout;
    vkGetImageSubresourceLayout(device, image, &subresource, &layout);
    uint32_t memory_type = 0;
    while (memory_type < 32 && !(requirements.memoryTypeBits & (1u << memory_type))) memory_type++;
    if (memory_type == 32) return 4;
    const VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = image,
    };
    const VkExportMemoryAllocateInfo export = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, .pNext = &dedicated,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    const VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .pNext = &export,
        .allocationSize = requirements.size, .memoryTypeIndex = memory_type,
    };
    VkDeviceMemory memory;
    CHECK(vkAllocateMemory(device, &allocation, NULL, &memory));
    CHECK(vkBindImageMemory(device, image, memory, 0));
    CHECK(vkDeviceWaitIdle(device));
    const VkMemoryGetFdInfoKHR fd_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, .memory = memory,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    PFN_vkGetMemoryFdKHR get_fd = (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(device, "vkGetMemoryFdKHR");
    if (!get_fd) return 5;
    int fd;
    CHECK(get_fd(device, &fd_info, &fd));
    const off_t size = lseek(fd, 0, SEEK_END);
    const uint64_t minimum = layout.offset + layout.rowPitch * height;
    const int passed = layout.rowPitch >= width * 4u && size > 0 &&
        requirements.size >= minimum && requirements2.memoryRequirements.size >= minimum &&
        (uint64_t)size >= minimum;
    printf("AHB_LAYOUT width=%u height=%u row=%" PRIu64 " offset=%" PRIu64
           " minimum=%" PRIu64 " requirements=%" PRIu64 " requirements2=%" PRIu64
           " fd_size=%" PRId64 " passed=%d\n",
           width, height, layout.rowPitch, layout.offset, minimum,
           requirements.size, requirements2.memoryRequirements.size, (int64_t)size, passed);
    close(fd);
    vkDestroyImage(device, image, NULL);
    vkFreeMemory(device, memory, NULL);
    vkDestroyDevice(device, NULL);
    vkDestroyInstance(instance, NULL);
    return passed ? 0 : 1;
}

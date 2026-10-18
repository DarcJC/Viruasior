/* A DMA-BUF must remain importable after its exporting Vulkan context exits. */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#define CHECK(call) do { VkResult r = (call); if (r != VK_SUCCESS) { \
    fprintf(stderr, "%s failed: %d\n", #call, r); return 1; } } while (0)

struct context {
    VkInstance instance;
    VkDevice device;
    VkImage image;
    VkDeviceMemory memory;
    VkMemoryRequirements requirements;
};

static int create_context(struct context *ctx)
{
    const VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Viruaisor AHB lifetime regression",
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instance_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &application,
    };
    CHECK(vkCreateInstance(&instance_info, NULL, &ctx->instance));
    uint32_t count = 1;
    VkPhysicalDevice physical;
    CHECK(vkEnumeratePhysicalDevices(ctx->instance, &count, &physical));
    if (!count) return 1;
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
    CHECK(vkCreateDevice(physical, &device_info, NULL, &ctx->device));
    const VkExternalMemoryImageCreateInfo external = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    const VkImageCreateInfo image_info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .pNext = &external,
        .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_B8G8R8A8_UNORM,
        .extent = {1, 1, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    CHECK(vkCreateImage(ctx->device, &image_info, NULL, &ctx->image));
    vkGetImageMemoryRequirements(ctx->device, ctx->image, &ctx->requirements);
    return 0;
}

static void destroy_context(struct context *ctx)
{
    vkDestroyImage(ctx->device, ctx->image, NULL);
    vkFreeMemory(ctx->device, ctx->memory, NULL);
    vkDestroyDevice(ctx->device, NULL);
    vkDestroyInstance(ctx->instance, NULL);
    memset(ctx, 0, sizeof(*ctx));
}

static int allocate(struct context *ctx, int import_fd)
{
    uint32_t types = ctx->requirements.memoryTypeBits;
    if (import_fd >= 0) {
        PFN_vkGetMemoryFdPropertiesKHR get_properties =
            (PFN_vkGetMemoryFdPropertiesKHR)vkGetDeviceProcAddr(ctx->device, "vkGetMemoryFdPropertiesKHR");
        if (!get_properties) return 1;
        VkMemoryFdPropertiesKHR properties = {.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR};
        CHECK(get_properties(ctx->device, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                             import_fd, &properties));
        types &= properties.memoryTypeBits;
    }
    uint32_t memory_type = 0;
    while (memory_type < 32 && !(types & (1u << memory_type))) memory_type++;
    if (memory_type == 32) return 1;
    const VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = ctx->image,
    };
    const VkExportMemoryAllocateInfo export = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, .pNext = &dedicated,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    const VkImportMemoryFdInfoKHR import = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, .pNext = &dedicated,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT, .fd = import_fd,
    };
    const VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = import_fd >= 0 ? (const void *)&import : (const void *)&export,
        .allocationSize = ctx->requirements.size, .memoryTypeIndex = memory_type,
    };
    CHECK(vkAllocateMemory(ctx->device, &allocation, NULL, &ctx->memory));
    CHECK(vkBindImageMemory(ctx->device, ctx->image, ctx->memory, 0));
    CHECK(vkDeviceWaitIdle(ctx->device));
    return 0;
}

int main(int argc, char **argv)
{
    if (argc != 2 || (strcmp(argv[1], "retain") && strcmp(argv[1], "release"))) return 2;
    const int release_exporter = !strcmp(argv[1], "release");
    struct context producer = {0}, consumer = {0};
    if (create_context(&producer) || allocate(&producer, -1)) return 1;
    PFN_vkGetMemoryFdKHR get_fd =
        (PFN_vkGetMemoryFdKHR)vkGetDeviceProcAddr(producer.device, "vkGetMemoryFdKHR");
    if (!get_fd) return 1;
    const VkMemoryGetFdInfoKHR fd_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, .memory = producer.memory,
        .handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
    };
    int fd;
    CHECK(get_fd(producer.device, &fd_info, &fd));
    if (release_exporter) {
        destroy_context(&producer);
        usleep(100000); /* Let the asynchronous renderer context teardown finish. */
    }
    printf("AHB_LIFETIME importing exporter_released=%d fd_size=%lld\n",
           release_exporter, (long long)lseek(fd, 0, SEEK_END));
    fflush(stdout);
    if (create_context(&consumer) || allocate(&consumer, fd)) return 1;
    destroy_context(&consumer); /* Successful Vulkan import consumes the fd. */
    if (!release_exporter) destroy_context(&producer);
    printf("AHB_LIFETIME exporter_released=%d passed=1\n", release_exporter);
    return 0;
}

/* Guest-only integration probe. Run the ordinary-memory rejection case only
 * against the repaired renderer: the preceding Mali path crashed crosvm. */
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define CHECK(call) do { VkResult r = (call); if (r != VK_SUCCESS) { \
    fprintf(stderr, "%s failed: %d\n", #call, r); return 1; } } while (0)

int main(int argc, char **argv)
{
    if (argc != 2 || (strcmp(argv[1], "dedicated") &&
                      strcmp(argv[1], "reject-ordinary"))) return 2;
    setbuf(stdout, NULL);
    const VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Viruaisor AHB binding regression",
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instance_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application,
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
        .extent = {64, 64, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
    };
    VkImage image;
    CHECK(vkCreateImage(device, &image_info, NULL, &image));
    const VkImageMemoryRequirementsInfo2 query = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, .image = image,
    };
    VkMemoryDedicatedRequirements dedicated_requirements = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_REQUIREMENTS,
    };
    VkMemoryRequirements2 requirements = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2,
        .pNext = &dedicated_requirements,
    };
    vkGetImageMemoryRequirements2(device, &query, &requirements);
    printf("AHB_REQUIRES_DEDICATED=%u\n", dedicated_requirements.requiresDedicatedAllocation);
    if (!dedicated_requirements.requiresDedicatedAllocation) return 4;
    uint32_t memory_type = 0;
    while (memory_type < 32 && !(requirements.memoryRequirements.memoryTypeBits &
                                (1u << memory_type))) memory_type++;
    if (memory_type == 32) return 5;
    const VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, .image = image,
    };
    const VkMemoryAllocateInfo allocation = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        /* Deliberately omit export flags: the translated image still needs AHB. */
        .pNext = !strcmp(argv[1], "dedicated") ? &dedicated : NULL,
        .allocationSize = requirements.memoryRequirements.size,
        .memoryTypeIndex = memory_type,
    };
    VkDeviceMemory memory;
    CHECK(vkAllocateMemory(device, &allocation, NULL, &memory));
    const VkBindImageMemoryInfo binding = {
        .sType = VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO,
        .image = image, .memory = memory,
    };
    printf("AHB_BIND_CASE=%s\n", argv[1]);
    CHECK(vkBindImageMemory2(device, 1, &binding));
    /* Round trip after Venus' asynchronous bind. Rejected contexts cannot pass. */
    CHECK(vkDeviceWaitIdle(device));
    if (strcmp(argv[1], "dedicated")) return 6;
    vkDestroyImage(device, image, NULL);
    vkFreeMemory(device, memory, NULL);
    vkDestroyDevice(device, NULL);
    vkDestroyInstance(instance, NULL);
    puts("AHB_DEDICATED_BIND_PASS");
    return 0;
}

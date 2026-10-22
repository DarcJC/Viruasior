#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include "readback.h"
#include "cache_policy.h"
#include "shader.h"

/* Default: disposable initramfs PID 1. Explicit modes also run in a desktop
 * guest and return an exit status, using the same shader and readback checks. */
static const char *stage = "boot";
#define VK_CHECK(call) do { stage = #call; printf("VENUS_GUEST_STAGE=%s\n", stage); VkResult result = (call); if (result != VK_SUCCESS) { printf("VENUS_GUEST_VK_ERROR=%d\n", result); goto fail; } } while (0)
#define REQUIRE(value) do { if (!(value)) { printf("VENUS_GUEST_CHECK_FAILED=%s errno=%d\n", #value, errno); goto fail; } } while (0)

int main(int argc, char **argv) {
    const int desktop = argc == 2;
    const int coherent = desktop && !strcmp(argv[1], "--coherent");
    if (argc > 2 || (desktop && !coherent && strcmp(argv[1], "--cached") &&
                    strcmp(argv[1], "--cached-no-sync"))) {
        fprintf(stderr, "Usage: %s [--coherent|--cached|--cached-no-sync]\n", argv[0]);
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IOLBF, 0);
    int skip_sync = coherent || (desktop && !strcmp(argv[1], "--cached-no-sync"));
    if (!desktop) {
        mkdir("/dev", 0755); mkdir("/proc", 0755); mkdir("/sys", 0755); mkdir("/tmp", 0700);
        REQUIRE(!mount("devtmpfs", "/dev", "devtmpfs", 0, NULL));
        REQUIRE(!mount("proc", "/proc", "proc", 0, NULL));
        REQUIRE(!mount("sysfs", "/sys", "sysfs", 0, NULL));
        setenv("VK_DRIVER_FILES", "/etc/venus_icd.json", 1);
        REQUIRE(!setenv("VN_PERF", venus_guest_perf_options(), 1));
        printf("VENUS_GUEST_PERF=%s\n", venus_guest_perf_options());
        setenv("XDG_RUNTIME_DIR", "/tmp", 1);
        char command_line[4096] = {0};
        FILE *command_file = fopen("/proc/cmdline", "re");
        REQUIRE(command_file != NULL);
        REQUIRE(fgets(command_line, sizeof(command_line), command_file) != NULL);
        fclose(command_file);
        skip_sync = strstr(command_line, "venus_probe.skip_cache_sync=1") != NULL;
    }
    printf("VENUS_GUEST_CACHE_POLICY=%s\n", coherent ? "coherent-no-cache-operations" : "explicit-noncoherent");
    if (skip_sync) puts("VENUS_GUEST_SYNC_SKIPPED=true");
    printf("VENUS_GUEST_BEGIN pid=%d mode=%s\n", getpid(), desktop ? "desktop" : "initramfs");
    VkApplicationInfo app = {.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="Viruaisor guest compute",.apiVersion=VK_API_VERSION_1_2};
    VkInstanceCreateInfo ic = {.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkInstance instance;
    VK_CHECK(vkCreateInstance(&ic, NULL, &instance));
    uint32_t count = 0;
    VK_CHECK(vkEnumeratePhysicalDevices(instance, &count, NULL));
    REQUIRE(count > 0 && count <= 4);
    VkPhysicalDevice physical[4];
    VK_CHECK(vkEnumeratePhysicalDevices(instance, &count, physical));
    VkPhysicalDeviceDriverProperties driver = {.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
    VkPhysicalDeviceProperties2 properties = {.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,.pNext=&driver};
    vkGetPhysicalDeviceProperties2(physical[0], &properties);
    printf("VENUS_GUEST_DEVICE name=%s vendor=%x type=%d driver=%d driverName=%s\n", properties.properties.deviceName, properties.properties.vendorID, properties.properties.deviceType, driver.driverID, driver.driverName);
    REQUIRE(properties.properties.vendorID == 0x13b5 && properties.properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU && driver.driverID == VK_DRIVER_ID_MESA_VENUS);
    uint32_t queue_count = 0, family = UINT32_MAX;
    vkGetPhysicalDeviceQueueFamilyProperties(physical[0], &queue_count, NULL);
    REQUIRE(queue_count > 0 && queue_count <= 32);
    VkQueueFamilyProperties queues[32];
    vkGetPhysicalDeviceQueueFamilyProperties(physical[0], &queue_count, queues);
    for (uint32_t i = 0; i < queue_count; ++i) if (queues[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { family = i; break; }
    REQUIRE(family != UINT32_MAX);
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qc = {.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dc = {.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qc};
    VkDevice device;
    VK_CHECK(vkCreateDevice(physical[0], &dc, NULL, &device));
    VkQueue queue; vkGetDeviceQueue(device, family, 0, &queue);
    VkBufferCreateInfo bc = {.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=256*sizeof(uint32_t),.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer;
    VK_CHECK(vkCreateBuffer(device, &bc, NULL, &buffer));
    VkMemoryRequirements requirements; vkGetBufferMemoryRequirements(device, buffer, &requirements);
    VkPhysicalDeviceMemoryProperties memory; vkGetPhysicalDeviceMemoryProperties(physical[0], &memory);
    uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
        const uint32_t flags = memory.memoryTypes[i].propertyFlags;
        if ((requirements.memoryTypeBits & (1u << i)) && (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            !!(flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) == coherent) {
            type = i;
            break;
        }
    }
    REQUIRE(type != UINT32_MAX);
    printf("VENUS_GUEST_MEMORY type=%u flags=%x size=%llu\n", type, memory.memoryTypes[type].propertyFlags, (unsigned long long)requirements.size);
    if (coherent) {
        REQUIRE(memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    } else {
        REQUIRE(memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        REQUIRE(!(memory.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
    }
    VkMemoryAllocateInfo ma = {.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=requirements.size,.memoryTypeIndex=type};
    VkDeviceMemory allocation;
    VK_CHECK(vkAllocateMemory(device, &ma, NULL, &allocation));
    VK_CHECK(vkBindBufferMemory(device, buffer, allocation, 0));
    void *mapping;
    VK_CHECK(vkMapMemory(device, allocation, 0, VK_WHOLE_SIZE, 0, &mapping));
    printf("VENUS_GUEST_SHARED_MAPPING=true\n");
    VkDescriptorSetLayoutBinding binding = {.binding=0,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1,.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT};
    VkDescriptorSetLayoutCreateInfo lc = {.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,.bindingCount=1,.pBindings=&binding};
    VkDescriptorSetLayout layout;
    VK_CHECK(vkCreateDescriptorSetLayout(device, &lc, NULL, &layout));
    VkPipelineLayoutCreateInfo plc = {.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,.setLayoutCount=1,.pSetLayouts=&layout};
    VkPipelineLayout pipeline_layout;
    VK_CHECK(vkCreatePipelineLayout(device, &plc, NULL, &pipeline_layout));
    VkShaderModuleCreateInfo sc = {.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,.codeSize=venus_guest_shader_len,.pCode=(const uint32_t *)venus_guest_shader};
    VkShaderModule shader;
    VK_CHECK(vkCreateShaderModule(device, &sc, NULL, &shader));
    VkComputePipelineCreateInfo pc = {.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,.stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_COMPUTE_BIT,.module=shader,.pName="main"},.layout=pipeline_layout};
    VkPipeline pipeline;
    VK_CHECK(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pc, NULL, &pipeline));
    VkDescriptorPoolSize pool_size = {.type=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1};
    VkDescriptorPoolCreateInfo dpc = {.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,.maxSets=1,.poolSizeCount=1,.pPoolSizes=&pool_size};
    VkDescriptorPool pool;
    VK_CHECK(vkCreateDescriptorPool(device, &dpc, NULL, &pool));
    VkDescriptorSetAllocateInfo sa = {.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,.descriptorPool=pool,.descriptorSetCount=1,.pSetLayouts=&layout};
    VkDescriptorSet set;
    VK_CHECK(vkAllocateDescriptorSets(device, &sa, &set));
    VkDescriptorBufferInfo bi = {.buffer=buffer,.offset=0,.range=bc.size};
    VkWriteDescriptorSet write = {.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=set,.dstBinding=0,.descriptorCount=1,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.pBufferInfo=&bi};
    vkUpdateDescriptorSets(device, 1, &write, 0, NULL);
    VkCommandPoolCreateInfo cpc = {.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,.queueFamilyIndex=family};
    VkCommandPool command_pool;
    VK_CHECK(vkCreateCommandPool(device, &cpc, NULL, &command_pool));
    VkCommandBufferAllocateInfo ca = {.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=command_pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    VkCommandBuffer command;
    VK_CHECK(vkAllocateCommandBuffers(device, &ca, &command));
    VkCommandBufferBeginInfo cb = {.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    VK_CHECK(vkBeginCommandBuffer(command, &cb));
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &set, 0, NULL);
    VkMemoryBarrier before = {.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT,.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &before, 0, NULL, 0, NULL);
    vkCmdDispatch(command, 16, 1, 1);
    VkMemoryBarrier after = {.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &after, 0, NULL, 0, NULL);
    VK_CHECK(vkEndCommandBuffer(command));
    VkFenceCreateInfo fc = {.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    VK_CHECK(vkCreateFence(device, &fc, NULL, &fence));
    const uint32_t seeds[] = {7, 0x12345678, 0xfedcba98};
    for (uint32_t pass = 0; pass < 3; ++pass) {
        uint32_t *words = mapping;
        for (uint32_t i = 0; i < 256; ++i) words[i] = venus_input(seeds[pass], i);
        REQUIRE(!venus_check_readback(words, 256, seeds[pass]));
        VkMappedMemoryRange range = {.sType=VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,.memory=allocation,.offset=0,.size=VK_WHOLE_SIZE};
        if (!skip_sync) VK_CHECK(vkFlushMappedMemoryRanges(device, 1, &range));
        VkSubmitInfo submit = {.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&command};
        VK_CHECK(vkQueueSubmit(queue, 1, &submit, fence));
        VK_CHECK(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_C(5000000000)));
        if (!skip_sync) VK_CHECK(vkInvalidateMappedMemoryRanges(device, 1, &range));
        if (!venus_check_readback(words, 256, seeds[pass])) {
            unsigned mismatch = 0;
            for (uint32_t i=0; i<256; ++i) if (words[i] != venus_expected(seeds[pass], i)) ++mismatch;
            printf("VENUS_GUEST_READBACK_MISMATCH pass=%u words=256 incorrect=%u observed0=%x expected0=%x\n", pass, mismatch, words[0], venus_expected(seeds[pass], 0));
            if (skip_sync && !coherent) { puts("VENUS_GUEST_NEGATIVE_CONTROL=EXPECTED_STALE_READBACK gpuFenceCompleted=true"); stage="negative-cache-readback"; }
            goto fail;
        }
        printf("VENUS_GUEST_READBACK pass=%u seed=%x words=256 correct=256\n", pass, seeds[pass]);
        VK_CHECK(vkResetFences(device, 1, &fence));
    }
    VK_CHECK(vkDeviceWaitIdle(device));
    vkUnmapMemory(device, allocation);
    vkDestroyFence(device, fence, NULL); vkDestroyCommandPool(device, command_pool, NULL);
    vkDestroyDescriptorPool(device, pool, NULL); vkDestroyPipeline(device, pipeline, NULL);
    vkDestroyShaderModule(device, shader, NULL); vkDestroyPipelineLayout(device, pipeline_layout, NULL);
    vkDestroyDescriptorSetLayout(device, layout, NULL); vkDestroyBuffer(device, buffer, NULL);
    vkFreeMemory(device, allocation, NULL); vkDestroyDevice(device, NULL); vkDestroyInstance(instance, NULL);
    puts("VENUS_GUEST_RESULT=PASS physicalGpu=true passes=3 wordsPerPass=256");
    if (desktop) return 0;
    for (;;) pause();
fail:
    printf("VENUS_GUEST_RESULT=FAIL stage=%s\n", stage);
    if (desktop) return 1;
    for (;;) pause();
}

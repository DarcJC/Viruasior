const test=require('node:test');
const assert=require('node:assert/strict');
const {inspectVulkanResource}=require('./assert-guest-vulkan-resource.cjs');
const capturedCrash=`DIAG_VK_RESOURCE_BEGIN
PYTHON_SYNTAX_PASS
VK_UID=1000 VK_GID=100 VK_GROUPS=[27, 44, 100, 991, 1000]
VULKAN_ABI_LAYOUT_PASS
VK_STAGE=vkCreateInstance
Segmentation fault
GUEST_VK_RESOURCE_EXIT=139
DIAG_VK_RESOURCE_END
`;
const good=`DIAG_VK_RESOURCE_BEGIN
PYTHON_SYNTAX_PASS
VK_UID=1000 VK_GID=100 VK_GROUPS=[27, 44, 100, 991, 1000]
VULKAN_ABI_LAYOUT_PASS
VK_STAGE=vkCreateInstance
VK_DEVICE=Mali-G2-Ultra-NX MC16 VENDOR=0x13b5 TYPE=1
VK_STAGE=vkCreateDevice
VK_STAGE=vkAllocateMemory
VK_STAGE=vkMapMemory
VK_STAGE=vkQueueSubmit
VK_STAGE=vkQueueWaitIdle
VK_BUFFER_READBACK=${JSON.stringify(Array(16).fill(0x12345678))}
VULKAN_BUFFER_TRANSFER_PASS (not shader rendering or Android presentation)
VK_STAGE=cleanupDeviceWaitIdle
GUEST_VK_RESOURCE_EXIT=0
DIAG_VK_RESOURCE_END
`;

test('replays the captured instance-creation segfault as a failure',()=>{
    assert.equal(inspectVulkanResource(capturedCrash).ok,false);
});
test('a verified buffer result does not imply shader execution or presentation',()=>{
    const result=inspectVulkanResource(good);
    assert.equal(result.ok,true);
    assert.equal(result.bufferTransferProven,true);
    assert.equal(result.shaderProven,false);
    assert.equal(result.presentationProven,false);
});
test('child crash, timeout, missing exit and conflicting exits fail even with a pass marker',()=>{
    for(const text of [good.replace('EXIT=0','EXIT=139'),good.replace('EXIT=0','EXIT=124'),
        good.replace('GUEST_VK_RESOURCE_EXIT=0\n',''),good.replace('EXIT=0','EXIT=0\nGUEST_VK_RESOURCE_EXIT=139')]) {
        assert.equal(inspectVulkanResource(text).ok,false);
    }
});
test('identity, ABI, device and command/readback evidence are mandatory',()=>{
    for(const text of [good.replace('VK_UID=1000','VK_UID=0'),good.replace('VULKAN_ABI_LAYOUT_PASS',''),
        good.replace('VENDOR=0x13b5','VENDOR=0x10005'),good.replace('TYPE=1','TYPE=4'),
        good.replace('Mali-G2-Ultra-NX MC16','Mali llvmpipe'),good.replace('VK_STAGE=vkQueueSubmit',''),
        good.replace('VK_STAGE=vkMapMemory',''),good.replace('VK_STAGE=vkQueueWaitIdle',''),
        good.replace('VK_STAGE=cleanupDeviceWaitIdle',''),good.replace('305419896','0')]) {
        assert.equal(inspectVulkanResource(text).ok,false);
    }
});
test('markers, readback and stages cannot be duplicated or out of order',()=>{
    for(const text of [good+good,good.replace('DIAG_VK_RESOURCE_END',''),
        good.replace('VK_STAGE=vkCreateInstance','VK_STAGE=vkCreateInstance\nVK_STAGE=vkCreateInstance'),
        good.replace('VK_STAGE=vkQueueSubmit\nVK_STAGE=vkQueueWaitIdle','VK_STAGE=vkQueueWaitIdle\nVK_STAGE=vkQueueSubmit'),
        good.replace('VK_BUFFER_READBACK=','VK_BUFFER_READBACK=[]\nVK_BUFFER_READBACK='),
        good.replace('VK_BUFFER_READBACK=[','VK_BUFFER_READBACK=invalid[')]) {
        assert.equal(inspectVulkanResource(text).ok,false);
    }
});
test('evidence outside the selected markers cannot substitute',()=>{
    assert.equal(inspectVulkanResource(good.replace('VK_DEVICE=Mali-G2-Ultra-NX MC16 VENDOR=0x13b5 TYPE=1\n','')+
        '\nVK_DEVICE=Mali-G2-Ultra-NX MC16 VENDOR=0x13b5 TYPE=1\n').ok,false);
});

#!/usr/bin/env python3
"""Direct gfxstream Vulkan device/buffer/submit/readback diagnostic (no EGL/WSI).

Use test UID 1000 and only the gfxstream ICD. Caller must impose a timeout.
A buffer-transfer pass is NOT a shader-rendering or Android-presentation pass.
"""

import ctypes as C
import os
import sys

U, H, P, Q = C.c_uint32, C.c_uint64, C.c_void_p, C.c_uint64
GFX_ICD = "/usr/share/vulkan/icd.d/gfxstream_vk_icd.json"
SIZE = 64
PATTERN = 0x12345678


def struct(name, fields):
    return type(name, (C.Structure,), {"_fields_": fields})


header = [("sType", U), ("pNext", P)]
AppInfo = struct("AppInfo", header + [("name", C.c_char_p), ("version", U),
    ("engine", C.c_char_p), ("engineVersion", U), ("apiVersion", U)])
InstanceInfo = struct("InstanceInfo", header + [("flags", U), ("app", P),
    ("layerCount", U), ("layers", P), ("extensionCount", U), ("extensions", P)])
QueueInfo = struct("QueueInfo", header + [("flags", U), ("family", U), ("count", U), ("priorities", P)])
DeviceInfo = struct("DeviceInfo", header + [("flags", U), ("queueCount", U), ("queues", P),
    ("layerCount", U), ("layers", P), ("extensionCount", U), ("extensions", P), ("features", P)])
QueueProperties = struct("QueueProperties", [("flags", U), ("count", U), ("timestampBits", U),
    ("width", U), ("height", U), ("depth", U)])
BufferInfo = struct("BufferInfo", header + [("flags", U), ("size", Q), ("usage", U),
    ("sharing", U), ("familyCount", U), ("families", P)])
Requirements = struct("Requirements", [("size", Q), ("alignment", Q), ("typeBits", U)])
MemoryType = struct("MemoryType", [("flags", U), ("heap", U)])
MemoryHeap = struct("MemoryHeap", [("size", Q), ("flags", U)])
MemoryProperties = struct("MemoryProperties", [("typeCount", U), ("types", MemoryType * 32),
    ("heapCount", U), ("heaps", MemoryHeap * 16)])
AllocateInfo = struct("AllocateInfo", header + [("size", Q), ("type", U)])
MappedRange = struct("MappedRange", header + [("memory", H), ("offset", Q), ("size", Q)])
PoolInfo = struct("PoolInfo", header + [("flags", U), ("family", U)])
CommandInfo = struct("CommandInfo", header + [("pool", H), ("level", U), ("count", U)])
BeginInfo = struct("BeginInfo", header + [("flags", U), ("inheritance", P)])
Barrier = struct("Barrier", header + [("sourceAccess", U), ("destAccess", U)])
SubmitInfo = struct("SubmitInfo", header + [("waitCount", U), ("waits", P), ("waitStages", P),
    ("commandCount", U), ("commands", P), ("signalCount", U), ("signals", P)])


def run():
    if os.geteuid() != 1000 or not os.access("/dev/dri/renderD128", os.R_OK | os.W_OK):
        raise RuntimeError("test UID 1000 and readable/writable render node required")
    if any(os.environ.get(k) != GFX_ICD for k in ("VK_DRIVER_FILES", "VK_ICD_FILENAMES")):
        raise RuntimeError("only the explicit gfxstream ICD is allowed")
    print("VK_UID=%s VK_GID=%s VK_GROUPS=%s" %
          (os.geteuid(), os.getegid(), os.getgroups()), flush=True)
    expected_sizes = {AppInfo: 48, InstanceInfo: 64, QueueInfo: 40, DeviceInfo: 72,
        QueueProperties: 24, BufferInfo: 56, Requirements: 24, MemoryProperties: 520,
        AllocateInfo: 32, MappedRange: 40, PoolInfo: 24, CommandInfo: 32,
        BeginInfo: 32, Barrier: 24, SubmitInfo: 72}
    if C.sizeof(P) != 8 or any(C.sizeof(kind) != size for kind, size in expected_sizes.items()):
        raise RuntimeError("unexpected native Vulkan structure layout")
    print("VULKAN_ABI_LAYOUT_PASS", flush=True)
    vk = C.CDLL("libvulkan.so.1")

    def fn(name, result, args):
        function = getattr(vk, name)
        function.restype, function.argtypes = result, args
        return function

    def call(name, args, *values):
        print("VK_STAGE=" + name, flush=True)
        result = fn(name, C.c_int32, args)(*values)
        if result != 0:
            print("VK_ERROR=%s RESULT=%s" % (name, result), flush=True)
            raise RuntimeError("%s returned VkResult %s" % (name, result))

    instance, device, buffer, memory, pool, mapped = P(), P(), H(), H(), H(), P()
    try:
        app = AppInfo(0, None, b"Viruaisor resource probe", 1, b"probe", 1, (1 << 22) | (1 << 12))
        info = InstanceInfo(1, None, 0, C.addressof(app), 0, None, 0, None)
        call("vkCreateInstance", [P, P, P], C.byref(info), None, C.byref(instance))
        count = U()
        enum = fn("vkEnumeratePhysicalDevices", C.c_int32, [P, P, P])
        result = enum(instance, C.byref(count), None)
        if result != 0 or count.value != 1:
            raise RuntimeError("expected exactly one gfxstream physical device")
        devices = (P * count.value)()
        if enum(instance, C.byref(count), devices) != 0:
            raise RuntimeError("physical-device enumeration failed")
        physical = devices[0]
        properties = C.create_string_buffer(4096)
        fn("vkGetPhysicalDeviceProperties", None, [P, P])(physical, properties)
        raw = properties.raw
        vendor = int.from_bytes(raw[8:12], "little")
        kind = int.from_bytes(raw[16:20], "little")
        name = raw[20:276].split(b"\0")[0].decode("utf8", "replace")
        print("VK_DEVICE=%s VENDOR=0x%x TYPE=%s" % (name, vendor, kind), flush=True)
        if vendor != 0x13B5 or "mali" not in name.lower() or kind == 4:
            raise RuntimeError("not the intended non-CPU Mali device")

        qcount = U()
        queues = fn("vkGetPhysicalDeviceQueueFamilyProperties", None, [P, P, P])
        queues(physical, C.byref(qcount), None)
        qprops = (QueueProperties * qcount.value)()
        queues(physical, C.byref(qcount), qprops)
        family = next((i for i, q in enumerate(qprops) if q.count and q.flags & 7), None)
        if family is None:
            raise RuntimeError("no transfer-capable queue")
        priority = C.c_float(1)
        queue_info = QueueInfo(2, None, 0, family, 1, C.addressof(priority))
        device_info = DeviceInfo(3, None, 0, 1, C.addressof(queue_info), 0, None, 0, None, None)
        call("vkCreateDevice", [P, P, P, P], physical, C.byref(device_info), None, C.byref(device))
        queue = P()
        fn("vkGetDeviceQueue", None, [P, U, U, P])(device, family, 0, C.byref(queue))

        # TRANSFER_DST buffer; no optional extensions/features or swapchain.
        buffer_info = BufferInfo(12, None, 0, SIZE, 2, 0, 0, None)
        call("vkCreateBuffer", [P, P, P, P], device, C.byref(buffer_info), None, C.byref(buffer))
        requirements = Requirements()
        fn("vkGetBufferMemoryRequirements", None, [P, H, P])(device, buffer, C.byref(requirements))
        mprops = MemoryProperties()
        fn("vkGetPhysicalDeviceMemoryProperties", None, [P, P])(physical, C.byref(mprops))
        if mprops.typeCount > 32 or requirements.size < SIZE:
            raise RuntimeError("invalid buffer memory properties")
        candidates = [i for i in range(mprops.typeCount)
                      if requirements.typeBits & (1 << i) and mprops.types[i].flags & 2]
        if not candidates:
            raise RuntimeError("no compatible HOST_VISIBLE memory type")
        index = min(candidates, key=lambda i: not bool(mprops.types[i].flags & 4))
        coherent = bool(mprops.types[index].flags & 4)
        print("VK_MEMORY_TYPE=%s FLAGS=0x%x ALLOCATION=%s" %
              (index, mprops.types[index].flags, requirements.size), flush=True)
        allocation = AllocateInfo(5, None, requirements.size, index)
        call("vkAllocateMemory", [P, P, P, P], device, C.byref(allocation), None, C.byref(memory))
        call("vkBindBufferMemory", [P, H, H, Q], device, buffer, memory, 0)
        call("vkMapMemory", [P, H, Q, Q, U, P], device, memory, 0, requirements.size, 0, C.byref(mapped))
        if not mapped.value:
            raise RuntimeError("mapped memory pointer is null")
        words = (U * (SIZE // 4)).from_address(mapped.value)
        for i in range(len(words)):
            words[i] = 0xA5A5A5A5
        region = MappedRange(6, None, memory.value, 0, (1 << 64) - 1)
        if not coherent:
            call("vkFlushMappedMemoryRanges", [P, U, P], device, 1, C.byref(region))

        pool_info = PoolInfo(39, None, 0, family)
        call("vkCreateCommandPool", [P, P, P, P], device, C.byref(pool_info), None, C.byref(pool))
        command_info = CommandInfo(40, None, pool.value, 0, 1)
        command = P()
        call("vkAllocateCommandBuffers", [P, P, P], device, C.byref(command_info), C.byref(command))
        begin = BeginInfo(42, None, 1, None)
        call("vkBeginCommandBuffer", [P, P], command, C.byref(begin))
        fn("vkCmdFillBuffer", None, [P, H, Q, Q, U])(command, buffer, 0, SIZE, PATTERN)
        barrier = Barrier(46, None, 0x1000, 0x2000)  # TRANSFER_WRITE -> HOST_READ
        fn("vkCmdPipelineBarrier", None, [P, U, U, U, U, P, U, P, U, P])(
            command, 0x1000, 0x4000, 0, 1, C.byref(barrier), 0, None, 0, None)
        call("vkEndCommandBuffer", [P], command)
        submit = SubmitInfo(4, None, 0, None, None, 1, C.addressof(command), 0, None)
        call("vkQueueSubmit", [P, U, P, H], queue, 1, C.byref(submit), 0)
        call("vkQueueWaitIdle", [P], queue)
        if not coherent:
            call("vkInvalidateMappedMemoryRanges", [P, U, P], device, 1, C.byref(region))
        actual = list(words)
        print("VK_BUFFER_READBACK=" + str(actual), flush=True)
        if actual != [PATTERN] * len(words):
            raise RuntimeError("Vulkan command buffer readback mismatch")
        print("VULKAN_BUFFER_TRANSFER_PASS (not shader rendering or Android presentation)", flush=True)
    finally:
        if device.value:
            # Timeout/kill handles a blocked native call; normal returns destroy owned resources.
            print("VK_STAGE=cleanupDeviceWaitIdle", flush=True)
            fn("vkDeviceWaitIdle", C.c_int32, [P])(device)
            if pool.value:
                fn("vkDestroyCommandPool", None, [P, H, P])(device, pool, None)
            if mapped.value:
                fn("vkUnmapMemory", None, [P, H])(device, memory)
            if buffer.value:
                fn("vkDestroyBuffer", None, [P, H, P])(device, buffer, None)
            if memory.value:
                fn("vkFreeMemory", None, [P, H, P])(device, memory, None)
            fn("vkDestroyDevice", None, [P, P])(device, None)
        if instance.value:
            fn("vkDestroyInstance", None, [P, P])(instance, None)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print("VULKAN_RESOURCE_FAIL: " + str(error), file=sys.stderr, flush=True)
        sys.exit(1)

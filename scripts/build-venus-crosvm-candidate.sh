#!/usr/bin/env bash
# Local build only; packaging/deployment requires a separately reviewed artifact pin.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work=${VENUS_CROSVM_WORK_DIR:-"$repo/.pi/device-debug/venus-crosvm-build"}
renderer_work=${VENUS_WORK_DIR:?Set VENUS_WORK_DIR to the pinned renderer workspace}
node_runner=${NODE:-node}
if ! command -v "$node_runner" >/dev/null 2>&1 && command -v node.exe >/dev/null 2>&1; then
    node_runner=node.exe
fi
[[ $(git -C "$work/rutabaga" rev-parse HEAD) == 1f181b0b3e4b821459f21fd9025cd4c408c4f9ec ]]
apply_renderer_patch() {
    local name=$1 marker=$2 source_file=$3
    local patch="$repo/native/venus-crosvm/$name.patch"
    if git -C "$renderer_work/virglrenderer" apply --check "$patch" 2>/dev/null; then
        git -C "$renderer_work/virglrenderer" apply "$patch"
    elif grep -Fq "$marker" "$renderer_work/virglrenderer/$source_file"; then
        : # already applied, possibly with later patches touching the same hunk
    elif git -C "$renderer_work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null; then
        : # already applied exactly
    else
        echo "$name.patch is neither applicable nor present" >&2
        exit 1
    fi
}
for item in crosvm rutabaga; do
    patch="$repo/native/venus-crosvm/$item-candidate.patch"
    if git -C "$work/$item" apply --unidiff-zero --check "$patch" 2>/dev/null; then
        git -C "$work/$item" apply --unidiff-zero "$patch"
    else
        git -C "$work/$item" apply --unidiff-zero --reverse --check "$patch"
    fi
done
cp "$repo/native/venus-crosvm/renderer_context_ids.rs" \
    "$work/rutabaga/src/renderer_context_ids.rs"
cp "$repo/native/venus-crosvm/renderer_context_init.rs" \
    "$work/rutabaga/src/renderer_context_init.rs"
rustc --test "$repo/native/venus-crosvm/renderer_context_ids.rs" \
    -o /tmp/viruaisor-renderer-context-ids-test
/tmp/viruaisor-renderer-context-ids-test
rustc --test "$repo/native/venus-crosvm/renderer_context_init.rs" \
    -o /tmp/viruaisor-renderer-context-init-test
/tmp/viruaisor-renderer-context-init-test
patch="$repo/native/venus-crosvm/rutabaga-context-id-remap.patch"
if git -C "$work/rutabaga" apply --ignore-space-change --check "$patch" 2>/dev/null; then
    git -C "$work/rutabaga" apply --ignore-space-change "$patch"
elif grep -Fq 'VIRUAISOR_CONTEXT_ID_REMAP_RELEASE_FAILED' \
        "$work/rutabaga/src/virgl_renderer.rs"; then
    : # already applied
elif git -C "$work/rutabaga" apply --ignore-space-change --reverse --check "$patch" 2>/dev/null; then
    : # already applied exactly
else
    echo 'rutabaga-context-id-remap.patch is neither applicable nor present' >&2
    exit 1
fi
patch="$repo/native/venus-crosvm/renderer-android-runtime.patch"
if git -C "$renderer_work/virglrenderer" apply --check "$patch" 2>/dev/null; then
    git -C "$renderer_work/virglrenderer" apply "$patch"
elif git -C "$renderer_work/virglrenderer" apply --reverse --check "$patch" 2>/dev/null; then
    : # already applied exactly
elif grep -q 'vkr_android_import' \
        "$renderer_work/virglrenderer/src/venus/vkr_device_memory.c"; then
    : # already applied and extended by dependent diagnostic/fix patches
else
    echo 'renderer-android-runtime.patch is neither applicable nor present' >&2
    exit 1
fi
cp "$repo/native/venus-crosvm/venus_dmaheap.h" "$renderer_work/virglrenderer/src/venus/venus_dmaheap.h"
cp "$repo/native/venus-crosvm/venus_gralloc.h" "$repo/native/venus-crosvm/venus_gralloc_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_cache_sync.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_dma_buf_size_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_import_type_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_ahb_registry.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_log_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_display_fence.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_ahb_bind_policy.h" "$renderer_work/virglrenderer/src/venus/"
cp "$repo/native/venus-crosvm/venus_ahb_image_size.h" "$renderer_work/virglrenderer/src/venus/"
if [[ ${VENUS_CROSVM_DISPLAY:-0} == 1 ]]; then
    patch="$repo/native/venus-crosvm/crosvm-fixed-blob-mapping.patch"
    git -C "$work/crosvm" apply --reverse --check "$patch" 2>/dev/null || \
        git -C "$work/crosvm" apply "$patch"
    patch="$repo/native/venus-crosvm/android-dmabuf-display.patch"
    if ! grep -Fq DmabufFrameInfo "$work/crosvm/gpu_display/src/gpu_display_android.rs"; then
        git -C "$work/crosvm" apply "$patch"
    fi
    patch="$repo/native/venus-crosvm/crosvm-context-scanout-trace.patch"
    if grep -Fq 'VIRUAISOR_CONTEXT_COMMAND op=create' \
            "$work/crosvm/devices/src/virtio/gpu/mod.rs" && \
            grep -Fq 'VIRUAISOR_SCANOUT_FLIP' \
            "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        : # already applied, possibly with later patches touching the same hunk
    elif git -C "$work/crosvm" apply --check "$patch" 2>/dev/null; then
        git -C "$work/crosvm" apply "$patch"
    elif git -C "$work/crosvm" apply --reverse --check "$patch" 2>/dev/null; then
        : # already applied exactly
    else
        echo 'crosvm-context-scanout-trace.patch is neither applicable nor present' >&2
        exit 1
    fi
    patch="$repo/native/venus-crosvm/crosvm-context-create-failure-trace.patch"
    git -C "$work/crosvm" apply --reverse --check "$patch" 2>/dev/null || \
        git -C "$work/crosvm" apply "$patch"
    patch="$repo/native/venus-crosvm/crosvm-blob-scanout-failure-trace.patch"
    if grep -Fq 'VIRUAISOR_BLOB_CREATE_FAILED' \
            "$work/crosvm/devices/src/virtio/gpu/mod.rs"; then
        : # already applied
    elif git -C "$work/crosvm" apply --check "$patch" 2>/dev/null; then
        git -C "$work/crosvm" apply "$patch"
    elif git -C "$work/crosvm" apply --reverse --check "$patch" 2>/dev/null; then
        : # already applied exactly
    else
        echo 'crosvm-blob-scanout-failure-trace.patch is neither applicable nor present' >&2
        exit 1
    fi
    patch="$repo/native/venus-crosvm/rutabaga-display-sync.patch"
    if grep -Fq 'fn display_flush(&self) -> RutabagaResult<()>' \
            "$work/rutabaga/src/virgl_renderer.rs"; then
        : # already applied
    elif git -C "$work/rutabaga" apply --unidiff-zero --check "$patch" 2>/dev/null; then
        git -C "$work/rutabaga" apply --unidiff-zero "$patch"
    else
        echo 'rutabaga-display-sync.patch is neither applicable nor present' >&2
        exit 1
    fi
    patch="$repo/native/venus-crosvm/crosvm-display-sync.patch"
    if grep -Fq 'rutabaga.display_flush()' \
            "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        : # already applied
    elif git -C "$work/crosvm" apply --unidiff-zero --check "$patch" 2>/dev/null; then
        git -C "$work/crosvm" apply --unidiff-zero "$patch"
    else
        echo 'crosvm-display-sync.patch is neither applicable nor present' >&2
        exit 1
    fi
    if [[ "$node_runner" == *.exe ]]; then
        "$node_runner" "$(wslpath -w "$repo/scripts/assert-crosvm-gpu-trace.cjs")" \
            "$(wslpath -w "$work/crosvm")"
    else
        "$node_runner" "$repo/scripts/assert-crosvm-gpu-trace.cjs" "$work/crosvm"
    fi
    apply_renderer_patch android-vulkan-loader-trace VIRUAISOR_VK_LOADER_LOAD_BEGIN src/venus/vkr_library.c
    apply_renderer_patch android-display-quiet-trace venus_log_is_verbose src/venus/vkr_common.c
    apply_renderer_patch android-shmem-trace VIRUAISOR_BLOB_EXPORT_READY src/venus/vkr_context.c
    apply_renderer_patch android-ring-trace VIRUAISOR_CONTEXT_SUBMIT src/venus/vkr_context.c
    apply_renderer_patch android-display-sync VIRUAISOR_DISPLAY_SYNC_BOUNDED_RING_DRAIN src/venus/vkr_renderer.c
    apply_renderer_patch android-display-gpu-completion VIRUAISOR_DISPLAY_SYNC_GPU_FENCE src/venus/vkr_renderer.c
    apply_renderer_patch android-display-gpu-null-slot VIRUAISOR_DISPLAY_SYNC_GPU_NULL_SLOT src/venus/vkr_context.c
    apply_renderer_patch android-display-fence-lifetime VIRUAISOR_DISPLAY_FENCE_LIFETIME src/venus/vkr_queue.c
    apply_renderer_patch android-display-sync-logging display_last_report_ns src/venus/vkr_renderer.c
    patch="$repo/native/venus-crosvm/crosvm-display-deferred.patch"
    if ! grep -Fq 'code == -libc::EAGAIN' "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        git -C "$work/crosvm" apply "$patch"
    fi
    if ! grep -Fq VIRUAISOR_SCANOUT_IMPORT_GENERATION "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-scanout-import-generation.patch"
    fi
    if ! grep -Fq VIRUAISOR_DISPLAY_IMPORT_RELEASE "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-display-import-lifetime.patch"
    fi
    if ! grep -Fq VIRUAISOR_BLOB_MAPPING_RELEASE "$work/crosvm/devices/src/virtio/gpu/virtio_gpu.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-blob-mapping-lifetime.patch"
    fi
    if ! grep -Fq VIRUAISOR_DESKTOP_HIGH_REFRESH "$work/crosvm/devices/src/virtio/gpu/edid.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-desktop-high-refresh.patch"
    fi
    if ! grep -Fq VIRUAISOR_DESKTOP_ASPECT "$work/crosvm/devices/src/virtio/gpu/edid.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-desktop-aspect.patch"
    fi
    cp "$repo/native/venus-crosvm/venus_gpu_arena.rs" "$work/crosvm/vm_control/src/sys/"
    if ! grep -Fq VIRUAISOR_GPU_PAGE_MAPPING "$work/crosvm/vm_control/src/sys/linux.rs"; then
        git -C "$work/crosvm" apply "$repo/native/venus-crosvm/crosvm-gpu-page-mapping.patch"
    fi
    if ! grep -Fq VIRUAISOR_DISPLAY_TIMING "$work/rutabaga/src/virgl_renderer.rs"; then
        git -C "$work/rutabaga" apply "$repo/native/venus-crosvm/rutabaga-display-timing.patch"
    fi
    cp "$repo/native/venus-crosvm/venus_display_batch.rs" "$work/rutabaga/src/"
    if ! grep -Fq 'display_batch::wait_all' "$work/rutabaga/src/virgl_renderer.rs"; then
        git -C "$work/rutabaga" apply "$repo/native/venus-crosvm/rutabaga-display-batch.patch"
    fi
    CROSVM_EDID_SOURCE="$work/crosvm/devices/src/virtio/gpu/edid.rs" bash "$repo/scripts/test-venus-edid.sh"
    patch="$repo/native/venus-crosvm/crosvm-memory-map-trace.patch"
    git -C "$work/crosvm" apply --reverse --check "$patch" 2>/dev/null || \
        git -C "$work/crosvm" apply "$patch"
fi
if [[ ${VENUS_CROSVM_IMPORT_FILTER:-0} == 1 ]]; then
    [[ ${VENUS_CROSVM_DISPLAY:-0} == 1 ]] # Experimental desktop comparison only.
    cp "$repo/native/venus-crosvm/venus_import_memory_policy.h" \
        "$repo/native/venus-crosvm/venus_android_memory_properties.h" "$renderer_work/virglrenderer/src/venus/"
    cp "$repo/native/venus-memory/uncached_"{client,protocol,types,registry}.h \
        "$renderer_work/virglrenderer/src/venus/"
    apply_renderer_patch android-import-memory-properties venus_android_memory_properties.h src/venus/vkr_physical_device.c
    apply_renderer_patch android-import-buffer-requirements VIRUAISOR_BUFFER_IMPORT_TYPES src/venus/vkr_buffer.c
    apply_renderer_patch android-dmabuf-lifetime-trace VIRUAISOR_DMA_BUF_FD src/venus/vkr_device_memory.c
    apply_renderer_patch android-gralloc-priority VIRUAISOR_GRALLOC_IMPORT_TYPE_REMAP src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-display-registry viruaisor_ahb_acquire_from_fd src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-export-size VIRUAISOR_DMA_BUF_AHB_SIZE_PADDING src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-image-sharing VIRUAISOR_AHB_IMAGE_IMPORT src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-import-diagnostics VIRUAISOR_AHB_IMAGE_IMPORT_COMPAT src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-descriptor-trace VIRUAISOR_AHB_DESCRIPTOR src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-import-memory-type 'const uint32_t compatible = properties.memoryTypeBits' src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-resource-import-type 'driver_mem_type_index = alloc_info->memoryTypeIndex' src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-resource-pnext VIRUAISOR_AHB_RESOURCE_PNEXT src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-resource-export-chain VIRUAISOR_AHB_RESOURCE_EXPORT_STRIPPED src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-optimal-tiling VIRUAISOR_IMAGE_EXTERNAL_AHB_RESULT src/venus/vkr_image.c
    apply_renderer_patch android-ahb-image-candidate-trace VIRUAISOR_IMAGE_CANDIDATE src/venus/vkr_image.c
    apply_renderer_patch android-ahb-optimal-source-sharing VIRUAISOR_AHB_OPTIMAL_SOURCE src/venus/vkr_image.c
    apply_renderer_patch android-ahb-modifier-source-sharing VIRUAISOR_AHB_MODIFIER_SOURCE src/venus/vkr_image.c
    apply_renderer_patch android-ahb-guest-layout VIRUAISOR_AHB_GUEST_LAYOUT src/venus/vkr_image.c
    apply_renderer_patch android-ahb-memory-requirements-trace VIRUAISOR_AHB_IMAGE_REQUIREMENTS src/venus/vkr_image.c
    apply_renderer_patch android-ahb-memory-requirements-fix VIRUAISOR_AHB_IMAGE_REQUIREMENTS_SYNTHETIC src/venus/vkr_image.c
    apply_renderer_patch android-ahb-rgba-host-format VIRUAISOR_AHB_HOST_FORMAT src/venus/vkr_image.c
    apply_renderer_patch android-ahb-image-view-format VIRUAISOR_AHB_IMAGE_VIEW src/venus/vkr_image.c
    apply_renderer_patch android-ahb-write-path-trace VIRUAISOR_AHB_WRITE_COMMAND src/venus/vkr_command_buffer.c
    apply_renderer_patch android-ahb-compatible-image-view VIRUAISOR_AHB_COMPATIBLE_IMAGE_VIEW src/venus/vkr_image.c
    apply_renderer_patch android-memory-publish-order VIRUAISOR_MEMORY_PUBLISH_READY src/venus/vkr_device_memory.c
    apply_renderer_patch android-venus-teardown VIRUAISOR_ANDROID_DEVICE_TEARDOWN_ORDER src/venus/vkr_device.c
    apply_renderer_patch android-renderer-fd-trace VIRUAISOR_PROXY_FD src/proxy/proxy_context.c
    apply_renderer_patch android-allocation-quiet-trace VIRUAISOR_ALLOCATION_QUIET_TRACE src/venus/vkr_device_memory.c
    apply_renderer_patch android-ahb-bind-contract VIRUAISOR_AHB_BIND_CONTRACT src/venus/vkr_image.c
    apply_renderer_patch android-ahb-layout-requirements VIRUAISOR_AHB_LAYOUT_REQUIREMENTS src/venus/vkr_image.c
    apply_renderer_patch android-ahb-resource-lifetime VIRUAISOR_AHB_RESOURCE_LIFETIME src/venus/vkr_device_memory.c
    apply_renderer_patch android-display-batch-sync virgl_renderer_context_wait_idle_begin src/virglrenderer.c
    apply_renderer_patch android-uncached-memory VIRUAISOR_UNCACHED_IMPORT src/venus/vkr_device_memory.c
    apply_renderer_patch android-host-cache-maintenance vkr_android_uncached_cache_maintain src/venus/vkr_device_memory.c
    for test in protocol types registry; do
        gcc -Wall -Wextra -Werror -fsanitize=undefined \
            "$repo/native/venus-memory/uncached_${test}_test.c" -o "/tmp/viruaisor-uncached-${test}-test"
        "/tmp/viruaisor-uncached-${test}-test"
    done
    gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_import_memory_policy_test.c" -o /tmp/viruaisor-venus-import-policy-test
    /tmp/viruaisor-venus-import-policy-test
elif git -C "$renderer_work/virglrenderer" apply --reverse --check \
        "$repo/native/venus-crosvm/android-import-memory-properties.patch" 2>/dev/null; then
    echo 'Experimental import-filter source requires explicit VENUS_CROSVM_IMPORT_FILTER=1 and display=1.' >&2
    exit 1
fi
if [[ ${VENUS_CROSVM_DISPLAY:-0} == 1 ]]; then
    # Apply after optional DMA-BUF tracing, whose hunk still names the original
    # generated destroy helper. The fix also applies without import tracing.
    apply_renderer_patch android-memory-detach-free 'generated destroy helper resolves' src/venus/vkr_device_memory.c
fi
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_cache_sync_test.c" -o /tmp/viruaisor-venus-cache-test
/tmp/viruaisor-venus-cache-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_ahb_bind_policy_test.c" -o /tmp/viruaisor-ahb-bind-test
/tmp/viruaisor-ahb-bind-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_ahb_image_size_test.c" -o /tmp/viruaisor-ahb-image-size-test
/tmp/viruaisor-ahb-image-size-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_gralloc_policy_test.c" -o /tmp/viruaisor-venus-gralloc-policy-test
/tmp/viruaisor-venus-gralloc-policy-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_dmaheap_test.c" -o /tmp/viruaisor-venus-dmaheap-test
/tmp/viruaisor-venus-dmaheap-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_dma_buf_size_policy_test.c" -o /tmp/viruaisor-venus-dma-buf-size-policy-test
/tmp/viruaisor-venus-dma-buf-size-policy-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_import_type_policy_test.c" -o /tmp/viruaisor-venus-import-type-policy-test
/tmp/viruaisor-venus-import-type-policy-test
gcc -Wall -Wextra -Werror "$repo/native/venus-crosvm/venus_ahb_registry_test.c" -o /tmp/viruaisor-venus-ahb-registry-test
/tmp/viruaisor-venus-ahb-registry-test
ninja -C "$renderer_work/build-android"
# Rutabaga embeds the static renderer archive; make Cargo reconsider that
# dependency even when only the external archive changed.
touch "$work/rutabaga/src/virgl_renderer.rs"
cp "$repo/native/venus-crosvm/venus_init_policy.rs" "$work/rutabaga/src/venus_init_policy.rs"
rustc --test "$repo/native/venus-crosvm/venus_init_policy.rs" -o /tmp/viruaisor-venus-policy-test
/tmp/viruaisor-venus-policy-test
VENUS_CROSVM_CANDIDATE=1 bash "$repo/scripts/build-venus-crosvm-baseline.sh"

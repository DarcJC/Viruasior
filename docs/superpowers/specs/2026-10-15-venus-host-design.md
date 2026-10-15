# Android Venus host renderer and execution gate

The user approved the sequence on 2026-10-15: build the Android host renderer, verify
external-memory export/map and correct GPU workload readback, then integrate with AVF
and deliver a normal signed APK. Remove instrumentation/test applications from the device.

## Architecture and order

1. Pin upstream virglrenderer and Venus protocol revisions. Cross-build a Venus-only arm64
   Android library with the NDK. Keep downloads/build trees and captures in ignored `.pi`.
2. Add a bounded native host validation harness. First verify the actual Mali Vulkan
   driver, external-memory allocation/export/map, synchronization and arithmetic workload
   readback. Exercise the Venus renderer/protocol separately; direct Vulkan is only a
   prerequisite and cannot count as Venus execution or AVF guest acceleration.
3. Package the validated native components in the normal application, without a separate
   test APK or software fallback. Use a release APK for final application validation.
4. Attempt AVF integration only after the renderer gate passes. The existing system AVF
   lacks the Venus backend and public blob/render-server controls. Investigate an app-local
   integration boundary. A system-image replacement or persistent policy/root module is
   outside the current repository authorization and requires separate explicit approval.

Compared with replacing the system AVF immediately, a standalone host library isolates
Mali memory compatibility with substantially less coupling. Panfrost adds hardware/kernel
support work and is not part of this approved Venus implementation.

## Acceptance

- Identify the selected physical GPU and reject CPU/software devices.
- Validate every Vulkan result, exported FD, mapped size and synchronization operation.
- Seed input with distinguishable sentinels and validate every output word against a
  deterministic GPU computation. Queue completion and unchanged input are failures.
- Record memory type/properties, handle type and map strategy. Separate DMA_BUF mmap from
  opaque-FD Vulkan import/map; do not assume advertised extensions imply compatibility.
- Exercise renderer/protocol initialization and workload transport before claiming Venus.
- Keep root, permission, AVF startup, VM boot, GPU execution and frames as separate gates.
- Every native operation is bounded and cleans up its own Vulkan/FD/mapping/thread resources.
- Run required Android builds/unit tests and native/capture regression tests. Verify no
  installed test package, residual VM, mount, or helper remains after the work.

## Deployment and data

The existing main APK and imported VM data are retained during development. The test-only
`pro.darc.viruaisor.test` package is removed. Release signing must preserve update
compatibility or a deliberate data-preserving migration is required; never uninstall the
main app and erase its VM to resolve a signature mismatch.

# Root-owned BLAST Surface for the display-only helper

## Decision and scope

**Source-level feasible, but not yet a verified device fix.** Create the BufferQueue in the existing root helper, target a helper-created buffer-layer `SurfaceControl` beneath the caller-delegated `SurfaceView` parent, and send only that queue's `Surface` to crosvm. This changes the graphics Binder server from the ordinary application to the helper. Parenting under an app-owned layer does **not** move the new BufferQueue back into the app. SurfaceFlinger still owns the compositor's layer objects; “root-owned child” means created/managed through the helper's client connection, not a compositor running as root. [BQ-native], [SC-native]

This investigation made no device calls, ran no `adb`, changed no existing code or settings, and did not repeat the process-guard investigation. The only report added is this file. Public source downloads and local signature evidence are under ignored `.pi/research-cache/root-owned-surface/`.

Context read: `AGENT.md`, `docs/direct-surface-selinux-research.md`, `docs/root-display-broker.md`, `docs/root-display-broker-research.md`, `RootDisplayService.kt`, `RootAvfSurfaceBackend.kt`, and `DisplayBackend.kt`. At the forwarding baseline inspected for this report, the attach parcel contained a client death token and an app-created `Surface`; the helper forwarded that Surface unchanged. The subsequent V2 experiment is tracked in `root-display-broker.md` and does not turn this source research into a device success claim. Its authenticated, synchronous attach/detach protocol, single-VM limitation, operation watchdog, and short non-daemon lifetime remain the boundary. VM lifecycle, vsock, UI and input remain ordinary-app work.

## Evidence and version boundaries

The supplied existing device evidence is:

```text
binder: 24257:24225 transaction credentials failed
binder: 24225:24257 transaction call to 10589:0 failed .../-1,
        code 41 size 88-0 line 3477
crosvm: PID 24225 UID 10278 untrusted_app_all_virtmgr_crosvm
app:    PID 10589 UID 10278 untrusted_app
helper: PID 24251 UID 0     ksu
```

The additional supplied Binder diagnostics confirm crosvm's handle `2` targets app PID `10589` for code `41`; the credentials gate returns `-EPERM`. Handle numbers are process-local references, not globally meaningful service identifiers.

Together with the existing app-Surface forwarding path, this supports the crosvm-to-app graphics-endpoint diagnosis. It is not a new capture, a proof of the precise missing SELinux rule, or proof that this is the only cause of black output. “Root Direct Surface attached” proves successful attachment, not guest-frame presentation.

Sources were fetched with exact googlesource `?format=TEXT` URLs and decoded in the ignored cache. References below pin commits rather than silently tracking a branch:

| Evidence | Version actually inspected | Limit |
|---|---|---|
| AOSP Java/JNI graphics baseline | frameworks/base `1cdfff555f4a21f71ccc978290e2e212e2f8b168`, fetched from `main`; commit dated March 2025 | Not installed Android 17 framework code |
| Android-17 Java/JNI comparison | frameworks/base `94b4c163b7dfe5ce3607f7bb8456f9573f7de57d`, `android17-release`, May 2026 | Inspected BLAST Java/JNI and SurfaceView behavior; still not proof of OEM runtime identity |
| AOSP native graphics baseline | frameworks/native `4f463a6b1de9198963dc6aff74154a504ba3f8f6`, fetched from `main`; commit dated March 2025 | Feature-flagged native behavior may differ on the OEM build |
| Android common Binder/SELinux | `android16-6.12`, commit `3f843d67013171808264166f9288257b3c316b52` | Comparator, not an identification of the installed kernel revision |
| Local Android SDK | `platforms/android-37.0/android.jar` and `sources/android-37.0/`; both revision 2, API 37.0, extension 22, base SDK; platform version 17, preview SDK 0 | Public API availability and shipped Java source, not OEM runtime reflection/native compatibility |

The local SDK was inspected using the Android Studio JBR's `javap`; results are cached in `sdk37-public-javap.txt` and `sdk37-blast-javap.txt`. **`android.graphics.BLASTBufferQueue` is absent from public `android.jar`**, even though its implementation is in SDK sources. Most importantly, the local API-37 source has only the `(String, boolean)` constructor; the inspected AOSP-main Java source additionally has a five-argument constructor. Do not infer installed API-37 hidden signatures from mainline examples. [BQ-java]

The inspected `android17-release` BLAST Java source agrees with local API-37 sources: only the `(String, boolean)` constructor, followed by explicit `update`, is present. Its JNI also creates the native queue locally and exports `queue->getSurface(...)`. The release SurfaceView source establishes the fixed-buffer parent transform detailed below. This is a substantially better Java/JNI comparator than the older `main`, but matching installed OEM code and native libgui remains a validation gate. [BQ17-java], [BQ17-jni], [SV17-java]

## 1. Where the graphics Binder actually lives

The ownership trace is concrete:

1. Java `BLASTBufferQueue(String, boolean)` calls JNI `nativeCreate(String, boolean)`.
2. JNI constructs `new BLASTBufferQueue(...)` in the calling process and retains a native strong reference.
3. Native `BLASTBufferQueue::createBufferQueue(...)` allocates a local core, `BBQBufferQueueProducer`, and `BufferQueueConsumer`. `BBQBufferQueueProducer` subclasses `BufferQueueProducer`, which subclasses **`BnGraphicBufferProducer`**, the Binder server implementation. No request asks SurfaceFlinger or the parent-owning app to host this queue.
4. `BLASTBufferQueue::getSurface(false)` returns a `BBQSurface` backed by that local producer. Java `createSurface()` selects this false argument.
5. JNI Surface parceling writes the native Surface's `IGraphicBufferProducer` Binder and optional SurfaceControl handle. Sending the parcel gives crosvm a reference to the existing server; it does not recreate or relocate the queue. [BQ-java], [BQ-jni], [BQ-native], [BQP-header], [Surface-jni]

Precisely, the remotely invoked endpoint carried in a Surface is the **producer interface hosted alongside BLAST's consumer**, not a serialized `IGraphicBufferConsumer`. It is reasonable to describe the current problem as an app-owned consumer-side queue, but the actual crosvm Binder target is its `IGraphicBufferProducer` server. With helper construction, that server and the consumer/BLAST callbacks live in the helper.

```text
ordinary app -- delegated parent capability + size + death token --> helper
crosvm      -- IGraphicBufferProducer calls ----------------------> helper
helper BLAST -- buffer/fence transactions ------------------------> SurfaceFlinger
SurfaceFlinger -- completion/release notifications ---------------> helper
```

Producer-listener callbacks can additionally run helper-to-crosvm. Native BLAST wraps producer listeners for asynchronous dispatch, and consumes frames by submitting buffer/fence transactions to SurfaceFlinger. These paths still require Binder transfer/call, graphics-buffer and fence-FD access; replacing the endpoint does not bypass those gates. [BQ-native], [Binder], [SELinux]

Parent parceling transfers a compositor capability: native `SurfaceControl::writeToParcel` writes the compositor client and layer handle plus properties, **not the parent's BLAST producer/consumer**. Use it only to parent the owned child. Do not construct a Surface from the delegated parent or send the original app Surface alongside the replacement. [SC-native]

## 2. Exact APIs: public SDK versus helper-only reflection

The signatures below are Java signatures. `Builder` methods return `SurfaceControl.Builder`; `Transaction` setters return `SurfaceControl.Transaction`.

### App-side public capability and lifetime APIs

```java
public SurfaceControl SurfaceView.getSurfaceControl();
public boolean SurfaceControl.isValid();
public void SurfaceControl.release();
public void SurfaceControl.writeToParcel(Parcel dest, int flags);
public static final Parcelable.Creator<SurfaceControl> SurfaceControl.CREATOR;
```

`getSurfaceControl()` is the supported parent capability for child surfaces; its documentation restricts use of the returned control to that purpose. Do not alter/reparent/remove the SurfaceView's control itself. Obtain it while the view has a live surface, check validity, and parcel it normally with flags `0`; do not use `PARCELABLE_WRITE_RETURN_VALUE`, which can release the sender's local control. The helper owns only its deserialized local handle, not the app's Java object or parent lifecycle. [SV-java], [SC-java]

The existing public `IBinder.linkToDeath(IBinder.DeathRecipient, int)` / `unlinkToDeath(IBinder.DeathRecipient, int)` mechanism is appropriate for the app-created session token. That token is not a SurfaceView-parent death notification.

### Helper-created child: public SDK calls suffice on the inspected API-37 SDK

```java
public SurfaceControl.Builder();
public Builder setName(String name);
public Builder setParent(SurfaceControl parent);
public Builder setBufferSize(int width, int height);
public Builder setFormat(int format);
public Builder setOpaque(boolean opaque);
public Builder setHidden(boolean hidden);
public SurfaceControl build();
```

Recommended conceptual construction: fixed helper-controlled name, delegated parent, bounded positive buffer dimensions, fixed `PixelFormat.RGBA_8888` (`1`), and initially hidden. Decide opacity deliberately: `setOpaque(true)` asserts that alpha should be ignored; use it only when the intended desktop output is opaque. No caller-supplied format, arbitrary flags, layer stack, metadata, service name or transaction is needed.

The required layer type is a **buffer/BLAST layer**, not a color/effect or container layer. In both the inspected main source and local API-37 source, `setBufferSize` selects normal-buffer flags and `build()` converts normal-buffer type to BLAST. Therefore an explicit reflected `setBLASTLayer()` is unnecessary on this baseline. The hidden constant is `FX_SURFACE_BLAST = 0x00040000`; `HIDDEN = 0x00000004`, and the builder starts hidden. `setBLASTLayer()`, `setFlags(...)`, container/effect methods and the constructor taking `SurfaceSession` are not needed public APIs. Do not hard-code hidden flags as an OEM ABI contract. [SC-java; local API-37 source]

### Explicit queue: reflection only inside the helper

The local API-37 source contains:

```java
public BLASTBufferQueue(String name, boolean updateDestinationFrame);
public void update(SurfaceControl sc, int width, int height, int format);
public Surface createSurface();
public Surface createSurfaceWithHandle();
public void destroy();
public long getLastAcquiredFrameNum();
public boolean syncNextTransaction(boolean acquireSingleBuffer,
        java.util.function.Consumer<SurfaceControl.Transaction> callback);
public boolean syncNextTransaction(
        java.util.function.Consumer<SurfaceControl.Transaction> callback);
public void stopContinuousSyncTransaction();
public void clearSyncTransaction();
```

These signatures are also present in the inspected Android-17 release Java source. [BQ17-java]

**The whole class is `@hide`**: these are implementation-public members, not public SDK APIs. A narrow reflection adapter would resolve exactly the class, `(String.class, boolean.class)` constructor, `update(SurfaceControl.class, int.class, int.class, int.class)`, `createSurface()`, and `destroy()` in the root process only. Instantiate with `updateDestinationFrame=true`, call `update(child, width, height, RGBA_8888)` **before** exposing the Surface, then `createSurface()`. Retain queue, Surface and child throughout the attachment. [BQ-java], [BQ-jni]

Inspected main additionally provides:

```java
public BLASTBufferQueue(String name, SurfaceControl sc,
        int width, int height, int format);
```

It delegates to the boolean constructor and `update`; it is absent from the local API-37 source. Use the two-step form as the researched common signature, and fail closed if runtime resolution is denied or mismatched. UID 0 is not a documented blanket hidden-API exemption. Do not load/link hidden framework classes in the ordinary app, write global hidden-API settings, or treat reflective access failure as authorization to relax policy.

Prefer **`createSurface()`**, not `createSurfaceWithHandle()`: the former excludes the SurfaceControl handle from the producer Surface parcel. crosvm receives the producer capability required to render, without the extra compositor handle. Root display/virtualization Binders, queue/control objects and root-owned Surfaces must not be returned to the app. The reply remains success/error and bounded diagnostics.

Do not call `syncNextTransaction` unless designing an actual bounded synchronization path: it redirects a buffer transaction into the supplied callback, and an unapplied callback transaction can prevent presentation. Continuous sync adds still more lifetime state. Neither sync nor root-side drawing is required for normal automatic BLAST consumption. [BQ-java], [BQ-native]

### Child visibility, placement, crop and removal: public API-37 SDK

```java
public SurfaceControl.Transaction();
public Transaction setVisibility(SurfaceControl sc, boolean visible);
public Transaction setLayer(SurfaceControl sc, int z);
public Transaction setPosition(SurfaceControl sc, float x, float y);
public Transaction setScale(SurfaceControl sc, float sx, float sy);
public Transaction setAlpha(SurfaceControl sc, float alpha);
public Transaction setCrop(SurfaceControl sc, android.graphics.Rect crop);
public Transaction setBufferSize(SurfaceControl sc, int width, int height);
public Transaction reparent(SurfaceControl sc, SurfaceControl newParent);
public void apply();
public void close();
```

`reparent` accepts a nullable new parent. `setGeometry(SurfaceControl, Rect, Rect, int)` is also public in the local SDK, but local source deprecates it in favor of crop/position/scale/transform calls. Use a local transaction in the helper and close it after applying. Set only the owned child visible, positioned at parent-local origin with bounded crop/geometry and an intentional sibling Z value. Do not transact on the delegated parent. Parent transforms, crop, visibility and Z ordering are inherited; a visible child cannot defeat a hidden/clipped parent. [SC-java]

The local SDK does **not** publish `Transaction.show(SurfaceControl)`, `hide(SurfaceControl)`, or `remove(SurfaceControl)`. Their source implementations are hidden. Use public **`setVisibility(child, true/false)`** instead of reflected show/hide. Use **`reparent(child, null).apply()` followed by `child.release()`** instead of reflected remove. Hidden `remove` itself performs reparent-to-null and local release. `release()` alone is not screen removal: the documented layer may remain visible while its parent exists. [SC-java; local API-37 signatures]

### Public convenience Surface alternative, with important differences

The local SDK also exposes:

```java
public Surface(SurfaceControl from);
public void Surface.release();
public boolean Surface.isValid();
```

This is not equivalent to passing an existing app Surface. Inspected Java/JNI calls native `SurfaceControl::getSurface()`, whose `generateSurfaceLocked()` creates **another `[BBQ]` child and a BLASTBufferQueue in the calling process**. Constructing `new Surface(helperCreatedChild)` in the helper can therefore provide helper-hosted graphics transport without explicit BLAST reflection. Native `updateDefaultBufferSize` updates that internally owned queue, making the public transaction's buffer-size route relevant here. [Surface-java], [Surface-jni], [SC-native]

However, that path adds an internal child, returns `getSurface(true)` with its control handle, and does not expose a public explicit queue-destroy handle. Its exact native behavior is not established for the installed OEM API-37 build. The explicit-queue design gives clearer resource accounting and the handle-minimal `createSurface()` parcel. **Never mix both paths on the same target child**: otherwise two queues/children can be created and the wrong one sized or shown. This is a source-backed alternative, not a tested replacement recommendation.

## 3. Restricted attach/session contract

Keep the existing authenticated attach operation, conceptually accepting only:

```text
client session death token + SurfaceView parent SurfaceControl + width + height
```

No new arbitrary root RPC, raw Binder lookup, caller transaction, shell command or root-managed VM lifecycle is needed. An implementation would require a UI/display seam carrying the parent and dimensions; today's `DisplayBackend.attach(Surface, callback)` alone does not contain that capability. This report does not modify that seam.

Necessary safeguards:

- Authenticate the caller's full UID before deserialization or clearing identity, retain interface/transaction allowlisting, synchronous replies and `enforceNoDataAvail()`.
- Treat the supplied parent as a powerful **delegated capability**, not proof of parent ownership. Public `isValid()` checks local handle validity, not that a live SurfaceView belonging to this UI still exists. There is no public package/UID ownership attestation in these APIs. Authentication limits who may delegate; parent provenance remains the authenticated application's responsibility.
- Use the passed parent only during creation/validation and to retain/release its local wrapper. Create exactly one tracked helper child and one queue. Never change parent visibility, size, crop, layer, security or ancestry, and never remove it or siblings.
- Reject zero/negative dimensions, impose finite per-axis and total-pixel ceilings using overflow-safe arithmetic, and fix format/opacity/Z policy internally. For example, a project-selected `1..4096` per-axis ceiling plus a stricter pixel budget is a **proposed resource policy**, not an Android API guarantee. Graphics allocations multiply by bytes per pixel and queue buffering. Bounds on the request do not absolutely constrain an externally producing crosvm's own allocation choices.
- Link the session token before publishing the attachment; handle already-dead tokens and death during blocking construction/setSurface. Serialize state, check session generation on completion, and publish owned resources only after a still-current successful attach. Cleanup partially created resources on every failure.
- Keep the existing internal crosvm display Binder private. `setSurface(surface, false)` still uses the existing display protocol; ownership replacement does not change global `waitDisplayService()` into a per-VM API.
- Handle display-service death and helper death as attachment loss. No automatic broad-policy fallback. Retain the bounded disposable-helper watchdog for calls that cannot safely be interrupted.

## 4. Resize, visibility and parent lifecycle

### Fixed 1280×720 holder inside a 1600×900 view

The additional caller facts supplied for this investigation are `holder.setFixedSize(1280, 720)`, a device view of 1600×900, attach from `surfaceCreated` using `holder.surface`, and a no-op `surfaceChanged`. No additional caller-code inspection was performed.

**Use 1280×720 as the initial root queue's buffer and child-local extent, not 1600×900. An identity child transform normally suffices in this layout.** Android-17 `SurfaceView.getSurfaceControl()` returns `mSurfaceControl`, a **container**; the view's own rendering layer `mBlastSurfaceControl` is a child of that container. A helper child under the delegated container is a sibling of that rendering layer, not a child of the app's BLAST queue. [SV17-java, lines 1819–1840 and 2366–2368]

Crucially, SurfaceView applies the fixed-buffer-to-screen **position and matrix to the container itself**:

- Non-hardware-accelerated path uses `screenRect.width / mSurfaceWidth` and the corresponding height ratio.
- Render-thread paths use the reported display rectangle divided by the fixed surface dimensions, and call `onSetSurfacePositionAndScale(..., mSurfaceControl, ...)`.
- That method writes the parent's position and `setMatrix(postScaleX, 0, 0, postScaleY)`.
- The ordinary clipping path crops the container to the fixed surface extent. The render-thread clipping path explicitly converts view-relative clip coordinates into **buffer-relative** coordinates before cropping the same container. [SV17-java, lines 1344–1371, 1895–1900 and 1972–2058]

Consequently, for the supplied unobstructed rectangle:

```text
parent-local extent:        1280 × 720
inherited parent scale:     1600/1280 = 1.25, 900/720 = 1.25
root child transform:       position (0,0), scale (1,1)
root child/queue extent:    1280 × 720
composed screen extent:     1600 × 900
```

Do **not** also scale the child by `1.25`: that would apply the fixed-buffer scale twice and can clip content. Do not put screen-space 1600×900 crop coordinates into this parent-local hierarchy. An optional owned-child crop is `Rect(0, 0, 1280, 720)`; the inherited parent crop still controls visibility. The parent remains read-only to the helper. This conclusion is conditional on the inspected release implementation and the actual parent/layout matching the supplied facts; additional view transforms/clipping can alter the final displayed rectangle.

The view's own BLAST rendering child has its destination frame set separately, and SurfaceView constructs its queue with `updateDestinationFrame=false`. Those **sibling-only** settings do not automatically configure the helper child. The explicit helper queue with `true` sets its own destination extent to the chosen fixed-buffer dimensions, allowing the inherited container transform to do the view scaling. No hidden app-side rendering-control lookup, parent mutation or global display transform is needed. [SV17-java, lines 1359–1360 and 1868–1869], [BQ-native]

If intentionally producing a different buffer size, its child-local destination must still fit the parent's 1280×720 coordinate system. For example, a 1600×900 child-local destination would need helper-child scale `0.8, 0.8` to fit, before the inherited parent `1.25` scale. This is a deliberate alternative, not a reason to pass the device view size into queue creation blindly. Keep queue-owned destination-frame updates and helper-owned geometry consistent; do not assume resizing the queue changes the parent's fixed-size coordinate space.

A larger/smaller on-screen view with the same fixed holder size can be handled by SurfaceView's inherited parent transform without a queue resize. If the **holder's fixed dimensions change**, forward the new buffer dimensions through the bounded current-session path or reattach; the existing no-op `surfaceChanged` would otherwise leave helper defaults stale. A callback dimension is a buffer-size fact, not the composed view's pixel size. Parent replacement/destruction still requires explicit detach/new attach even when dimensions remain 1280×720.

### Queue resize and lifecycle

**Explicit BLAST resize:** call the same queue's `update(child, newWidth, newHeight, fixedFormat)` after authenticated, bounded session validation; retain the producer Surface. Native update changes default buffer size, transform hint, backpressure and destination-frame behavior. Public `Transaction.setBufferSize(child, ...)` is **not a substitute for explicit queue update**: it updates the control's internally created queue, if any, whereas this design's separate Java BLAST queue is not that internal queue. [BQ-native], [SC-native]

Queue defaults do not force a producer using explicit dimensions to start rendering differently. BLAST's freeze-mode rejection checks requested/current buffer dimensions; incompatible guest buffers can be rejected. Host viewport resize, guest display-mode resize and scaling are different operations. Keep existing guest/input ownership outside root; choose either bounded scaling/crop for a fixed guest buffer or a separately validated guest-resolution flow. Do not claim `update()` negotiates a new guest mode. [BQ-native]

With `updateDestinationFrame=true`, BLAST may update the child's destination frame on incoming buffers. Avoid a competing geometry writer on that same buffer layer; use parent-inherited positioning and carefully chosen child crop/scale, or explicitly design who owns geometry. Coordinate crop and queue changes to avoid clipping a new-size frame with old bounds. A basic source-feasible design does not guarantee seamless resize.

If no separate resize operation is approved, dimensions can change through a new authenticated attach generation that tears down/recreates the owned child/queue. That is bounded and simpler but may cause visible discontinuity. A future resize RPC must carry the current session and contain only bounded dimensions, never arbitrary transactions or controls.

SurfaceView retains responsibility for its own Surface and any internally managed layers. Do not connect the guest to the holder Surface or draw black placeholder buffers over the helper child. Choose child sibling Z intentionally so the SurfaceView's own content does not obscure it; do not try to solve an invisible child by modifying the parent. Normal view/window visibility, alpha, clipping and SurfaceView Z-order behavior still affect composition. [SV-java], [SC-java]

**Parent death is not token death.** A session Binder normally dies with its process, not on every `surfaceDestroyed`, detach, activity recreation or parent replacement. SurfaceControl layer handles are compositor capabilities, not per-SurfaceView app-owned Binder death endpoints; linking to a compositor handle is not a reliable parent-lifecycle monitor. Likewise, `isValid()` does not query whether the layer remains attached/on-screen. [SC-java], [SC-native], [SV-java]

The application must explicitly detach on SurfaceView destruction/replacement, VM stop and UI disposal, and supply a fresh parent with a fresh generation when recreated. App-process death triggers the existing death token. Size/visibility changes need UI-driven updates; retain no stale parent merely because its wrapper is valid. A timeout/lease could be considered separately for missed detach, but is not an existing guaranteed parent-death API. Do not reparent to a display root or unrelated surviving layer to keep content alive after its UI parent disappears.

## 5. Safe cleanup and failure rollback

A recommended **best-effort ordered teardown**, for explicit BLAST, is:

1. Under serialized session ownership, mark the session closing and stop admitting new attach/resize work. Unlink the saved client death recipient without allowing a stale callback to affect a newer generation.
2. Invoke existing `removeSurface(false)` on the current crosvm display Binder to stop the producer. Bound this operation using the existing helper watchdog. Detach failure must not skip local cleanup.
3. While the owned child control is usable, apply a local transaction making **only that child** invisible and `reparent(child, null)`. Close the transaction. Detaching before releasing handles avoids leaving a last buffer visibly attached to the parent's tree.
4. Release the helper's producer `Surface` wrapper.
5. Invoke `BLASTBufferQueue.destroy()` **exactly once**, clear the queue reference, and never call queue methods afterward. If custom sync/hang callbacks were introduced, cancel/clear them while the queue is still alive; the minimal design should not create them.
6. Release the helper-created child control wrapper, then release the helper's deserialized **parent handle only**. Clear all remaining session resources, then unbind/stop the normal short-lived service.

Every stage must be attempted in `finally`/rollback even if an earlier stage fails. Never call `remove`, `reparent(..., null)` or visibility/crop setters on the delegated parent. Never release the app's own parent Java object from the app side as part of bridge cleanup. [SC-java], [BQ-jni], [BQ-native]

Important limits: Java queue `destroy()` releases a JNI-held strong reference, not a synchronous guarantee that all native callbacks have disappeared. `BBQSurface` also holds a strong queue reference; transaction completion callbacks can retain it, and the native destructor can apply pending transactions. Hence detach/hide first, release Surface before dropping the queue reference, keep child resources valid until queue teardown is initiated, make callbacks generation-aware, and do not depend on finalizers or immediate native destruction. SurfaceFlinger transactions apply asynchronously; a returned `apply()` is not proof that the next displayed frame is blank. Abrupt watchdog/helper death remains a separate best-effort cleanup case. [BQ-jni], [BQ-native], [Surface-jni], [SC-java]

## 6. Meaning of `transaction credentials failed`

In the inspected Android common kernel, `binder_transaction()` resolves the destination process, then checks:

```c
if (security_binder_transaction(proc->cred, target_proc->cred) < 0) {
    binder_txn_error("%d:%d transaction credentials failed\n",
                    thread->pid, proc->pid);
    return_error = BR_FAILED_REPLY;
    return_error_param = -EPERM;
    ...
}
```

`security_binder_transaction()` dispatches the LSM Binder-transaction hook. The inspected SELinux implementation checks `binder impersonate` if the current task SID differs from the saved sender credential SID, then checks **`binder call` from sender SID to receiver SID**. Binder captures its process credentials from the Binder file's `f_cred` at open. This is kernel security authorization, not an Android permission exception, queue-format negotiation or hidden-API access check. UID equality between app and crosvm does not imply SELinux SID equality. Clearing Java Binder calling identity does not change the process/domain owning an already-created graphics Binder. [Binder], [Security], [SELinux]

The first diagnostic prints **thread:process**, so `24257:24225` means crosvm thread 24257 in process 24225, not helper PID 24257. The summary diagnostic prints **process:thread**, yielding `24225:24257`, and identifies target process 10589; destination thread `0` does not mean destination UID 0. `/-1` is consistent with the driver normalizing this failure to `-EPERM`; it does not uniquely identify which LSM permission failed. [Binder]

Therefore the supplied lines establish a rejected kernel Binder transaction toward the app. SELinux `call` is a strong source-consistent explanation given the supplied domains, but **no specific absent AVC, missing allow rule, impersonation failure or OEM hook result can be asserted from those lines alone**. Audit suppression/rate limiting and OEM changes also prevent treating an absent AVC as proof of no SELinux decision. The captured line number is revision-specific. Do not decode code `41` into a named graphics method using the older inspected mainline enum: transaction numbering can differ on API 37/OEM graphics interfaces. [IGBP]

Binder-object transfer and FD transfer have separate `security_binder_transfer_binder` and `security_binder_transfer_file` gates. SELinux FD handling checks receiver access to the passed file, including `fd use` where applicable. A successful helper-to-crosvm `setSurface` call does not establish the later crosvm-to-helper producer-call or all buffer/fence transport permissions. [Binder], [SELinux]

## 7. Validation gates before any implementation claim

This is a scoped next design, not permission/policy escalation:

1. Resolve only the necessary hidden queue signatures inside the disposable helper, or deliberately select and separately validate the public convenience path. If unavailable, fail closed.
2. Establish child creation under the delegated live parent and helper-hosted queue identity; successful `setSurface` alone is insufficient.
3. Verify first buffer acquisition, producer configuration, and actual visible guest frames separately. A queue frame counter can diagnose acquisition, but is not proof of final composition/presentation.
4. Exercise resize, SurfaceView hide/destroy/recreate, stale detach, app/client death, display death, helper death, attach rollback and detach failure; ensure only owned children disappear and resources do not leak.
5. Preserve ordinary VM lifecycle/vsock/input, the authenticated RPC boundary, no privileged Binder return, global SELinux Enforcing, and existing settings. Record any remaining Binder/FD/compositor failure before deciding whether the design is compatible with the installed stack.

No permissive mode, shared-domain policy grant, global hidden-API relaxation, persistent module, system installation or arbitrary privileged RPC is proposed. The existing KernelSU root domain is not claimed to be least-privileged; this project would add no speculative policy widening.

## Primary-source references

Each source link names an inspected file; append `?format=TEXT` to obtain the exact downloaded representation. Mainline links intentionally pin the older baseline, not Android-17 runtime equivalence.

- [BQ17-java]: [Android-17 release BLASTBufferQueue.java](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/graphics/java/android/graphics/BLASTBufferQueue.java): hidden two-argument constructor, update/create/destroy and synchronization signatures; agrees with local API-37 source.
- [BQ17-jni]: [Android-17 android_graphics_BLASTBufferQueue.cpp](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/jni/android_graphics_BLASTBufferQueue.cpp#171): local native construction, strong-reference teardown and Surface creation.
- [SV17-java]: [Android-17 SurfaceView.java](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/view/SurfaceView.java): container/rendering-child separation, fixed-size parent scaling/crop, public parent capability and lifetime contract. Particularly [parent matrix](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/view/SurfaceView.java#1895), [render-thread scaling/crop](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/view/SurfaceView.java#1972), and [getSurfaceControl](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/view/SurfaceView.java#2350).
- [BQ-java]: [frameworks/base BLASTBufferQueue.java](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/graphics/java/android/graphics/BLASTBufferQueue.java): constructors, class `@hide`, update/create/destroy/sync APIs.
- [BQ-jni]: [android_graphics_BLASTBufferQueue.cpp](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/jni/android_graphics_BLASTBufferQueue.cpp): `nativeCreate`, `nativeDestroy`, `nativeGetSurface`, `nativeUpdate`.
- [BQ-native]: [frameworks/native BLASTBufferQueue.cpp](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/BLASTBufferQueue.cpp): constructor/destructor, update, acquisition, rejectBuffer, BBQSurface, getSurface, listener wrappers, createBufferQueue. [Header](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/include/gui/BLASTBufferQueue.h).
- [BQP-header]: [BufferQueueProducer.h](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/include/gui/BufferQueueProducer.h): Binder server inheritance. [BufferQueueConsumer.cpp](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/BufferQueueConsumer.cpp): positive default buffer dimensions.
- [SC-java]: [SurfaceControl.java](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/SurfaceControl.java): Builder flags/type conversion, parceling, release, visibility, resizing, reparent/remove. API-37 signature differences were checked directly against local SDK sources and public stubs.
- [SV-java]: [SurfaceView.java](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/SurfaceView.java): parent-capability contract and view-managed surface lifecycle. [Official public SurfaceView reference](https://developer.android.com/reference/android/view/SurfaceView#getSurfaceControl()).
- [SC-native]: [native SurfaceControl.cpp](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/SurfaceControl.cpp): generateSurfaceLocked/getSurface, internally owned BLAST queue, buffer resize, compositor handles and parceling.
- [Surface-java]: [Surface.java](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/view/Surface.java): SurfaceControl constructor/copyFrom and lifetime; constructor also confirmed in API-37 public stubs.
- [Surface-jni]: [android_view_Surface.cpp](https://android.googlesource.com/platform/frameworks/base/+/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/jni/android_view_Surface.cpp): nativeGetFromSurfaceControl, nativeWriteToParcel, nativeRelease.
- [IGBP]: [IGraphicBufferProducer.cpp](https://android.googlesource.com/platform/frameworks/native/+/4f463a6b1de9198963dc6aff74154a504ba3f8f6/libs/gui/IGraphicBufferProducer.cpp): version-specific native transaction enum and proxy calls.
- [Binder]: [kernel/common drivers/android/binder.c](https://android.googlesource.com/kernel/common/+/3f843d67013171808264166f9288257b3c316b52/drivers/android/binder.c): transaction gate around lines 3466–3474; summary diagnostics around 4121; captured file credentials around 6373; separate object/FD-transfer gates.
- [Security]: [kernel/common security/security.c](https://android.googlesource.com/kernel/common/+/3f843d67013171808264166f9288257b3c316b52/security/security.c#1012): LSM dispatch for Binder transaction authorization.
- [SELinux]: [kernel/common security/selinux/hooks.c](https://android.googlesource.com/kernel/common/+/3f843d67013171808264166f9288257b3c316b52/security/selinux/hooks.c#2045): Binder impersonate/call, transfer and FD checks.

Local primary SDK evidence: `C:/Users/i/AppData/Local/Android/Sdk/platforms/android-37.0/android.jar`; `sources/android-37.0/android/graphics/BLASTBufferQueue.java`, `android/view/SurfaceControl.java`, `android/view/Surface.java`; and both packages' `source.properties`. Public signatures were checked with `javap`, not inferred from a Java member's `public` modifier. No builds/tests or device verification were run because this task is research only.

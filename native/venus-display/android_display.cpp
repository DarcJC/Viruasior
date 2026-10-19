// Android 17 crosvm display ABI, implemented using NDK Binder and native windows.
// Based on the documented FFI and AIDL in AOSP android17-release; no system file writes.
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_aidl.h>
#include <android/native_window.h>
#include <android/native_window_aidl.h>
#include <android/log.h>
#include <android/surface_control.h>
#include <linux/dma-buf.h>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <vector>
#include <poll.h>
#include <atomic>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdint>
#include <chrono>
#include <dlfcn.h>
#include "dmabuf_frame_policy.h"
#include "dmabuf_sync.h"
#include "scanout_buffer.h"

extern "C" uid_t AIBinder_getCallingUid();
#include "ahb_release.h"
using ErrorCallback = void (*)(const char *);
static constexpr const char *DISPLAY = "android.crosvm.ICrosvmAndroidDisplayService";
static constexpr const char *INTERNAL = "android.system.virtualizationservice_internal.IVirtualizationServiceInternal";
static constexpr const char *FRAME_SINK = "pro.darc.viruaisor.display.IRootFrameSink";


static void *frameSinkOnCreate(void *arg) { return arg; }
static void frameSinkOnDestroy(void *) {}
static binder_status_t frameSinkOnTransact(
    AIBinder *, transaction_code_t, const AParcel *, AParcel *) {
    return STATUS_UNKNOWN_TRANSACTION;
}
static const AIBinder_Class *frameSinkClass() {
    static AIBinder_Class *frame_sink_class = AIBinder_Class_define(
        FRAME_SINK, frameSinkOnCreate, frameSinkOnDestroy, frameSinkOnTransact);
    return frame_sink_class;
}

struct AndroidDisplaySurface {
    std::mutex mutex;
    ANativeWindow *window = nullptr;
    ASurfaceControl *control = nullptr;
    AIBinder *frameSink = nullptr;
    FrameReleasePool frameReleases;
    uint32_t width = 0, height = 0;
    ScanoutBuffer frame;
    bool writing=false;
    bool isCursor=false;
    uint64_t frames=0;
    uint64_t frameSinkFailures=0;
    uint64_t cpuCopies=0;
    uint64_t deferredFrames=0;
    uint64_t consumerBacklog=0;
    std::chrono::steady_clock::time_point lastBacklogLog{};
    void backlog() {
        ++consumerBacklog;
        const auto now=std::chrono::steady_clock::now();
        if (now-lastBacklogLog>=std::chrono::seconds(1)) {
            lastBacklogLog=now;
            __android_log_print(ANDROID_LOG_INFO,"ViruaisorVenusDisplay",
                "DISPLAY_CONSUMER_BACKLOG count=%llu",(unsigned long long)consumerBacklog);
        }
    }
    AHardwareBuffer *retainedBuffer=nullptr;
    std::chrono::steady_clock::time_point lastPostLog{};
    bool logPost() {
        const auto now=std::chrono::steady_clock::now();
        if (frames!=1 && frames%60!=0 && now-lastPostLog<std::chrono::seconds(1)) return false;
        lastPostLog=now;
        return true;
    }
    ~AndroidDisplaySurface() { detach(); }
    void releaseControlLocked() {
        if (!control) return;
        auto *transaction = ASurfaceTransaction_create();
        if (transaction) {
            ASurfaceTransaction_setVisibility(
                transaction, control, ASURFACE_TRANSACTION_VISIBILITY_HIDE);
            ASurfaceTransaction_reparent(transaction, control, nullptr);
            ASurfaceTransaction_apply(transaction);
            ASurfaceTransaction_delete(transaction);
        }
        ASurfaceControl_release(control);
        control = nullptr;
    }
    void detach() {
        std::lock_guard guard(mutex);
        releaseControlLocked();
        frameReleases.reset();
        if (frameSink) AIBinder_decStrong(frameSink);
        frameSink = nullptr;
        if (window) ANativeWindow_release(window);
        window = nullptr;
        if (retainedBuffer) AHardwareBuffer_release(retainedBuffer);
        retainedBuffer=nullptr;
    }
    binder_status_t ensureControlLocked() {
        if (!window || !width || !height) return STATUS_OK;
        auto result = ANativeWindow_setBuffersGeometry(
            window, width, height, WINDOW_FORMAT_RGBA_8888);
        if (result != STATUS_OK) return result;
        if (frameSink || isCursor) return STATUS_OK;
        if (!control) {
            control = ASurfaceControl_createFromWindow(
                window, "Viruaisor Venus scanout");
            if (!control) return STATUS_BAD_VALUE;
        }
        return STATUS_OK;
    }
    bool usesRootProducer() {
        std::lock_guard guard(mutex);
        return frameSink != nullptr;
    }
    binder_status_t attach(ANativeWindow *value, AIBinder *sink) {
        std::lock_guard guard(mutex);
        releaseControlLocked();
        frameReleases.reset();
        if (frameSink) AIBinder_decStrong(frameSink);
        frameSink = sink;
        if (window) ANativeWindow_release(window);
        window = value;
        auto result = ensureControlLocked();
        if (result != STATUS_OK) return result;
        if (window && width && height && retainedBuffer)
            return presentHardwareBufferLocked(retainedBuffer);
        if (window && width && height && frame.available() && !writing)
            return presentLocked();
        return STATUS_OK;
    }
    int presentHardwareBufferLocked(AHardwareBuffer *hardware_buffer, int dma_fd=-1) {
        if (!hardware_buffer || !width || !height) {
            __android_log_print(
                ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                "SURFACE_BUFFER_REJECTED state window=%d control=%d sink=%d buffer=%d width=%u height=%u",
                window != nullptr, control != nullptr, frameSink != nullptr,
                hardware_buffer != nullptr, width, height);
            return STATUS_BAD_VALUE;
        }
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(hardware_buffer, &description);
        const uint64_t display_usage =
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
            AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (description.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
            description.width != width || description.height != height ||
            description.layers != 1 ||
            (description.usage & display_usage) != display_usage) {
            __android_log_print(
                ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                "SURFACE_BUFFER_REJECTED descriptor width=%u height=%u layers=%u format=%u usage=%llx expected=%ux%u",
                description.width, description.height, description.layers,
                description.format,
                static_cast<unsigned long long>(description.usage), width, height);
            return STATUS_BAD_VALUE;
        }
        // A detached UI has no consumer. Retain the qualified original AHB for
        // attach replay; do not turn an ordinary detach into a CPU copy.
        if (!frameSink && (!window || !control)) return STATUS_OK;
        if (frameSink) {
            AParcel *input = nullptr, *output = nullptr;
            auto status = AIBinder_prepareTransaction(frameSink, &input);
            const auto release = status == STATUS_OK
                ? frameReleases.acquire(hardware_buffer, dma_fd) : FrameRelease{};
            if (status == STATUS_OK && !release) {
                AParcel_delete(input);
                backlog();
                return -EAGAIN; // Bounded consumer backlog: retain the last visible frame.
            }
            if (status == STATUS_OK) status = AParcel_writeStrongBinder(input, release.binder);
            if (status == STATUS_OK) status = AParcel_writeInt64(input, release.ticket);
            if (status == STATUS_OK)
                status = AHardwareBuffer_writeToParcel(hardware_buffer, input);
            bool dispatched=false;
            if (status == STATUS_OK) {
                dispatched=true;
                status = AIBinder_transact(frameSink, 2, &input, &output, 0);
            }
            // A failed transaction can already have reached the consumer.
            // Only locally unsent frames can be cancelled without its fence.
            if (!dispatched) frameReleases.cancelUnsent(release);
            AStatus *header = nullptr;
            const auto header_status = status == STATUS_OK && output
                ? AParcel_readStatusHeader(output, &header) : STATUS_BAD_VALUE;
            const bool okay = status == STATUS_OK && output &&
                header_status == STATUS_OK && AStatus_isOk(header);
            if (!okay && header && (frameSinkFailures < 2 || frameSinkFailures % 60 == 0))
                __android_log_print(ANDROID_LOG_WARN,"ViruaisorVenusDisplay","FRAME_SINK_ERROR exception=%d message=%s",
                    AStatus_getExceptionCode(header),AStatus_getMessage(header));
            if (header) AStatus_delete(header);
            if (input) AParcel_delete(input);
            if (output) AParcel_delete(output);
            if (!okay) {
                ++frameSinkFailures;
                if (frameSinkFailures == 1 || frameSinkFailures % 60 == 0)
                    __android_log_print(
                        ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                        "FRAME_SINK_FAILED count=%llu status=%d header=%d output=%d",
                        static_cast<unsigned long long>(frameSinkFailures), status,
                        header_status, output != nullptr);
                return status == STATUS_OK ? STATUS_BAD_VALUE : status;
            }
            ++frames;
            if (logPost())
                __android_log_print(
                    ANDROID_LOG_INFO, "ViruaisorVenusDisplay",
                    "FRAME_POSTED count=%llu path=root-surface-control cpuCopies=%llu width=%u height=%u",
                    static_cast<unsigned long long>(frames), static_cast<unsigned long long>(cpuCopies), width, height);
            return STATUS_OK;
        }
        auto *transaction = ASurfaceTransaction_create();
        if (!transaction) return STATUS_NO_MEMORY;
        const ARect source{0, 0, static_cast<int32_t>(width),
                           static_cast<int32_t>(height)};
        const ARect destination = source;
        // Renderer synchronization completes the producing Vulkan queues before
        // this call, so no additional acquire fence is needed.
        auto *release = ReleaseBuffer::create(hardware_buffer, dma_fd);
        if (!release) { ASurfaceTransaction_delete(transaction); backlog(); return -EAGAIN; }
        ASurfaceTransaction_setBufferWithRelease(transaction, control, hardware_buffer, -1,
                                                release, releaseNativeBuffer);
        ASurfaceTransaction_setBufferTransparency(transaction, control,
                                                  ASURFACE_TRANSACTION_TRANSPARENCY_OPAQUE);
        ASurfaceTransaction_setGeometry(
            transaction, control, source, destination, 0);
        ASurfaceTransaction_setVisibility(
            transaction, control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_apply(transaction);
        ASurfaceTransaction_delete(transaction);
        ++frames;
        if (logPost())
            __android_log_print(
                ANDROID_LOG_INFO, "ViruaisorVenusDisplay",
                "FRAME_POSTED count=%llu path=surface-control cpuCopies=%llu width=%u height=%u",
                static_cast<unsigned long long>(frames), static_cast<unsigned long long>(cpuCopies), width, height);
        return STATUS_OK;
    }
    int presentLocked() {
        if (!window || !frame.available()) return 0; // Retain pixels while the UI is absent.
        ++cpuCopies;
        ANativeWindow_Buffer buffer{};
        int result=ANativeWindow_lock(window,&buffer,nullptr);
        if (result) return result;
        bool valid=buffer.format==WINDOW_FORMAT_RGBA_8888 && buffer.width==(int32_t)width &&
            buffer.height==(int32_t)height && buffer.stride>0 && frame.copyRgba(buffer.bits,(size_t)buffer.stride*4);
        result=ANativeWindow_unlockAndPost(window);
        if (!valid) return STATUS_BAD_VALUE;
        if (!result && (++frames==1 || frames%60==0))
            __android_log_print(ANDROID_LOG_INFO,"ViruaisorVenusDisplay","FRAME_POSTED count=%llu",(unsigned long long)frames);
        return result;
    }
};

struct AndroidDisplayContext {
    ErrorCallback error;
    AndroidDisplaySurface scanout, cursor;
    AIBinder *service = nullptr, *virtualization = nullptr;
    std::mutex cursorMutex;
    int cursorFd = -1;
    uint32_t cursorX=0x80000000, cursorY=0x80000000;
    bool registered = false;
    bool retired = false;
    std::mutex serviceMutex;
    void report(const char *message) {
        __android_log_print(ANDROID_LOG_ERROR, "ViruaisorVenusDisplay", "%s", message);
        if (error) error(message);
    }
};

static void *onCreate(void *arg) { return arg; }
static void onDestroy(void *arg) { delete static_cast<AndroidDisplayContext *>(arg); }
static binder_status_t statusReply(AParcel *reply, binder_status_t result) {
    AStatus *status = result == STATUS_OK ? AStatus_newOk() : AStatus_fromStatus(result);
    auto written = AParcel_writeStatusHeader(reply, status);
    AStatus_delete(status);
    return written;
}
static binder_status_t transact(AIBinder *binder, transaction_code_t code, const AParcel *input, AParcel *output) {
    if (AIBinder_getCallingUid() != 0) return STATUS_PERMISSION_DENIED;
    auto *ctx = static_cast<AndroidDisplayContext *>(AIBinder_getUserData(binder));
    std::lock_guard serviceGuard(ctx->serviceMutex);
    if (ctx->retired) return STATUS_DEAD_OBJECT;
    binder_status_t result;
    bool cursor = false;
    if (code == 1) {
        int32_t present = 0;
        if ((result=AParcel_readInt32(input, &present)) != STATUS_OK || present != 1) return STATUS_BAD_VALUE;
        ANativeWindow *window = nullptr;
        if ((result=ANativeWindow_readFromParcel(input, &window)) != STATUS_OK) return result;
        if ((result=AParcel_readBool(input, &cursor)) != STATUS_OK) { ANativeWindow_release(window); return result; }
        AIBinder *frame_sink = nullptr;
        if (AParcel_getDataPosition(input) < AParcel_getDataSize(input) &&
            (result=AParcel_readStrongBinder(input, &frame_sink)) != STATUS_OK) {
            ANativeWindow_release(window);
            return result;
        }
        auto *frame_sink_class = frameSinkClass();
        if (frame_sink && !AIBinder_associateClass(frame_sink, frame_sink_class)) {
            AIBinder_decStrong(frame_sink);
            ANativeWindow_release(window);
            return STATUS_BAD_TYPE;
        }
        result=(cursor ? ctx->cursor : ctx->scanout).attach(window, frame_sink);
        if (result != STATUS_OK) ctx->report("Native window attachment failed");
        else __android_log_print(ANDROID_LOG_INFO, "ViruaisorVenusDisplay", "SURFACE_ATTACHED cursor=%d frame_sink=%d", cursor, frame_sink != nullptr);
        return statusReply(output, result);
    }
    if (code == 2) {
        int fd = -1;
        // AParcel already consumes the nullable Parcelable presence word.
        if (AParcel_readParcelFileDescriptor(input,&fd) != STATUS_OK) return STATUS_BAD_VALUE;
        if (fd>=0) {
            int flags=fcntl(fd,F_GETFL);
            if (flags<0 || fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0) { close(fd);return STATUS_BAD_VALUE; }
        }
        std::lock_guard guard(ctx->cursorMutex);
        if (ctx->cursorFd >= 0) close(ctx->cursorFd);
        ctx->cursorFd=fd;
        if (fd>=0) { uint32_t position[]={ctx->cursorX,ctx->cursorY}; (void)send(fd,position,sizeof(position),MSG_DONTWAIT|MSG_NOSIGNAL); }
        return statusReply(output,STATUS_OK);
    }
    if (code == 3) {
        if ((result=AParcel_readBool(input,&cursor)) != STATUS_OK) return result;
        (cursor ? ctx->cursor : ctx->scanout).detach();
        return statusReply(output,STATUS_OK);
    }
    return STATUS_UNKNOWN_TRANSACTION;
}

static bool registerDisplay(AndroidDisplayContext *ctx, bool remove) {
    AParcel *input=nullptr, *output=nullptr;
    if (AIBinder_prepareTransaction(ctx->virtualization,&input) != STATUS_OK) return false;
    auto status = remove ? STATUS_OK : AParcel_writeStrongBinder(input,ctx->service);
    if (status == STATUS_OK) status=AIBinder_transact(ctx->virtualization,remove ? 17 : 16,&input,&output,0);
    AStatus *header=nullptr;
    bool okay=status == STATUS_OK && AParcel_readStatusHeader(output,&header) == STATUS_OK && AStatus_isOk(header);
    if (header) AStatus_delete(header);
    if (input) AParcel_delete(input);
    if (output) AParcel_delete(output);
    return okay;
}

extern "C" AndroidDisplayContext *create_android_display_context(const char *, ErrorCallback error) {
    auto *ctx=new AndroidDisplayContext{}; ctx->error=error;
    // ServiceManager and process-pool entry points exist in the installed platform library,
    // but are absent from the NDK link stubs. Use the existing namespace, without changing it.
    void *library=dlopen("libbinder_ndk.so",RTLD_NOW|RTLD_NOLOAD);
    auto wait=reinterpret_cast<AIBinder *(*)(const char *)>(library ? dlsym(library,"AServiceManager_waitForService") : nullptr);
    auto pool=reinterpret_cast<void (*)()>(library ? dlsym(library,"ABinderProcess_startThreadPool") : nullptr);
    if (!wait || !pool) { ctx->report("Platform Binder entry points unavailable"); delete ctx; return nullptr; }
    auto *internal=AIBinder_Class_define(INTERNAL,onCreate,onDestroy,transact);
    ctx->virtualization=wait("android.system.virtualizationservice");
    if (!ctx->virtualization || !AIBinder_associateClass(ctx->virtualization,internal)) {
        ctx->report("Cannot acquire AVF internal display registration service");
        if (ctx->virtualization) AIBinder_decStrong(ctx->virtualization);
        delete ctx; return nullptr;
    }
    auto *klass=AIBinder_Class_define(DISPLAY,onCreate,onDestroy,transact);
    ctx->service=AIBinder_new(klass,ctx);
    pool();
    ctx->registered=registerDisplay(ctx,false);
    if (!ctx->registered) {
        ctx->report("AVF display registration refused");
        AIBinder_decStrong(ctx->virtualization);
        AIBinder_decStrong(ctx->service); return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO,"ViruaisorVenusDisplay","DISPLAY_REGISTERED uid=%d",getuid());
    return ctx;
}
extern "C" void destroy_android_display_context(AndroidDisplayContext *ctx) {
    if (!ctx) return;
    if (ctx->registered && !registerDisplay(ctx,true)) ctx->report("AVF display removal refused");
    {
        std::lock_guard guard(ctx->serviceMutex);
        ctx->retired=true;
        ctx->scanout.detach(); ctx->cursor.detach();
        AIBinder_decStrong(ctx->virtualization); ctx->virtualization=nullptr;
        if (ctx->cursorFd >= 0) close(ctx->cursorFd);
        ctx->cursorFd=-1;
    }
    // Remote Binder references may outlive the FFI context. onDestroy frees it only after
    // those references and any in-flight callback are gone.
    AIBinder_decStrong(ctx->service);
}
extern "C" AndroidDisplaySurface *create_android_surface(AndroidDisplayContext *ctx,uint32_t width,uint32_t height,bool cursor) {
    if (!ctx || !width || !height || width>4096 || height>4096) return nullptr;
    auto &surface=cursor ? ctx->cursor : ctx->scanout;
    std::lock_guard guard(surface.mutex);
    surface.isCursor=cursor;
    if (!surface.frame.configure(width,height)) return nullptr;
    if ((surface.width!=width || surface.height!=height) && surface.retainedBuffer) {
        AHardwareBuffer_release(surface.retainedBuffer); surface.retainedBuffer=nullptr;
    }
    surface.width=width; surface.height=height;
    if (surface.ensureControlLocked() != STATUS_OK) return nullptr;
    return &surface;
}
extern "C" void destroy_android_surface(AndroidDisplayContext *,AndroidDisplaySurface *surface) {
    if (!surface) return;
    // A guest modeset destroys scanout resources, not the Android UI attachment.
    std::lock_guard guard(surface->mutex);
    surface->writing=false;
    if (surface->isCursor && surface->frame.data()) {
        // Resource 0 hides the cursor. Preserve the UI attachment but clear the
        // old glyph, so a later position update cannot resurrect stale pixels.
        memset(surface->frame.data(),0,(size_t)surface->width*surface->height*4);
        surface->frame.finish();
        (void)surface->presentLocked();
    }
}
extern "C" uint32_t get_android_surface_buffer(AndroidDisplayContext *,AndroidDisplaySurface *surface,ANativeWindow_Buffer *buffer) {
    if (!surface || !buffer) return 0;
    std::lock_guard guard(surface->mutex);
    if (!surface->frame.data()) return 0;
    *buffer={}; buffer->width=surface->width; buffer->height=surface->height;
    buffer->stride=surface->width; buffer->format=5; // Crosvm copy scanout uses BGRA/BGRX.
    buffer->bits=surface->frame.data(); surface->writing=true;
    return 1;
}
extern "C" void post_android_surface_buffer(AndroidDisplayContext *ctx,AndroidDisplaySurface *surface) {
    if (!surface) return;
    std::lock_guard guard(surface->mutex);
    surface->writing=false; surface->frame.finish();
    if (surface->presentLocked()) ctx->report("Native window format/conversion/post failed");
}
extern "C" void set_android_surface_position(AndroidDisplayContext *ctx,uint32_t x,uint32_t y) {
    std::lock_guard guard(ctx->cursorMutex);
    ctx->cursorX=x;ctx->cursorY=y;
    if (ctx->cursorFd >= 0) { uint32_t position[]={x,y}; (void)send(ctx->cursorFd,position,sizeof(position),MSG_DONTWAIT|MSG_NOSIGNAL); }
}
struct AHardwareBufferInfo { size_t num_fds; const int32_t *fds_ptr; size_t metadata_len; const uint8_t *metadata_ptr; };
extern "C" void android_display_flip_to(AndroidDisplayContext *ctx,AndroidDisplaySurface *,const AHardwareBufferInfo *) {
    ctx->report("Unqualified AHardwareBuffer scanout rejected; use the qualified copy scanout path");
}

struct DmabufFrameInfo {
    int32_t fd;
    uint32_t offset, stride, width, height, fourcc;
};

struct FrameSample {
    uint64_t colored = 0;
    uint64_t opaque = 0;
    uint64_t hash = UINT64_C(1469598103934665603);
};

static FrameSample sampleFrame(const uint8_t *source, uint32_t stride,
                               uint32_t width, uint32_t height) {
    FrameSample sample;
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t *row = source + static_cast<size_t>(y) * stride;
        for (uint32_t x = 0; x < width; ++x) {
            const uint8_t *pixel = row + static_cast<size_t>(x) * 4;
            sample.colored += (pixel[0] | pixel[1] | pixel[2]) != 0;
            sample.opaque += pixel[3] != 0;
            for (size_t channel = 0; channel < 4; ++channel) {
                sample.hash ^= pixel[channel];
                sample.hash *= UINT64_C(1099511628211);
            }
        }
    }
    return sample;
}

extern "C" void *viruaisor_ahb_acquire_from_fd(int fd);

static int dmabufIoctl(int fd, unsigned long request, void *argument) {
    return ioctl(fd, request, argument);
}

static int presentSourceLocked(AndroidDisplaySurface *surface,
                               const DmabufFrameInfo *info,
                               const uint8_t *source, const char *path) {
    std::lock_guard guard(surface->mutex);
    if (surface->frames < 60 || surface->frames % 60 == 0) {
        const auto sample = sampleFrame(source, info->stride,
                                        surface->width, surface->height);
        __android_log_print(
            ANDROID_LOG_INFO, "ViruaisorVenusDisplay",
            "FRAME_SOURCE frame=%llu path=%s fd=%d offset=%u stride=%u fourcc=%08x colored=%llu opaque=%llu hash=%016llx",
            static_cast<unsigned long long>(surface->frames + 1), path,
            info->fd, info->offset, info->stride, info->fourcc,
            static_cast<unsigned long long>(sample.colored),
            static_cast<unsigned long long>(sample.opaque),
            static_cast<unsigned long long>(sample.hash));
    }
    const bool copied = venus_copy_bgra(
        surface->frame.data(), static_cast<size_t>(surface->width) * 4,
        source, info->stride, surface->width, surface->height);
    surface->frame.finish();
    return copied ? surface->presentLocked() : STATUS_BAD_VALUE;
}

extern "C" int32_t android_display_flip_to_dmabuf(
    AndroidDisplayContext *ctx, AndroidDisplaySurface *surface,
    const DmabufFrameInfo *info) {
    if (!ctx || !surface || !info || info->fd < 0) {
        if (ctx) {
            __android_log_print(ANDROID_LOG_ERROR,"ViruaisorVenusDisplay",
                "DMA-BUF scanout metadata rejected actual=%ux%u expected=%ux%u",
                info ? info->width : 0, info ? info->height : 0,
                surface ? surface->width : 0, surface ? surface->height : 0);
            ctx->report("DMA-BUF scanout metadata rejected");
        }
        return 0;
    }
    uint64_t mapping_bytes = 0;
    if (!venus_dmabuf_frame_bytes(info->offset, info->stride, info->width,
                                  info->height, info->fourcc,
                                  &mapping_bytes)) {
        ctx->report("DMA-BUF scanout geometry rejected");
        return 0;
    }
    struct stat status{};
    if (fstat(info->fd, &status) || status.st_size < 0 ||
        static_cast<uint64_t>(status.st_size) < mapping_bytes) {
        ctx->report("DMA-BUF scanout size rejected");
        return 0;
    }
    if (info->width != surface->width || info->height != surface->height) {
        // XRandR grows the framebuffer before updating the scanout rectangle.
        // Keep the prior frame during that valid intermediate state.
        if (++surface->deferredFrames==1 || surface->deferredFrames%60==0)
            __android_log_print(ANDROID_LOG_INFO,"ViruaisorVenusDisplay",
                "DISPLAY_DEFERRED reason=mode-transition width=%u height=%u scanout=%ux%u",
                info->width,info->height,surface->width,surface->height);
        return 1;
    }

    // The Mali gralloc mapper owns cache and layout handling for this exact AHB.
    // The renderer has already completed a bounded Vulkan fence before flip.
    auto *hardware_buffer = static_cast<AHardwareBuffer *>(
        viruaisor_ahb_acquire_from_fd(info->fd));
    if (hardware_buffer) {
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(hardware_buffer, &description);
        const bool rgba_image =
            description.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM &&
            description.width == info->width &&
            description.height == info->height && description.layers == 1 &&
            description.stride >= description.width;
        if (rgba_image && info->offset == 0) {
            int surface_result = STATUS_BAD_VALUE;
            {
                std::lock_guard guard(surface->mutex);
                surface_result = surface->presentHardwareBufferLocked(hardware_buffer, info->fd);
                if (surface_result == STATUS_OK) {
                    AHardwareBuffer_acquire(hardware_buffer);
                    if (surface->retainedBuffer) AHardwareBuffer_release(surface->retainedBuffer);
                    surface->retainedBuffer=hardware_buffer;
                }
            }
            if (surface_result == STATUS_OK) {
                AHardwareBuffer_release(hardware_buffer);
                return 1;
            }
            if (surface_result == -EAGAIN) {
                AHardwareBuffer_release(hardware_buffer);
                return 1;
            }
            __android_log_print(ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                                "DIRECT_SCANOUT_FALLBACK status=%d", surface_result);
        }
        void *mapping = nullptr;
        const bool blob = description.format == AHARDWAREBUFFER_FORMAT_BLOB &&
            description.height == 1 && description.layers == 1 &&
            description.width >= mapping_bytes;
        const bool qualified = blob || rgba_image;
        const int lock_result = qualified ? AHardwareBuffer_lock(
            hardware_buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1,
            nullptr, &mapping) : STATUS_BAD_VALUE;
        int result = STATUS_BAD_VALUE;
        if (lock_result == 0 && mapping) {
            DmabufFrameInfo mapped_info = *info;
            if (rgba_image) {
                mapped_info.offset = 0;
                mapped_info.stride = description.stride * 4;
            }
            const auto *source = static_cast<const uint8_t *>(mapping) +
                mapped_info.offset;
            result = presentSourceLocked(surface, &mapped_info, source,
                                         rgba_image ? "ahb-image-lock"
                                                    : "ahb-lock");
        }
        int release_fence = -1;
        const int unlock_result = lock_result == 0 ?
            AHardwareBuffer_unlock(hardware_buffer, &release_fence) : lock_result;
        if (release_fence >= 0) close(release_fence);
        AHardwareBuffer_release(hardware_buffer);
        if (result == 0 && unlock_result == 0) return 1;
        __android_log_print(ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                            "AHardwareBuffer mapper path unavailable");
    }

    // Keep the generic DMA-BUF protocol as a qualified fallback for exporters
    // whose AHardwareBuffer cannot be recovered by the app-private renderer.
    void *mapping = mmap(nullptr, static_cast<size_t>(mapping_bytes), PROT_READ,
                         MAP_SHARED, info->fd, 0);
    if (mapping != MAP_FAILED) {
        if (venus_dmabuf_begin_cpu_read(info->fd, dmabufIoctl)) {
            const auto *source = static_cast<const uint8_t *>(mapping) + info->offset;
            int result = presentSourceLocked(surface, info, source, "dmabuf-sync-mmap");
            if (!venus_dmabuf_end_cpu_read(info->fd, dmabufIoctl)) result = STATUS_BAD_VALUE;
            munmap(mapping, static_cast<size_t>(mapping_bytes));
            if (!result) return 1;
            ctx->report("DMA-BUF synchronized scanout copy/post failed");
            return 0;
        }
        munmap(mapping, static_cast<size_t>(mapping_bytes));
        __android_log_print(ANDROID_LOG_WARN, "ViruaisorVenusDisplay",
                            "DMA-BUF CPU synchronization unavailable");
    }
    ctx->report("DMA-BUF scanout mapping failed");
    return 0;
}

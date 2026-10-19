#pragma once
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <unistd.h>
#include <condition_variable>
#include <array>
#include <mutex>
#include <thread>
#include <vector>
#include "ahb_release_budget.h"
#include "release_ticket.h"
static AhbReleaseBudget displayReleaseBudget;

/* Keep allocation and consumer lifetime distinct. The release sync_file is
 * also published as an implicit reader dependency for the DMA-BUF exporter. */
struct ReleaseBuffer {
    AHardwareBuffer *buffer;
    int dmaFd;
    int fence=-1;
    ReleaseBuffer(AHardwareBuffer *value,int fd):buffer(value),dmaFd(fd<0?-1:dup(fd)) {
        AHardwareBuffer_acquire(buffer);
    }
    ~ReleaseBuffer() {
        if (fence>=0) close(fence);
        if (dmaFd>=0) close(dmaFd);
        AHardwareBuffer_release(buffer);
        displayReleaseBudget.release();
    }
    static ReleaseBuffer *create(AHardwareBuffer *buffer,int fd) {
        return displayReleaseBudget.take() ? new ReleaseBuffer(buffer,fd) : nullptr;
    }
};
class BufferReleases {
    std::mutex mutex;
    std::condition_variable changed;
    std::vector<ReleaseBuffer *> waiting;
    bool stopped=false;
    std::thread worker;
    void run() {
        std::unique_lock guard(mutex);
        while (!stopped) {
            if (waiting.empty()) changed.wait(guard,[this]{return stopped || !waiting.empty();});
            else changed.wait_for(guard,std::chrono::milliseconds(8));
            for (auto i=waiting.begin();i!=waiting.end();) {
                pollfd fd{(*i)->fence,POLLIN,0};
                if ((*i)->fence<0 || (poll(&fd,1,0)>0 && (fd.revents&POLLIN))) {
                    delete *i; i=waiting.erase(i);
                } else ++i;
            }
        }
        for (auto *value:waiting) delete value;
    }
public:
    BufferReleases():worker([this]{run();}) {}
    ~BufferReleases() {
        { std::lock_guard guard(mutex); stopped=true; changed.notify_one(); }
        worker.join();
    }
    void add(ReleaseBuffer *value,int fence) {
        value->fence=fence;
        if (fence>=0 && value->dmaFd>=0) {
            dma_buf_import_sync_file sync{DMA_BUF_SYNC_READ,fence};
            if (ioctl(value->dmaFd,DMA_BUF_IOCTL_IMPORT_SYNC_FILE,&sync))
                __android_log_print(ANDROID_LOG_WARN,"ViruaisorVenusDisplay",
                                    "DISPLAY_RELEASE_IMPORT_UNAVAILABLE errno=%d",errno);
        }
        std::lock_guard guard(mutex);
        waiting.push_back(value); changed.notify_one();
    }
    static BufferReleases &instance() { static BufferReleases values; return values; }
};
static void releaseNativeBuffer(void *opaque,int fence) {
    BufferReleases::instance().add(static_cast<ReleaseBuffer *>(opaque),fence);
}
struct ReleaseBinder { ReleaseTicket<ReleaseBuffer> slot; };
static void *releaseOnCreate(void *p) { return p; }
static void releaseOnDestroy(void *p) {
    delete static_cast<ReleaseBinder *>(p);
}
static binder_status_t releaseOnTransact(AIBinder *binder,transaction_code_t code,
                                         const AParcel *input,AParcel *output) {
    if (AIBinder_getCallingUid()!=0) return STATUS_PERMISSION_DENIED;
    if (code!=2) return STATUS_UNKNOWN_TRANSACTION;
    auto *value=static_cast<ReleaseBinder *>(AIBinder_getUserData(binder));
    int64_t ticket=0;
    bool hasFence=false;
    int fence=-1;
    if (AParcel_readInt64(input,&ticket)!=STATUS_OK ||
        AParcel_readBool(input,&hasFence)!=STATUS_OK ||
        (hasFence && AParcel_readParcelFileDescriptor(input,&fence)!=STATUS_OK)) {
        if (fence>=0) close(fence);
        return STATUS_BAD_VALUE;
    }
    if (hasFence && fence<0) return STATUS_BAD_VALUE;
    auto buffer=value->slot.take(ticket);
    if (!buffer) { if (fence>=0) close(fence); return STATUS_BAD_VALUE; }
    releaseNativeBuffer(buffer.release(),fence);
    auto *status=AStatus_newOk();
    auto result=AParcel_writeStatusHeader(output,status);
    AStatus_delete(status); return result;
}
struct FrameRelease {
    AIBinder *binder=nullptr; // borrowed while the owning display lock is held
    int64_t ticket=0;
    explicit operator bool() const { return binder && ticket>0; }
};
class FrameReleasePool {
    std::array<AIBinder *,AhbReleaseBudget::limit> binders{};
public:
    FrameReleasePool()=default;
    FrameReleasePool(const FrameReleasePool &)=delete;
    FrameReleasePool &operator=(const FrameReleasePool &)=delete;
    ~FrameReleasePool() { reset(); }
    void reset() {
        // Callback closures keep their own Binder reference until release. Do
        // not discard their pending payload when the display detaches.
        for (auto &binder:binders) {
            if (binder) AIBinder_decStrong(binder);
            binder=nullptr;
        }
    }
    FrameRelease acquire(AHardwareBuffer *buffer,int fd) {
        static auto *klass=AIBinder_Class_define("pro.darc.viruaisor.display.IBufferRelease",
            releaseOnCreate,releaseOnDestroy,releaseOnTransact);
        if (!klass) return {};
        for (auto &binder:binders) {
            if (!binder) {
                auto *value=new ReleaseBinder;
                binder=AIBinder_new(klass,value);
                if (!binder) { delete value; return {}; }
            }
            auto *value=static_cast<ReleaseBinder *>(AIBinder_getUserData(binder));
            const auto ticket=value->slot.assign([&] {
                return std::unique_ptr<ReleaseBuffer>(ReleaseBuffer::create(buffer,fd));
            });
            if (ticket) return {binder,ticket};
        }
        return {};
    }
    void cancelUnsent(const FrameRelease &frame) {
        if (!frame) return;
        auto *value=static_cast<ReleaseBinder *>(AIBinder_getUserData(frame.binder));
        value->slot.take(frame.ticket); // never use after a transaction was issued
    }
};

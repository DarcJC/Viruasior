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
#include <mutex>
#include <thread>
#include <vector>
#include "ahb_release_budget.h"
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
struct ReleaseBinder { ReleaseBuffer *buffer; std::mutex mutex; };
static void *releaseOnCreate(void *p) { return p; }
static void releaseOnDestroy(void *p) {
    auto *value=static_cast<ReleaseBinder *>(p);
    delete value->buffer; delete value;
}
static binder_status_t releaseOnTransact(AIBinder *binder,transaction_code_t code,
                                         const AParcel *input,AParcel *output) {
    if (AIBinder_getCallingUid()!=0) return STATUS_PERMISSION_DENIED;
    if (code!=1) return STATUS_UNKNOWN_TRANSACTION;
    auto *value=static_cast<ReleaseBinder *>(AIBinder_getUserData(binder));
    std::lock_guard guard(value->mutex);
    bool hasFence=false;
    int fence=-1;
    if (AParcel_readBool(input,&hasFence)!=STATUS_OK ||
        (hasFence && AParcel_readParcelFileDescriptor(input,&fence)!=STATUS_OK))
        return STATUS_BAD_VALUE;
    if (!value->buffer) { if (fence>=0) close(fence); return STATUS_BAD_VALUE; }
    releaseNativeBuffer(value->buffer,fence); value->buffer=nullptr;
    auto *status=AStatus_newOk();
    auto result=AParcel_writeStatusHeader(output,status);
    AStatus_delete(status); return result;
}
static AIBinder *makeReleaseBinder(AHardwareBuffer *buffer,int fd) {
    static auto *klass=AIBinder_Class_define("pro.darc.viruaisor.display.IBufferRelease",
        releaseOnCreate,releaseOnDestroy,releaseOnTransact);
    auto *owned=ReleaseBuffer::create(buffer,fd);
    return owned ? AIBinder_new(klass,new ReleaseBinder{owned,{}}) : nullptr;
}

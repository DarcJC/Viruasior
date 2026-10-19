// Device-only ownership/NDK wire regression. Uses private scratch buffers and
// pipe readiness to test deferred cleanup; no display or system policy changes.
#include <android/binder_ibinder.h>
#include <android/hardware_buffer.h>
#include <cassert>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <dirent.h>
extern "C" uid_t AIBinder_getCallingUid();
#include "ahb_release.h"

static binder_status_t send(const FrameRelease &frame,int fd=-1) {
    AParcel *input=nullptr,*output=nullptr;
    assert(AIBinder_prepareTransaction(frame.binder,&input)==STATUS_OK);
    assert(AParcel_writeInt64(input,frame.ticket)==STATUS_OK);
    assert(AParcel_writeBool(input,fd>=0)==STATUS_OK);
    if (fd>=0) assert(AParcel_writeParcelFileDescriptor(input,fd)==STATUS_OK);
    const auto result=AIBinder_transact(frame.binder,2,&input,&output,0);
    if (result==STATUS_OK) {
        AStatus *status=nullptr;
        assert(AParcel_readStatusHeader(output,&status)==STATUS_OK);
        assert(AStatus_isOk(status));
        AStatus_delete(status);
    }
    if (input) AParcel_delete(input);
    if (output) AParcel_delete(output);
    return result;
}
static unsigned available() {
    unsigned count=0;
    while (displayReleaseBudget.take()) ++count;
    for (unsigned n=0;n<count;++n) displayReleaseBudget.release();
    return count;
}
static void waitAvailable(unsigned expected) {
    for (unsigned i=0;i<2000 && available()!=expected;++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(available()==expected);
}
static unsigned fdCount() {
    auto *directory=opendir("/proc/self/fd");
    assert(directory);
    unsigned count=0;
    while (auto *entry=readdir(directory)) if (entry->d_name[0]!='.') ++count;
    closedir(directory);
    return count;
}
int main(int argc,char **argv) {
    const bool denied=argc==2 && !strcmp(argv[1],"--expect-denied");
    assert(argc==1 || denied);
    AHardwareBuffer_Desc desc{};
    desc.width=64; desc.height=64; desc.layers=1;
    desc.format=AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage=AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    AHardwareBuffer *buffer=nullptr;
    assert(AHardwareBuffer_allocate(&desc,&buffer)==0 && buffer);
    FrameReleasePool pool;
    auto first=pool.acquire(buffer,-1);
    assert(first);
    if (denied) {
        assert(getuid()!=0);
        assert(send(first)==STATUS_PERMISSION_DENIED);
        assert(available()==AhbReleaseBudget::limit-1);
        pool.cancelUnsent(first);
        AHardwareBuffer_release(buffer);
        puts("PASS unauthorized release rejected; payload remains owned");
        return 0;
    }
    assert(getuid()==0);
    std::array<FrameRelease,AhbReleaseBudget::limit-1> rest;
    for (auto &frame:rest) { frame=pool.acquire(buffer,-1); assert(frame); }
    assert(!pool.acquire(buffer,-1) && available()==0);
    int pipeFd[2]; assert(pipe(pipeFd)==0);
    assert(send(first,pipeFd[0])==STATUS_OK);
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    assert(!pool.acquire(buffer,-1) && available()==0);
    assert(write(pipeFd[1],"x",1)==1);
    waitAvailable(1);
    auto second=pool.acquire(buffer,-1);
    assert(second && second.binder==first.binder && second.ticket>first.ticket);
    const auto before=fdCount();
    for (unsigned i=0;i<32;++i) assert(send(first,pipeFd[0])==STATUS_BAD_VALUE);
    assert(fdCount()==before && available()==0); // stale release closes its duplicate FD
    assert(send(second)==STATUS_OK);
    for (auto frame:rest) assert(send(frame)==STATUS_OK);
    waitAvailable(AhbReleaseBudget::limit);
    close(pipeFd[0]); close(pipeFd[1]);
    auto detached=pool.acquire(buffer,-1);
    assert(detached);
    AIBinder_incStrong(detached.binder); // the consumer callback's reference
    pool.reset();
    assert(available()==AhbReleaseBudget::limit-1);
    assert(send(detached)==STATUS_OK);
    AIBinder_decStrong(detached.binder);
    waitAvailable(AhbReleaseBudget::limit);
    auto unsent=pool.acquire(buffer,-1);
    assert(unsent);
    pool.cancelUnsent(unsent);
    assert(available()==AhbReleaseBudget::limit);
    AHardwareBuffer_release(buffer);
    puts("PASS bounded reuse, stale/duplicate rejection, delayed fence, FD cleanup and detach ownership");
}

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/dma-heap.h>
#include <linux/dma-buf.h>
#include <linux/kvm.h>
#include <sched.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include "guest.h"

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s failed: %s\n", #c, strerror(errno)); return 1; } } while (0)
#define CORE(name) (KVM_REG_ARM64 | KVM_REG_SIZE_U64 | KVM_REG_ARM_CORE | KVM_REG_ARM_CORE_REG(name))
static int setreg(int fd, uint64_t id, uint64_t value) {
    struct kvm_one_reg r = {.id=id, .addr=(uintptr_t)&value};
    return ioctl(fd, KVM_SET_ONE_REG, &r);
}

static void report_mapping_flags(const void *mapping) {
    FILE *smaps = fopen("/proc/self/smaps", "r");
    if (!smaps) return;
    char line[512];
    int selected = 0;
    while (fgets(line, sizeof(line), smaps)) {
        uintptr_t first, last;
        if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR, &first, &last) == 2) {
            selected = first <= (uintptr_t)mapping && (uintptr_t)mapping < last;
            if (selected) printf("KVM_HOST_VMA=%s", line);
        } else if (selected && !strncmp(line, "VmFlags:", 8)) {
            printf("KVM_HOST_VMA_%s", line);
            break;
        }
    }
    fclose(smaps);
}

int main(int argc, char **argv) {
    const int dma_buf_sync = argc == 2 && !strcmp(argv[1], "--dma-buf-sync");
    const int cached_heap = argc == 2 && !strcmp(argv[1], "--cached-host-clean");
    const int host_clean = argc == 2 && (!strcmp(argv[1], "--host-clean") || dma_buf_sync || cached_heap);
    CHECK(argc == 1 || host_clean);
    setbuf(stdout, NULL);
    alarm(8); // Bounds execution even if guest setup faults or loops.
    CHECK(sysconf(_SC_PAGESIZE) == 4096);
    int host_clean_cpu = -1, guest_cpu = -1;
    if (host_clean) {
        cpu_set_t allowed, single;
        CHECK(sched_getaffinity(0, sizeof(allowed), &allowed) == 0);
        int first = -1;
        for (int i = 0; i < CPU_SETSIZE; ++i) {
            if (CPU_ISSET(i, &allowed)) {
                if (first < 0) first = i;
                host_clean_cpu = i;
            }
        }
        CHECK(first >= 0 && host_clean_cpu > first);
        guest_cpu = first;
        CPU_ZERO(&single);
        CPU_SET(first, &single);
        CHECK(sched_setaffinity(0, sizeof(single), &single) == 0);
        printf("KVM_GUEST_CPU=%d HOST_CLEAN_CPU=%d\n", first, host_clean_cpu);
    }
    int kvm = open("/dev/kvm", O_RDWR | O_CLOEXEC);
    CHECK(kvm >= 0);
    CHECK(ioctl(kvm, KVM_GET_API_VERSION, 0) == 12);
    const char *heap_name = cached_heap ? "/dev/dma_heap/system" : "/dev/dma_heap/system-uncached";
    int heap = open(heap_name, O_RDONLY | O_CLOEXEC);
    CHECK(heap >= 0);
    printf("KVM_HEAP=%s\n", heap_name);
    struct dma_heap_allocation_data allocation = {.len=65536, .fd_flags=O_RDWR | O_CLOEXEC};
    CHECK(ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &allocation) == 0);
    close(heap);
    void *data = mmap(NULL, 65536, PROT_READ|PROT_WRITE, MAP_SHARED, allocation.fd, 0);
    CHECK(data != MAP_FAILED);
    report_mapping_flags(data);
    volatile uint32_t *word = data;
    *word = 7;
    __asm__ volatile("dsb sy" ::: "memory");
    void *code = mmap(NULL, 65536, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS, -1, 0);
    CHECK(code != MAP_FAILED);
    CHECK(kvm_memory_guest_len < 4096);
    memcpy(code, kvm_memory_guest, kvm_memory_guest_len);
    uint64_t *table = (uint64_t *)((char *)code + 4096);
    // AF=1, inner shareable, EL1 RW. Attr0=WB, Attr1=Normal Non-cacheable.
    table[1] = UINT64_C(0x40000000) | 0x701;
    table[2] = UINT64_C(0x80000000) | 0x705;
    __builtin___clear_cache(code, (char *)code + 4096);
    int vm = ioctl(kvm, KVM_CREATE_VM, 0);
    CHECK(vm >= 0);
    struct kvm_userspace_memory_region slot = {.slot=0, .guest_phys_addr=0x40000000, .memory_size=65536, .userspace_addr=(uintptr_t)code};
    CHECK(ioctl(vm, KVM_SET_USER_MEMORY_REGION, &slot) == 0);
    slot.slot=1; slot.guest_phys_addr=0x80000000; slot.userspace_addr=(uintptr_t)data;
    CHECK(ioctl(vm, KVM_SET_USER_MEMORY_REGION, &slot) == 0);
    int cpu = ioctl(vm, KVM_CREATE_VCPU, 0);
    CHECK(cpu >= 0);
    struct kvm_vcpu_init init = {0};
    CHECK(ioctl(vm, KVM_ARM_PREFERRED_TARGET, &init) == 0);
    CHECK(ioctl(cpu, KVM_ARM_VCPU_INIT, &init) == 0);
    CHECK(setreg(cpu, CORE(regs.pc), 0x40000000) == 0);
    CHECK(setreg(cpu, CORE(regs.pstate), 0x3c5) == 0);
    CHECK(setreg(cpu, CORE(regs.regs[0]), host_clean ? 1 : 0) == 0);
    int run_size = ioctl(kvm, KVM_GET_VCPU_MMAP_SIZE, 0);
    CHECK(run_size >= (int)sizeof(struct kvm_run));
    struct kvm_run *run = mmap(NULL, (size_t)run_size, PROT_READ|PROT_WRITE, MAP_SHARED, cpu, 0);
    CHECK(run != MAP_FAILED);
    unsigned tested = 0;
    int coherent = 0, mmu_checked = 0, attributes_checked = 0, host_clean_works = 0;
    int host_invalidate_works = 0;
    unsigned roundtrip_reads = 0;
    for (unsigned exits=0; exits<8 && (tested<2 || (host_clean && roundtrip_reads<2)); ++exits) {
        CHECK(ioctl(cpu, KVM_RUN, 0) == 0);
        printf("KVM_EXIT=%u\n", run->exit_reason);
        CHECK(run->exit_reason == KVM_EXIT_MMIO && run->mmio.is_write);
        uint64_t value = 0;
        CHECK(run->mmio.len <= sizeof(value));
        memcpy(&value, run->mmio.data, run->mmio.len);
        printf("MMIO address=%" PRIx64 " value=%" PRIx64 "\n", (uint64_t)run->mmio.phys_addr, value);
        if (run->mmio.phys_addr == 0x90000010) {
            CHECK((value & 5) == 5); // SCTLR_EL1.M and .C enabled.
            mmu_checked = 1;
        } else if (run->mmio.phys_addr == 0x90000018) {
            CHECK(!(value & 1) && (value >> 56) == 0x44); // S1 translation: Normal NC.
            attributes_checked = 1;
        } else if (host_clean && (run->mmio.phys_addr == 0x90000020 ||
                                  run->mmio.phys_addr == 0x90000028)) {
            CHECK(mmu_checked && attributes_checked && tested == 1);
            CHECK(run->mmio.phys_addr == UINT64_C(0x90000020) + roundtrip_reads * 8);
            printf("KVM_HOST_TO_GUEST_%s guest=%08" PRIx64 "\n",
                   roundtrip_reads ? "AFTER_CIVAC" : "NO_CIVAC", value);
            if (!roundtrip_reads) {
                cpu_set_t single;
                CPU_ZERO(&single);
                CPU_SET(host_clean_cpu, &single);
                CHECK(sched_setaffinity(0, sizeof(single), &single) == 0);
                if (dma_buf_sync) {
                    struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_RW};
                    CHECK(ioctl(allocation.fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
                } else {
                    __asm__ volatile("dc civac, %0; dsb sy" :: "r"(data) : "memory");
                }
                CPU_ZERO(&single);
                CPU_SET(guest_cpu, &single);
                CHECK(sched_setaffinity(0, sizeof(single), &single) == 0);
            } else {
                host_invalidate_works = value == 0x89abcdef;
            }
            ++roundtrip_reads;
        }
        if (run->mmio.phys_addr == 0x90000000 || run->mmio.phys_addr == 0x90000008) {
            CHECK(mmu_checked && attributes_checked);
            CHECK(run->mmio.phys_addr == UINT64_C(0x90000000) + tested * 8);
            __asm__ volatile("dsb sy" ::: "memory");
            uint32_t observed = *word;
            printf("KVM_UNCACHED_%s guest=%08" PRIx64 " host=%08x equal=%d\n", tested ? "AFTER_CIVAC" : "NO_FLUSH", value, observed, observed == value);
            CHECK(value == 0x12345678);
            if (!tested) {
                coherent = observed == value;
                if (host_clean) {
                    // Test whether a host-side VA cache clean reaches the guest's
                    // dirty cache line. This is diagnostic, not Vulkan coherence.
                    cpu_set_t single;
                    CPU_ZERO(&single);
                    CPU_SET(host_clean_cpu, &single);
                    CHECK(sched_setaffinity(0, sizeof(single), &single) == 0);
                    printf("KVM_ACTUAL_HOST_CLEAN_CPU=%d\n", sched_getcpu());
                    if (dma_buf_sync) {
                        struct dma_buf_sync sync = {.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_RW};
                        CHECK(ioctl(allocation.fd, DMA_BUF_IOCTL_SYNC, &sync) == 0);
                    } else {
                        __asm__ volatile("dc cvac, %0; dsb sy" :: "r"(data) : "memory");
                    }
                    observed = *word;
                    host_clean_works = observed == value;
                    printf("KVM_UNCACHED_AFTER_HOST_CVAC host=%08x equal=%d\n", observed, host_clean_works);
                    *word = 0x89abcdef;
                    __asm__ volatile("dsb sy" ::: "memory");
                    CPU_ZERO(&single);
                    CPU_SET(guest_cpu, &single);
                    CHECK(sched_setaffinity(0, sizeof(single), &single) == 0);
                }
            }
            if (tested) CHECK(observed == value);
            ++tested;
        }
    }
    CHECK(tested == 2 && (!host_clean || roundtrip_reads == 2));
    munmap(run, (size_t)run_size);
    close(cpu); close(vm); close(kvm);
    munmap(code, 65536); munmap(data, 65536); close(allocation.fd);
    puts("KVM_MEMORY_TEST_DONE");
    puts(coherent ? "KVM_MEMORY_RESULT=PASS" : "KVM_MEMORY_RESULT=FAIL_NO_COHERENCE");
    if (host_clean) {
        puts(host_clean_works ? "KVM_HOST_CLEAN_RESULT=VISIBLE" : "KVM_HOST_CLEAN_RESULT=STALE");
        puts(host_invalidate_works ? "KVM_HOST_INVALIDATE_RESULT=VISIBLE" : "KVM_HOST_INVALIDATE_RESULT=STALE");
    }
    return coherent || (host_clean && host_clean_works && host_invalidate_works) ? 0 : 1;
}

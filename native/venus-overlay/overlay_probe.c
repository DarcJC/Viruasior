#define _GNU_SOURCE
#include "overlay_policy.h"
#include "overlay_copy.h"
#include "overlay_mount_policy.h"
#include "overlay_wait.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

/* No caller-selected command, process, directory, source, or mount target. */
#define STOCK_CROSVM "/apex/com.android.virt/bin/crosvm"
#define STOCK_VIRTMGR "/apex/com.android.virt/bin/virtmgr"
static int read_small(const char *path, char *buf, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, size - 1);
    int saved = errno;
    close(fd);
    errno = saved;
    if (n < 0 || (size_t)n == size - 1) return -1;
    buf[n] = 0;
    return (int)n;
}
static uint64_t ns_inode(pid_t pid) {
    char path[80]; struct stat st;
    snprintf(path, sizeof(path), "/proc/%d/ns/mnt", pid);
    return stat(path, &st) ? 0 : st.st_ino;
}
static bool read_command(pid_t pid, char *buf, size_t size) {
    char path[80]; snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, buf, size - 1); close(fd);
    return n > 0 && memchr(buf, 0, (size_t)n) != NULL;
}
static bool credentials(pid_t pid, unsigned expected, uint64_t *caps) {
    char path[80], buf[8192]; unsigned u[4];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    if (read_small(path, buf, sizeof(buf)) < 0) return false;
    char *line = strstr(buf, "Uid:");
    if (!line || sscanf(line, "Uid: %u %u %u %u", &u[0], &u[1], &u[2], &u[3]) != 4) return false;
    for (int i = 0; i < 4; ++i) if (u[i] != expected) return false;
    line = strstr(buf, "CapEff:");
    return line && sscanf(line, "CapEff: %" SCNx64, caps) == 1;
}
static unsigned long long start_time(pid_t pid, pid_t *parent) {
    char path[80], buf[4096];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    if (read_small(path, buf, sizeof(buf)) < 0) return 0;
    char *end = strrchr(buf, ')');
    if (!end) return 0;
    char *save = NULL, *field = strtok_r(end + 2, " ", &save);
    for (int i = 3; field; ++i, field = strtok_r(NULL, " ", &save)) {
        if (i == 4 && parent) *parent = (pid_t)strtol(field, NULL, 10);
        if (i == 22) return strtoull(field, NULL, 10);
    }
    return 0;
}
static bool identity(pid_t pid, unsigned uid) {
    char path[80], buf[8192], name[128];
    unsigned u[4]; uint64_t caps = UINT64_MAX;
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    if (read_small(path, buf, sizeof(buf)) < 0) return false;
    char *line = strstr(buf, "Uid:");
    if (!line || sscanf(line, "Uid: %u %u %u %u", &u[0], &u[1], &u[2], &u[3]) != 4) return false;
    for (int i = 0; i < 4; ++i) if (u[i] != uid) return false;
    line = strstr(buf, "CapEff:");
    if (!line || sscanf(line, "CapEff: %" SCNx64, &caps) != 1) return false;
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (read_small(path, name, sizeof(name)) < 0) return false;
    return overlay_identity_valid(uid, u[0], name, caps);
}
static unsigned members(uint64_t ns) {
    DIR *dir = opendir("/proc");
    if (!dir) return UINT32_MAX;
    unsigned count = 0; struct dirent *e;
    while ((e = readdir(dir))) {
        char *end; long pid = strtol(e->d_name, &end, 10);
        if (!pid || *end) continue;
        errno = 0;
        uint64_t current = ns_inode((pid_t)pid);
        if (!current && errno != ENOENT && errno != ESRCH) { count = UINT32_MAX; break; }
        if (current == ns) ++count;
    }
    closedir(dir);
    return count;
}
static bool private_mounts(void) {
    FILE *f = fopen("/proc/self/mountinfo", "re");
    if (!f) return false;
    bool ok = true; char *line = NULL; size_t size = 0;
    while (getline(&line, &size, f) >= 0) {
        char *end = strstr(line, " - ");
        if (!end) { ok = false; break; }
        *end = 0;
        if (strstr(line, " shared:") || strstr(line, " master:")) { ok = false; break; }
    }
    free(line); fclose(f); return ok;
}
static bool file_bytes(int fd, const char *expected, unsigned owner) {
    struct stat st; char buf[128];
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_uid != owner || st.st_nlink != 1) return false;
    ssize_t n = pread(fd, buf, sizeof(buf) - 1, 0);
    if (n < 0) return false;
    buf[n] = 0;
    return !strcmp(buf, expected);
}
static uint64_t monotonic_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
struct stock_children { int virtmgr_fd, crosvm_fd; pid_t virtmgr_pid, crosvm_pid; };
static bool same_inode(const struct stat *a, const struct stat *b) {
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
}
static bool no_existing_crosvm(const struct stat *original) {
    DIR *dir = opendir("/proc");
    if (!dir) return false;
    bool ok = true; struct dirent *e;
    while ((e = readdir(dir))) {
        char *end, cmd[128], path[80]; long p = strtol(e->d_name, &end, 10);
        if (p <= 0 || *end) continue;
        struct stat executable;
        snprintf(path, sizeof(path), "/proc/%ld/exe", p);
        errno = 0;
        int status = stat(path, &executable);
        if ((!status && same_inode(&executable, original)) ||
            (status && errno != ENOENT && errno != ESRCH)) { ok = false; break; }
        if (read_command((pid_t)p, cmd, sizeof(cmd)) &&
            (!strncmp(cmd, "crosvm_", 7) || !strcmp(cmd, "crosvm") || !strcmp(cmd, STOCK_CROSVM))) { ok = false; break; }
    }
    closedir(dir); return ok;
}
static int stable_process_handle(pid_t pid, pid_t expected_parent, unsigned uid, uint64_t ns,
                                 unsigned long long expected_start, const struct stat *image, bool virtmgr) {
    int handle = (int)syscall(SYS_pidfd_open, pid, 0);
    if (handle < 0) return -1;
    char path[80], context[256]; struct stat executable; pid_t parent = 0; uint64_t caps = UINT64_MAX;
    snprintf(path, sizeof(path), "/proc/%d/exe", pid);
    if (stat(path, &executable) || !same_inode(&executable, image) || ns_inode(pid) != ns ||
        start_time(pid, &parent) != expected_start || parent != expected_parent || !credentials(pid, uid, &caps) ||
        !overlay_process_caps_valid(virtmgr, caps)) {
        close(handle); return -1;
    }
    snprintf(path, sizeof(path), "/proc/%d/attr/current", pid);
    if (read_small(path, context, sizeof(context)) < 0 || !overlay_stock_domain_valid(context, virtmgr)) { close(handle); return -1; }
    return handle;
}
/* Observe only the fixed test VM and its authenticated stock-virtmgr parent. */
static bool observe_stock(pid_t owner, unsigned uid, uint64_t ns, const struct stat *image,
                          const struct stat *virtmgr_image, struct stock_children *children) {
    DIR *dir = opendir("/proc");
    if (!dir) return false;
    bool seen = children->crosvm_fd >= 0; struct dirent *e;
    while (!seen && (e = readdir(dir))) {
        char *end, path[80], cmd[128], context[256];
        long p = strtol(e->d_name, &end, 10);
        if (p <= 1 || *end || !read_command((pid_t)p, cmd, sizeof(cmd))) continue;
        pid_t parent = 0; struct stat executable;
        uint64_t caps = UINT64_MAX;
        snprintf(path, sizeof(path), "/proc/%ld/exe", p);
        unsigned long long started = start_time((pid_t)p, &parent);
        if (stat(path, &executable) || ns_inode((pid_t)p) != ns || !started ||
            !credentials((pid_t)p, uid, &caps)) continue;
        if (parent == owner && same_inode(&executable, virtmgr_image) && children->virtmgr_fd < 0) {
            children->virtmgr_fd = stable_process_handle((pid_t)p, owner, uid, ns, started, virtmgr_image, true);
            if (children->virtmgr_fd >= 0) {
                children->virtmgr_pid = (pid_t)p;
                snprintf(path, sizeof(path), "/proc/%ld/attr/current", p);
                if (read_small(path, context, sizeof(context)) < 0) strcpy(context, "unreadable");
                printf("VIRTMGR_OBSERVED pid=%ld ppid=%d uid=%u caps=%" PRIx64 " ns=%" PRIu64 " context=%s\n", p, owner, uid, caps, ns, context);
            }
        }
        if (!same_inode(&executable, image) || !overlay_stock_identity_valid(uid, uid, cmd, caps)) continue;
        pid_t grandparent = 0; struct stat parent_exe;
        snprintf(path, sizeof(path), "/proc/%d/exe", parent);
        unsigned long long parent_start = start_time(parent, &grandparent);
        if (stat(path, &parent_exe) || !same_inode(&parent_exe, virtmgr_image) || ns_inode(parent) != ns || !parent_start || grandparent != owner) continue;
        if (children->virtmgr_fd < 0) {
            children->virtmgr_fd = stable_process_handle(parent, owner, uid, ns, parent_start, virtmgr_image, true);
            if (children->virtmgr_fd >= 0) { children->virtmgr_pid = parent; printf("VIRTMGR_OBSERVED pid=%d ppid=%d uid=%u caps=800000 ns=%" PRIu64 "\n", parent, owner, uid, ns); }
        }
        if (children->virtmgr_fd < 0 || parent != children->virtmgr_pid) continue;
        int handle = stable_process_handle((pid_t)p, parent, uid, ns, started, image, false);
        if (handle < 0) continue;
        // Recheck executable/namespace after obtaining the stable signal handle.
        snprintf(path, sizeof(path), "/proc/%ld/exe", p);
        if (stat(path, &executable) || !same_inode(&executable, image) || ns_inode((pid_t)p) != ns) { close(handle); continue; }
        snprintf(path, sizeof(path), "/proc/%ld/attr/current", p);
        if (read_small(path, context, sizeof(context)) < 0 || !overlay_stock_domain_valid(context, false)) { close(handle); continue; }
        children->crosvm_fd = handle; children->crosvm_pid = (pid_t)p; seen = true;
        printf("STOCK_EXEC_OBSERVED pid=%ld ppid=%d uid=%u caps=0 ns=%" PRIu64 " dev=%ju ino=%ju name=%s context=%s\n",
            p, parent, uid, ns, (uintmax_t)image->st_dev, (uintmax_t)image->st_ino, cmd, context);
    }
    closedir(dir); return seen;
}
static void terminate_stock_lease(int owner_fd, const struct stock_children *children) {
    // Only timeout/owner-death cleanup, with pidfds obtained from this exclusive, fixed-name lease.
    if (children->crosvm_fd >= 0) syscall(SYS_pidfd_send_signal, children->crosvm_fd, SIGKILL, NULL, 0);
    if (children->virtmgr_fd >= 0) syscall(SYS_pidfd_send_signal, children->virtmgr_fd, SIGKILL, NULL, 0);
    syscall(SYS_pidfd_send_signal, owner_fd, SIGKILL, NULL, 0);
    struct pollfd fds[3] = {{ .fd = children->crosvm_fd, .events = POLLIN }, { .fd = children->virtmgr_fd, .events = POLLIN }, { .fd = owner_fd, .events = POLLIN }};
    bool drained = overlay_wait_terminated(fds, 3, 1000);
    printf("STOCK_LEASE_TERMINATED_BY_PIDFD all_exited=%d\n", drained);
}
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (getuid() || argc < 5 || !overlay_token_valid(argv[3])) return 64;
    const char *mode = argv[4];
    bool candidate = !strcmp(mode, "venus-exec");
    bool stock = !strcmp(mode, "stock-exec") || candidate;
    if (argc != (candidate ? 10 : stock ? 7 : 5) || (!stock && strcmp(mode, "normal") && strcmp(mode, "timeout") && strcmp(mode, "client-death") && strcmp(mode, "helper-death"))) return 64;
    char *end; long parsed = strtol(argv[1], &end, 10);
    if (*end || parsed <= 1 || parsed > INT32_MAX) return 64;
    pid_t pid = (pid_t)parsed;
    unsigned long value = strtoul(argv[2], &end, 10);
    if (*end || value > UINT32_MAX) return 64;
    unsigned uid = (unsigned)value;
    int result = 1, mounted = 0, dir = -1, target = -1, snapshot = -1, nsfd = -1, pidfd = -1;
    struct stock_children children = { .virtmgr_fd = -1, .crosvm_fd = -1 };
    struct stat original_image = {0}, virtmgr_image = {0}, copied_image = {0};
    bool stock_seen = false;
    char path[256], target_name[64], control_name[64], snapshot_name[64], anchored[128];
    char original[64], overlay[64];
    snprintf(target_name, sizeof(target_name), "%s.target", argv[3]);
    snprintf(control_name, sizeof(control_name), "%s.control", argv[3]);
    snprintf(snapshot_name, sizeof(snapshot_name), "%s.snapshot", argv[3]);
    snprintf(original, sizeof(original), "ORIGINAL:%s\n", argv[3]);
    snprintf(overlay, sizeof(overlay), "OVERLAY:%s\n", argv[3]);
#define REQUIRE(condition, gate) do { if (!(condition)) { printf("REFUSED gate=%s errno=%d (%s)\n", gate, errno, strerror(errno)); goto cleanup; } } while (0)
    if (stock) {
        uint64_t source_dev = strtoull(argv[5], &end, 10);
        REQUIRE(*argv[5] && !*end, "source-device-number");
        uint64_t source_ino = strtoull(argv[6], &end, 10);
        REQUIRE(*argv[6] && !*end, "source-inode-number");
        struct statvfs fs;
        REQUIRE(!stat(STOCK_CROSVM, &original_image) && S_ISREG(original_image.st_mode) && original_image.st_uid == 0 &&
            (uint64_t)original_image.st_dev == source_dev && (uint64_t)original_image.st_ino == source_ino &&
            original_image.st_size == 13593816 && !statvfs(STOCK_CROSVM, &fs) && (fs.f_flag & ST_RDONLY), "pinned-original-artifact");
        struct stat input;
        REQUIRE(!fstat(STDIN_FILENO, &input) && (fcntl(STDIN_FILENO, F_GETFL) & O_ACCMODE) == O_RDONLY, "readonly-source-fd");
        if (candidate) {
            uint64_t candidate_dev = strtoull(argv[7], &end, 10);
            REQUIRE(*argv[7] && !*end, "candidate-device-number");
            uint64_t candidate_ino = strtoull(argv[8], &end, 10);
            REQUIRE(*argv[8] && !*end, "candidate-inode-number");
            uint64_t candidate_size = strtoull(argv[9], &end, 10);
            REQUIRE(*argv[9] && !*end && candidate_size >= 1024 * 1024 && candidate_size <= 32 * 1024 * 1024, "candidate-size-bound");
            REQUIRE(S_ISREG(input.st_mode) && (input.st_uid == 1000 || input.st_uid == 0) && input.st_nlink == 1 &&
                ((input.st_mode & 07777) == 0755 || (input.st_mode & 07777) == 0555) &&
                (uint64_t)input.st_dev == candidate_dev && (uint64_t)input.st_ino == candidate_ino &&
                (uint64_t)input.st_size == candidate_size, "pinned-installer-owned-candidate-fd");
        } else REQUIRE(same_inode(&input, &original_image), "pinned-stock-source-fd");
        REQUIRE(!stat(STOCK_VIRTMGR, &virtmgr_image) && no_existing_crosvm(&original_image), "stock-no-competing-vm");
    }
    pid_t parent = 0;
    unsigned long long start = start_time(pid, &parent);
    REQUIRE(start && identity(pid, uid), "ordinary-app-identity");
    snprintf(path, sizeof(path), "/proc/%d/cmdline", parent);
    char parent_name[128];
    REQUIRE(read_small(path, parent_name, sizeof(parent_name)) > 0 &&
        (!strcmp(parent_name, "zygote64") || !strcmp(parent_name, "zygote")), "zygote-parent");
    pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
    REQUIRE(pidfd >= 0, "pidfd");
    uint64_t target_ns = ns_inode(pid), init_ns = ns_inode(1), zygote_ns = ns_inode(parent), helper_ns = ns_inode(getpid());
    unsigned count = members(target_ns);
    printf("IDENTITY pid=%d uid=%u start=%llu target_ns=%" PRIu64 " init_ns=%" PRIu64 " zygote_ns=%" PRIu64 " helper_ns=%" PRIu64 " members=%u\n",
        pid, uid, start, target_ns, init_ns, zygote_ns, helper_ns, count);
    REQUIRE(overlay_namespace_valid(target_ns, init_ns, zygote_ns, helper_ns, count), "exclusive-app-namespace");
    snprintf(path, sizeof(path), "/proc/%d/ns/mnt", pid);
    nsfd = open(path, O_RDONLY | O_CLOEXEC);
    struct stat st;
    REQUIRE(nsfd >= 0 && !fstat(nsfd, &st) && st.st_ino == target_ns && start_time(pid, NULL) == start && identity(pid, uid), "stable-identity");
    /* Enter the EXISTING app namespace. Never unshare a helper-only namespace. */
    REQUIRE(!setns(nsfd, CLONE_NEWNS), "setns-app");
    REQUIRE(ns_inode(getpid()) == target_ns, "entered-target");
    REQUIRE(!mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) && private_mounts(), "private-propagation");
    snprintf(path, sizeof(path), "/data/user/%u/pro.darc.viruaisor/files/venus-overlay-lab", uid / 100000);
    dir = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    REQUIRE(dir >= 0 && !fstat(dir, &st) && st.st_uid == uid, "fixed-lab-directory");
    target = stock ? open(STOCK_CROSVM, O_RDONLY | O_CLOEXEC) : openat(dir, target_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (stock) REQUIRE(target >= 0 && !fstat(target, &st) && same_inode(&st, &original_image), "fixed-stock-target");
    else REQUIRE(target >= 0 && file_bytes(target, original, uid), "fixed-sentinel");
    snapshot = openat(dir, snapshot_name, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    REQUIRE(snapshot >= 0, "create-root-snapshot");
    char label[256]; ssize_t length = fgetxattr(target, "security.selinux", label, sizeof(label));
    if (stock) REQUIRE(length > 0 && !strcmp(label, "u:object_r:crosvm_exec:s0"), "original-stock-label");
    REQUIRE(length > 0 && !fsetxattr(snapshot, "security.selinux", label, (size_t)length, 0), "temporary-snapshot-label");
    REQUIRE(stock ? overlay_copy_identical(STDIN_FILENO, snapshot) : write(snapshot, overlay, strlen(overlay)) == (ssize_t)strlen(overlay), "snapshot-bytes");
    REQUIRE(!fchown(snapshot, 0, 0) && !fchmod(snapshot, stock ? 0555 : 0444) && !fsync(snapshot) && !fstat(snapshot, &copied_image), "immutable-snapshot");
    REQUIRE(S_ISREG(copied_image.st_mode) && copied_image.st_uid == 0 && copied_image.st_gid == 0 &&
        copied_image.st_nlink == 1 && (copied_image.st_mode & 07777) == (stock ? 0555 : 0444), "snapshot-no-privileged-mode");
    errno = 0;
    REQUIRE(fgetxattr(snapshot, "security.capability", NULL, 0) == -1 && errno == ENODATA, "snapshot-no-file-capabilities");
    char copied_label[256]; ssize_t copied_length = fgetxattr(snapshot, "security.selinux", copied_label, sizeof(copied_label));
    REQUIRE(copied_length == length && !memcmp(copied_label, label, (size_t)length), "snapshot-label-readback");
    REQUIRE(overlay_snapshot_readonly(&snapshot), "close-all-snapshot-writers");
    char source[80]; snprintf(source, sizeof(source), "/proc/self/fd/%d", snapshot);
    if (stock) snprintf(anchored, sizeof(anchored), "%s", STOCK_CROSVM);
    else snprintf(anchored, sizeof(anchored), "/proc/self/fd/%d/%s", dir, target_name);
    REQUIRE(!mount(source, anchored, NULL, MS_BIND, NULL), "sentinel-bind");
    mounted = 1;
    REQUIRE(!mount(NULL, anchored, NULL, overlay_remount_flags(stock), NULL), "readonly-sentinel");
    int mounted_fd = open(anchored, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    struct statvfs mounted_fs = {0}; struct stat mounted_image = {0};
    bool mounted_ok = mounted_fd >= 0 && !fstatvfs(mounted_fd, &mounted_fs) &&
        !fstat(mounted_fd, &mounted_image) && same_inode(&mounted_image, &copied_image);
    if (mounted_fd >= 0) close(mounted_fd);
    REQUIRE(mounted_ok && (mounted_fs.f_flag & ST_RDONLY) && (mounted_fs.f_flag & ST_NODEV) &&
        !(mounted_fs.f_flag & ST_NOEXEC) && !!(mounted_fs.f_flag & ST_NOSUID) == !stock, "actual-mount-profile");
    printf("MOUNT_PROFILE kind=%s flags=%lu ro=1 nodev=1 nosuid=%d noexec=0 mode=%04o filecaps=none\n",
        stock ? "stock-copy" : "sentinel", mounted_fs.f_flag, !stock, copied_image.st_mode & 07777);
    printf("MOUNT_READY token=%s ns=%" PRIu64 " kind=%s snapshot_dev=%ju snapshot_ino=%ju\n", argv[3], target_ns,
        candidate ? "venus-candidate-byte-identical" : stock ? "stock-copy-byte-identical" : "sentinel", (uintmax_t)copied_image.st_dev, (uintmax_t)copied_image.st_ino);
    if (!strcmp(mode, "helper-death")) _exit(23); /* Dedicated app's independent watchdog destroys the namespace. */
    uint64_t deadline = monotonic_ms() + (stock ? 20000 : 8000);
    for (;;) {
        if (stock && !stock_seen) stock_seen = observe_stock(pid, uid, target_ns, &copied_image, &virtmgr_image, &children);
        struct pollfd owner = { .fd = pidfd, .events = POLLIN };
        int status = poll(&owner, 1, 50);
        if (status > 0 && owner.revents) {
            printf("OWNER_DIED\n");
            if (stock) terminate_stock_lease(pidfd, &children);
            result = 0; break;
        }
        REQUIRE(status >= 0 || errno == EINTR, "pidfd-poll");
        int control = openat(dir, control_name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        REQUIRE(control >= 0, "fixed-control");
        char c = 0; ssize_t n = pread(control, &c, 1, 0); close(control);
        REQUIRE(n == 1, "read-control");
        if (c == '1' && (stock || !strcmp(mode, "normal"))) { result = 0; break; }
        if (monotonic_ms() >= deadline) {
            printf("LEASE_EXPIRED\n");
            if (stock) terminate_stock_lease(pidfd, &children);
            result = !strcmp(mode, "timeout") ? 0 : 2; break;
        }
    }
cleanup:
    if (mounted) {
        // pidfd readiness precedes final executable/mount reference reclamation.
        // Retry only transient EBUSY, never detach/lazy-unmount or another target.
        int unmounted; unsigned retries = 0;
        uint64_t drain_deadline = monotonic_ms() + 1500;
        while ((unmounted = umount2(anchored, 0)) && errno == EBUSY && monotonic_ms() < drain_deadline) {
            ++retries; struct timespec delay = {.tv_nsec = 20000000}; nanosleep(&delay, NULL);
        }
        printf("ROLLBACK_MOUNT_DRAIN retries=%u\n", retries);
        if (unmounted) { printf("ROLLBACK=FAIL errno=%d\n", errno); result = 3; }
        else {
            int restored = stock ? open(STOCK_CROSVM, O_RDONLY | O_CLOEXEC) : openat(dir, target_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
            bool ok = restored >= 0 && (stock ? (!fstat(restored, &st) && same_inode(&st, &original_image) && overlay_same_bytes(target, restored)) : file_bytes(restored, original, uid));
            if (restored >= 0) close(restored);
            printf("ROLLBACK=%s\n", ok ? "PASS" : "FAIL");
            if (!ok) result = 3;
        }
    }
    if (stock && !stock_seen) { printf("STOCK_EXEC_NOT_OBSERVED\n"); if (!result) result = 4; }
    if (children.crosvm_fd >= 0) close(children.crosvm_fd);
    if (children.virtmgr_fd >= 0) close(children.virtmgr_fd);
    if (snapshot >= 0) { close(snapshot); unlinkat(dir, snapshot_name, 0); }
    if (target >= 0) close(target);
    if (dir >= 0) close(dir);
    if (nsfd >= 0) close(nsfd);
    if (pidfd >= 0) close(pidfd);
    return result;
}

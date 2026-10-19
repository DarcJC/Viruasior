/* Ordinary-UID offline image copy. Preserves holes; never opens a live VM image for writing. */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int copy_extent(int source, int target, off_t start, off_t end) {
    static unsigned char buffer[1024*1024], zeros[1024*1024];
    while (start < end) {
        size_t size = (end-start < (off_t)sizeof(buffer)) ? (size_t)(end-start) : sizeof(buffer);
        ssize_t count = pread(source, buffer, size, start);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        if (memcmp(buffer, zeros, (size_t)count)) {
            ssize_t done=0;
            while (done<count) {
                ssize_t written=pwrite(target,buffer+done,(size_t)(count-done),start+done);
                if (written<0 && errno==EINTR) continue;
                if (written<=0) return -1;
                done+=written;
            }
        }
        start+=count;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 3 || !strcmp(argv[1],argv[2])) return 64;
    int source=open(argv[1],O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(source<0) { perror("source"); return 1; }
    struct stat before,after;
    if(fstat(source,&before) || !S_ISREG(before.st_mode)) return 1;
    int target=open(argv[2],O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW,0600);
    if(target<0) { perror("target"); close(source); return 1; }
    int result=0;
    off_t offset=0;
    while(offset<before.st_size) {
        off_t data=lseek(source,offset,SEEK_DATA);
        if(data<0 && errno==ENXIO) break;
        if(data<0 && (errno==EINVAL || errno==ENOTSUP)) { result=copy_extent(source,target,offset,before.st_size); break; }
        if(data<0) { result=-1; break; }
        off_t hole=lseek(source,data,SEEK_HOLE);
        if(hole<=data || hole>before.st_size) { result=-1; break; }
        if(copy_extent(source,target,data,hole)) { result=-1; break; }
        offset=hole;
    }
    if(fstat(source,&after) || before.st_size!=after.st_size || before.st_mtim.tv_sec!=after.st_mtim.tv_sec ||
       before.st_mtim.tv_nsec!=after.st_mtim.tv_nsec || ftruncate(target,before.st_size) || fsync(target)) result=-1;
    close(source); close(target);
    if(result) { perror("sparse image copy"); unlink(argv[2]); return 1; }
    printf("COPIED bytes=%lld source_allocated=%lld\n",(long long)before.st_size,(long long)before.st_blocks*512);
    return 0;
}

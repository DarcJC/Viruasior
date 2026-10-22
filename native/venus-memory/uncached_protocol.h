#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/un.h>

#define VENUS_UNCACHED_VERSION 1u
#define VENUS_UNCACHED_GRANULE UINT64_C(65536)
#define VENUS_UNCACHED_MAX_ALLOCATION (UINT64_C(64) * 1024 * 1024)
#define VENUS_UNCACHED_MAX_LIVE (UINT64_C(1024) * 1024 * 1024)
#define VENUS_UNCACHED_MAX_LEASES 1024u
struct venus_uncached_request { uint32_t version, reserved; uint64_t bytes; };
struct venus_uncached_reply { int32_t error; uint32_t version; };

static inline bool venus_uncached_request_valid(const struct venus_uncached_request *r) {
    return r && r->version == VENUS_UNCACHED_VERSION && !r->reserved && r->bytes &&
        r->bytes <= VENUS_UNCACHED_MAX_ALLOCATION && !(r->bytes % VENUS_UNCACHED_GRANULE);
}
static inline bool venus_uncached_budget_valid(uint64_t live, uint64_t bytes) {
    return live <= VENUS_UNCACHED_MAX_LIVE && bytes <= VENUS_UNCACHED_MAX_LIVE - live;
}
static inline socklen_t venus_uncached_address(uid_t uid, struct sockaddr_un *address) {
    *address = (struct sockaddr_un){.sun_family=AF_UNIX};
    int n=snprintf(address->sun_path+1,sizeof(address->sun_path)-1,
        "viruaisor.venus.uncached.%u",(unsigned)uid);
    return (socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+n);
}

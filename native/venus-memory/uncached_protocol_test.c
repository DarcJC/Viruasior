#define _GNU_SOURCE
#include "uncached_protocol.h"
#include <assert.h>
#include <limits.h>

int main(void) {
    struct venus_uncached_request request = {VENUS_UNCACHED_VERSION, 0, 65536};
    assert(venus_uncached_request_valid(&request));
    request.version++;
    assert(!venus_uncached_request_valid(&request));
    request.version = VENUS_UNCACHED_VERSION;
    request.reserved = 1;
    assert(!venus_uncached_request_valid(&request));
    request.reserved = 0;
    const uint64_t invalid[] = {0, 1, 4096, 65535, 65537, VENUS_UNCACHED_MAX_ALLOCATION + 65536, UINT64_MAX};
    for (unsigned i=0; i<sizeof(invalid)/sizeof(invalid[0]); ++i) {
        request.bytes = invalid[i];
        assert(!venus_uncached_request_valid(&request));
    }
    request.bytes = VENUS_UNCACHED_MAX_ALLOCATION;
    assert(venus_uncached_request_valid(&request));
    assert(venus_uncached_budget_valid(0, request.bytes));
    assert(venus_uncached_budget_valid(VENUS_UNCACHED_MAX_LIVE - request.bytes, request.bytes));
    assert(!venus_uncached_budget_valid(VENUS_UNCACHED_MAX_LIVE, request.bytes));
    assert(!venus_uncached_budget_valid(UINT64_MAX, request.bytes));
    return 0;
}

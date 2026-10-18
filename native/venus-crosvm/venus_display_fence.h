#pragma once
#include <stdbool.h>
#include <stdint.h>

/* One pending display fence per externally synchronized queue. Vulkan timeout
 * does not retire a submission: retain its fence until completion or idle teardown. */
struct venus_display_fence { uintptr_t pending; };
struct venus_display_fence_ops {
    bool (*create)(void *, uintptr_t *);
    bool (*submit)(void *, uintptr_t);
    int (*wait)(void *, uintptr_t, uint64_t);
    void (*destroy)(void *, uintptr_t);
};
static inline void venus_display_fence_finish_idle(
    struct venus_display_fence *state, const struct venus_display_fence_ops *ops, void *ctx) {
    if (state->pending) ops->destroy(ctx,state->pending);
    state->pending=0;
}
static inline int venus_display_fence_wait(
    struct venus_display_fence *state, const struct venus_display_fence_ops *ops,
    void *ctx, uint64_t timeout_ns) {
    if (state->pending) {
        int status=ops->wait(ctx,state->pending,0);
        if (status<=0) return status;
        venus_display_fence_finish_idle(state,ops,ctx);
    }
    uintptr_t fence=0;
    if (!ops->create(ctx,&fence)) return -1;
    if (!ops->submit(ctx,fence)) { ops->destroy(ctx,fence); return -1; }
    state->pending=fence;
    int status=ops->wait(ctx,fence,timeout_ns);
    if (status>0) venus_display_fence_finish_idle(state,ops,ctx);
    return status;
}

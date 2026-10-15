#ifndef VIRUAISOR_VN_RING_H
#define VIRUAISOR_VN_RING_H
#include "vn_cs.h"
#define VN_TRACE_FUNC()
struct vn_ring;
struct vn_ring_submit_command { int unused; };
/* Generated headers also define Mesa ring convenience wrappers. This harness only uses
 * explicit encode/decode and renderer submit; accidentally using a ring must fail closed. */
static inline struct vn_cs_encoder *vn_ring_submit_command_init(
    struct vn_ring *ring, struct vn_ring_submit_command *submit, void *data, size_t size, size_t reply) {
    (void)ring; (void)submit; (void)data; (void)size; (void)reply; abort();
}
static inline void vn_ring_submit_command(struct vn_ring *ring, struct vn_ring_submit_command *submit) {
    (void)ring; (void)submit; abort();
}
static inline struct vn_cs_decoder *vn_ring_get_command_reply(struct vn_ring *ring, struct vn_ring_submit_command *submit) {
    (void)ring; (void)submit; abort();
}
static inline void vn_ring_free_command_reply(struct vn_ring *ring, struct vn_ring_submit_command *submit) {
    (void)ring; (void)submit; abort();
}
#endif

#ifndef VIRUAISOR_VN_CS_H
#define VIRUAISOR_VN_CS_H
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

typedef uint64_t vn_object_id;
struct vn_cs_encoder { uint8_t *data; size_t capacity, length; bool fatal; };
struct vn_cs_decoder { const uint8_t *data; size_t size, offset; bool fatal; };

static inline bool vn_cs_renderer_protocol_has_api_version(uint32_t version) {
    return version <= VK_API_VERSION_1_1;
}
static inline bool vn_cs_renderer_protocol_has_extension(uint32_t number) {
    (void)number;
    return true; /* Driver and renderer are built from the same pinned protocol revision. */
}
static inline size_t vn_cs_encoder_get_len(const struct vn_cs_encoder *enc) { return enc->length; }
static inline bool vn_cs_encoder_reserve(struct vn_cs_encoder *enc, size_t size) {
    return !enc->fatal && enc->length <= enc->capacity && size <= enc->capacity - enc->length;
}
static inline void vn_cs_encoder_write(struct vn_cs_encoder *enc, size_t size, const void *value, size_t value_size) {
    if (value_size > size || !vn_cs_encoder_reserve(enc, size)) { enc->fatal = true; return; }
    memset(enc->data + enc->length, 0, size);
    if (value_size) memcpy(enc->data + enc->length, value, value_size);
    enc->length += size;
}
static inline void vn_cs_decoder_set_fatal(struct vn_cs_decoder *dec) { dec->fatal = true; }
static inline void vn_cs_decoder_peek(struct vn_cs_decoder *dec, size_t size, void *value, size_t value_size) {
    if (dec->fatal || value_size > size || dec->offset > dec->size || size > dec->size - dec->offset) {
        dec->fatal = true;
        if (value_size) memset(value, 0, value_size);
        return;
    }
    if (value_size) memcpy(value, dec->data + dec->offset, value_size);
}
static inline void vn_cs_decoder_read(struct vn_cs_decoder *dec, size_t size, void *value, size_t value_size) {
    vn_cs_decoder_peek(dec, size, value, value_size);
    if (!dec->fatal) dec->offset += size;
}
static inline vn_object_id vn_cs_handle_load_id(const void **handle, VkObjectType type) {
    (void)type;
    return (uintptr_t)*handle;
}
static inline void vn_cs_handle_store_id(void **handle, vn_object_id id, VkObjectType type) {
    (void)type;
    *handle = (void *)(uintptr_t)id;
}
#endif

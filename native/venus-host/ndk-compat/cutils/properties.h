#pragma once
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX 128
/* NDK equivalent of the read-only libcutils helper used by Mesa os_misc.c. */
static inline int property_get(const char *key, char *value, const char *fallback) {
    int length = __system_property_get(key, value);
    if (!length && fallback) {
        size_t copied = strlcpy(value, fallback, PROPERTY_VALUE_MAX);
        length = copied < PROPERTY_VALUE_MAX ? (int)copied : PROPERTY_VALUE_MAX - 1;
    }
    return length;
}

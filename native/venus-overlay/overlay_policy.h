#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
static inline bool overlay_token_valid(const char *token) {
    if (!token || strlen(token) != 32) return false;
    for (unsigned i = 0; i < 32; ++i)
        if (!((token[i] >= '0' && token[i] <= '9') || (token[i] >= 'a' && token[i] <= 'f'))) return false;
    return true;
}
static inline bool overlay_identity_valid(uint32_t expected, uint32_t actual, const char *name, uint64_t caps) {
    return expected % 100000 >= 10000 && expected % 100000 <= 19999 &&
        expected == actual && name && !strcmp(name, "pro.darc.viruaisor:venus_vm") && caps == 0;
}
static inline bool overlay_namespace_valid(uint64_t target, uint64_t init, uint64_t zygote, uint64_t helper, unsigned members) {
    return target && init && zygote && helper && target != init && target != zygote &&
        target != helper && members == 1;
}
static inline bool overlay_stock_identity_valid(uint32_t expected, uint32_t actual, const char *name, uint64_t caps) {
    return expected % 100000 >= 10000 && expected % 100000 <= 19999 && expected == actual && caps == 0 &&
        name && !strcmp(name, "crosvm_viruaisor-stock-overlay-probe");
}
static inline bool overlay_process_caps_valid(bool virtmgr, uint64_t caps) {
    /* Existing stock virtmgr filecap, confirmed with the no-overlay baseline.
       The copied crosvm intentionally has no filecaps and must remain caps=0. */
    return caps == (virtmgr ? UINT64_C(0x800000) : 0);
}
static inline bool overlay_stock_domain_valid(const char *context, bool virtmgr) {
    const char *expected = virtmgr ? "u:r:untrusted_app_all_virtualizationmanager:s0" : "u:r:untrusted_app_all_virtmgr_crosvm:s0";
    size_t length = strlen(expected);
    return context && !strncmp(context, expected, length) &&
        (!context[length] || context[length] == ':' || context[length] == '\n');
}

#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define OVERLAY_PACKAGE_MAX 255
#define OVERLAY_PROCESS_NAME_MAX (OVERLAY_PACKAGE_MAX + sizeof(":venus_vm") + 2)
static inline bool overlay_package_valid(const char *name) {
    if (!name || !*name || strlen(name) > OVERLAY_PACKAGE_MAX) return false;
    bool start = true, dotted = false;
    for (const char *c = name; *c; ++c) {
        bool letter = (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z');
        if (*c == '.') { if (start) return false; start = true; dotted = true; }
        else { if (!letter && (start || !((*c >= '0' && *c <= '9') || *c == '_'))) return false; start = false; }
    }
    return dotted && !start;
}
static inline bool overlay_files_dir_valid(uint32_t uid, const char *package, const char *files) {
    if (uid % 100000 < 10000 || uid % 100000 > 19999 || !overlay_package_valid(package) || !files) return false;
    char expected[512];
    snprintf(expected, sizeof(expected), "/data/user/%u/%s/files", uid / 100000, package);
    if (!strcmp(files, expected)) return true;
    snprintf(expected, sizeof(expected), "/data/user_de/%u/%s/files", uid / 100000, package);
    if (!strcmp(files, expected)) return true;
    snprintf(expected, sizeof(expected), "/data/data/%s/files", package);
    return uid / 100000 == 0 && !strcmp(files, expected);
}
static inline bool overlay_token_valid(const char *token) {
    if (!token || strlen(token) != 32) return false;
    for (unsigned i = 0; i < 32; ++i)
        if (!((token[i] >= '0' && token[i] <= '9') || (token[i] >= 'a' && token[i] <= 'f'))) return false;
    return true;
}
static inline bool overlay_identity_valid(uint32_t expected, uint32_t actual, const char *name, uint64_t caps, const char *package) {
    return expected % 100000 >= 10000 && expected % 100000 <= 19999 &&
        expected == actual && caps == 0 && overlay_package_valid(package) && name &&
        strlen(name) == strlen(package) + strlen(":venus_vm") &&
        !strncmp(name, package, strlen(package)) && !strcmp(name + strlen(package), ":venus_vm");
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

#define _GNU_SOURCE
#include "storage_control.h"
#include <assert.h>
#include <string.h>
static bool matches(char *text) {
    FILE *input = fmemopen(text, strlen(text), "r");
    assert(input);
    bool result = storage_mount_matches(input);
    fclose(input);
    return result;
}
int main(void) {
    char correct[] = "viruaisor-storage-0 /mnt/shared virtiofs rw,nosuid,nodev 0 0\n";
    char wrong_tag[] = "other-vm /mnt/shared virtiofs rw 0 0\n";
    char wrong_path[] = "viruaisor-storage-0 /mnt/other virtiofs rw 0 0\n";
    char wrong_type[] = "viruaisor-storage-0 /mnt/shared ext4 rw 0 0\n";
    assert(matches(correct));
    assert(!matches(wrong_tag));
    assert(!matches(wrong_path));
    assert(!matches(wrong_type));
    puts("storage mount authentication tests passed");
}

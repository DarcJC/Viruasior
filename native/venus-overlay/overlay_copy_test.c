#define _GNU_SOURCE
#include "overlay_copy.h"
#include <assert.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>

int main(void) {
    char a[] = "/tmp/viruaisor-overlay-source-XXXXXX", b[] = "/tmp/viruaisor-overlay-copy-XXXXXX";
    int source = mkstemp(a), copy = mkstemp(b);
    assert(source >= 0 && copy >= 0);
    unlink(a); unlink(b); /* Temporary anonymous FDs only; never touch a real mount or device. */
    unsigned char bytes[131091];
    for (unsigned i = 0; i < sizeof(bytes); ++i) bytes[i] = (unsigned char)((i * 17) ^ (i >> 8));
    assert(write(source, bytes, sizeof(bytes)) == sizeof(bytes));
    assert(overlay_copy_identical(source, copy));
    assert(overlay_same_bytes(source, copy));
    assert(!overlay_copy_identical(source, copy)); /* Refuse to overwrite a nonempty payload. */
    assert(!overlay_copy_identical(source, source));
    assert(pwrite(copy, "X", 1, 70000) == 1);
    assert(!overlay_same_bytes(source, copy));
    close(source); close(copy);
    return 0;
}

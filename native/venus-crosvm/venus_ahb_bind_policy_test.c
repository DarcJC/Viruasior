#include <assert.h>
#include "venus_ahb_bind_policy.h"

int main(void)
{
    /* Ordinary Vulkan memory retains its existing offset semantics. */
    assert(venus_ahb_bind_allowed(false, false, 4096));
    assert(venus_ahb_bind_allowed(true, true, 0));
    /* A translated external image must never reach Mali with ordinary memory
     * or a suballocation: the Wayland cursor exposed a driver null dereference. */
    assert(!venus_ahb_bind_allowed(true, false, 0));
    assert(!venus_ahb_bind_allowed(true, true, 4096));
    return 0;
}

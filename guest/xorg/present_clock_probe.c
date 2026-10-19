/* Diagnostic only: preload into one QtQuick GLX client, never the desktop. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <inttypes.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <xcb/present.h>
#include <xcb/xcbext.h>

static void observe(xcb_connection_t *connection, xcb_generic_event_t *event)
{
    if (!event || (event->response_type & 127) != XCB_GE_GENERIC)
        return;
    const xcb_query_extension_reply_t *extension =
        xcb_get_extension_data(connection, &xcb_present_id);
    const xcb_present_complete_notify_event_t *complete = (const void *) event;
    if (!extension || !extension->present || complete->extension != extension->major_opcode ||
        complete->event_type != XCB_PRESENT_COMPLETE_NOTIFY || complete->length < 2 ||
        complete->kind != XCB_PRESENT_COMPLETE_KIND_PIXMAP)
        return;
    static atomic_uint count;
    unsigned sample = atomic_fetch_add(&count, 1) + 1;
    if (sample <= 4 || sample % 300 == 0)
        fprintf(stderr, "VIRUAISOR_PRESENT_PROBE COMPLETE count=%u kind=%u mode=%u "
                "ust=%" PRIu64 " msc=%" PRIu64 "\n", sample, complete->kind,
                complete->mode, complete->ust, complete->msc);
}

/* The probe is used by a single-window GLX client. Resolving each call keeps
 * the wrappers independent of client thread/connection initialization order. */
#define WRAP_EVENT(name) \
    xcb_generic_event_t *name(xcb_connection_t *connection, xcb_special_event_t *special) \
    { \
        xcb_generic_event_t *(*next)(xcb_connection_t *, xcb_special_event_t *) = \
            dlsym(RTLD_NEXT, #name); \
        if (!next) abort(); \
        xcb_generic_event_t *event = next(connection, special); \
        observe(connection, event); \
        return event; \
    }
WRAP_EVENT(xcb_wait_for_special_event)
WRAP_EVENT(xcb_poll_for_special_event)

#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define Success 0
#define RR_DoubleScan 32
#define RR_Interlace 16
typedef void *PixmapPtr;
typedef struct Screen *ScreenPtr;
typedef struct Window *WindowPtr;
typedef struct { uint64_t dotClock; uint32_t hTotal,vTotal,modeFlags; } xRRModeInfo;
struct Mode { xRRModeInfo mode; };
typedef struct Crtc { struct Mode *mode; ScreenPtr pScreen; } *RRCrtcPtr;
typedef struct Randr { int numCrtcs; RRCrtcPtr *crtcs; } *rrScrPrivPtr;
struct Window { struct { ScreenPtr pScreen; } drawable; PixmapPtr pixmap; };
struct Screen {
    PixmapPtr (*GetWindowPixmap)(WindowPtr);
    PixmapPtr (*GetScreenPixmap)(ScreenPtr);
    rrScrPrivPtr rr;
    PixmapPtr pixmap,pending;
};
struct Info {
    RRCrtcPtr (*get_crtc)(WindowPtr);
    int (*get_ust_msc)(RRCrtcPtr,uint64_t *,uint64_t *);
};
typedef struct Priv { struct Info *info; PixmapPtr flip_pixmap; } *present_screen_priv_ptr;
#define rrGetScrPriv(screen) ((screen)->rr)
static RRCrtcPtr current;
static int hardware_status=1,clock_calls=0;
static uint64_t last_period=0;
static PixmapPtr window_pixmap(WindowPtr window) {return window->pixmap;}
static PixmapPtr screen_pixmap(ScreenPtr screen) {return screen->pixmap;}
static __attribute__((unused)) PixmapPtr present_flip_pending_pixmap(ScreenPtr screen) {return screen->pending;}
static RRCrtcPtr get_crtc(WindowPtr window) {(void)window;return current;}
static int get_ust_msc(RRCrtcPtr crtc,uint64_t *ust,uint64_t *msc) {(void)crtc;*ust=1;*msc=1;return hardware_status;}
static void present_fake_set_clock(ScreenPtr screen,uint64_t period) {(void)screen;last_period=period;clock_calls++;}
#include "function-under-test.h"
int main(void) {
    int screen_storage,redirected_storage,flip_storage,pending_storage;
    struct Mode mode={{150000000,2000,1250,0}};
    struct Crtc crtc={&mode,NULL},second={&mode,NULL};
    RRCrtcPtr crtcs[]={&crtc,&second};
    struct Randr rr={1,crtcs};
    struct Screen screen={window_pixmap,screen_pixmap,&rr,&screen_storage,&pending_storage};
    struct Window window={{&screen},&screen_storage};
    struct Info info={get_crtc,get_ust_msc};
    struct Priv priv={&info,&flip_storage};
    current=&crtc;crtc.pScreen=&screen;second.pScreen=&screen;
    assert(setenv("VIRUAISOR_X11_SOFTWARE_CLOCK","1",1)==0);
    // A missing hardware clock must not remove the compositor's flip CRTC.
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    assert(clock_calls==0);
    window.pixmap=&flip_storage;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    window.pixmap=&pending_storage;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    window.pixmap=NULL;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    assert(clock_calls==0);
    // A redirected application still gets the failed-clock fallback.
    window.pixmap=&redirected_storage;
    assert(present_scmd_get_crtc(&priv,&window)==NULL);
    assert(clock_calls==1&&last_period==16666667);
    hardware_status=Success;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    hardware_status=1;rr.numCrtcs=2;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    rr.numCrtcs=1;mode.mode.dotClock=0;
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    mode.mode.dotClock=150000000;
    assert(unsetenv("VIRUAISOR_X11_SOFTWARE_CLOCK")==0);
    assert(present_scmd_get_crtc(&priv,&window)==&crtc);
    assert(clock_calls==1);
    puts("PASS: screen/current/pending flip paths preserved; redirected-only fallback and original gates");
}

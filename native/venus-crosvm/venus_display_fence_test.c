#include "venus_display_fence.h"
#include <assert.h>

struct fake { int creates, submits, destroys, status; };
static bool create(void *p, uintptr_t *f) { struct fake *s=p; *f=++s->creates; return true; }
static bool submit(void *p, uintptr_t f) { (void)f; ++((struct fake *)p)->submits; return true; }
static int wait_for(void *p, uintptr_t f, uint64_t ns) { (void)f; (void)ns; return ((struct fake *)p)->status; }
static void destroy(void *p, uintptr_t f) { (void)f; ++((struct fake *)p)->destroys; }
int main(void) {
    struct fake gpu={0};
    struct venus_display_fence fence={0};
    const struct venus_display_fence_ops ops={create,submit,wait_for,destroy};
    assert(venus_display_fence_wait(&fence,&ops,&gpu,10)==0);
    assert(gpu.creates==1 && gpu.submits==1 && gpu.destroys==0);
    assert(venus_display_fence_wait(&fence,&ops,&gpu,10)==0);
    assert(gpu.creates==1 && gpu.submits==1 && gpu.destroys==0);
    gpu.status=1;
    assert(venus_display_fence_wait(&fence,&ops,&gpu,10)==1);
    /* A completed old fence cannot qualify work submitted since the last call. */
    assert(gpu.creates==2 && gpu.submits==2 && gpu.destroys==2);
    gpu.status=0;
    assert(venus_display_fence_wait(&fence,&ops,&gpu,10)==0);
    gpu.status=-1;
    assert(venus_display_fence_wait(&fence,&ops,&gpu,10)==-1);
    assert(gpu.destroys==2); /* Error is not proof that pending GPU work retired. */
    venus_display_fence_finish_idle(&fence,&ops,&gpu);
    assert(gpu.destroys==3 && !fence.pending);
}

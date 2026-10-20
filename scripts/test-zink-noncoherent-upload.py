"""Compile real Mesa upload call sites and check visibility before GPU consumers.

The fixtures model non-coherent memory: CPU writes remain invisible to the GPU
until the uploader flushes. Device rendering is checked separately with the
Chromium color page and repeated GLES pixel reads.
"""
from pathlib import Path
import subprocess
import sys
import tempfile


def body(text, name):
    start = text.index("{", text.index(name + "(")) + 1
    depth = 1
    for end in range(start, len(text)):
        depth += (text[end] == "{") - (text[end] == "}")
        if depth == 0:
            return text[start:end]
    raise ValueError("Unterminated Mesa function: " + name)


if len(sys.argv) != 2:
    raise SystemExit("Usage: test-zink-noncoherent-upload.py MESA_SOURCE")
root = Path(sys.argv[1]) / "src/gallium"
zink = (root / "drivers/zink/zink_context.c").read_text()
tc = (root / "auxiliary/util/u_threaded_context.c").read_text()
constant = body(zink, "zink_set_constant_buffer_internal")
constant = body("void upload(" + constant[constant.index("if (cb->user_buffer)"):], "upload")
staging = body(tc, "tc_buffer_do_flush_region")
single = body(tc, "tc_draw_user_indices_single").split("   struct tc_draw_single *p =", 1)[0]
draw_id = body(tc, "tc_draw_user_indices_single_draw_id").split("   struct tc_draw_single *p =", 1)[0]
multi = body(tc, "tc_draw_user_indices_multi").split("   int total_offset = 0;", 1)[0]
source = r'''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define unlikely(x) (x)
#define PIPE_MAP_WRITE 2
#define TC_TRANSFER_MAP_UPLOAD_CPU_STORAGE 8
struct u_upload_mgr;
struct pipe_resource { struct u_upload_mgr *upload; };
struct u_upload_mgr { bool coherent, published; unsigned flushes; uint8_t cpu[256], gpu[256]; struct pipe_resource resource; };
struct pipe_screen { struct { bool buffer_map_persistent_coherent; } caps; };
struct pipe_context { struct pipe_screen *screen; struct u_upload_mgr *stream_uploader, *const_uploader; };
struct threaded_context { struct pipe_context base; unsigned map_buffer_alignment; };
struct zink_context { struct pipe_context base; };
struct zink_screen { struct { struct { struct { unsigned minUniformBufferOffsetAlignment; } limits; } props; } info; };
struct pipe_box { int x, width; };
struct threaded_transfer { struct { struct pipe_resource *resource; struct pipe_box box; unsigned offset, usage; } b; struct pipe_resource *staging; void *valid_buffer_range; };
struct threaded_resource { struct pipe_resource b; };
struct pipe_constant_buffer { unsigned buffer_size; void *user_buffer; };
struct pipe_draw_info { unsigned index_size; union { void *user; } index; };
struct pipe_draw_start_count_bias { unsigned start,count; int index_bias; };
static struct threaded_resource resource;
static struct threaded_resource *threaded_resource(struct pipe_resource *p) { (void)p; return &resource; }
static struct threaded_context *threaded_context(struct pipe_context *p) { return (void*)p; }
#define util_range_add(...) ((void)0)
static void u_box_1d(int x,int size,struct pipe_box *b) { b->x=x;b->width=size; }
static unsigned util_logbase2(unsigned x) { return x==2?1:0; }
static void u_upload_alloc(struct u_upload_mgr *u,unsigned min,unsigned size,unsigned align,unsigned *offset,struct pipe_resource **buf,void **ptr) {
 (void)min;(void)align;assert(size<=sizeof(u->cpu));u->resource.upload=u;u->published=false;*offset=0;*buf=&u->resource;*ptr=u->cpu;
}
static void u_upload_data(struct u_upload_mgr *u,unsigned min,unsigned size,unsigned align,const void *data,unsigned *offset,struct pipe_resource **buf) {
 void *p;u_upload_alloc(u,min,size,align,offset,buf,&p);memcpy(p,data,size);
}
static void u_upload_unmap(struct u_upload_mgr *u) {
 if (!u->coherent) {memcpy(u->gpu,u->cpu,sizeof(u->gpu));u->published=true;u->flushes++;}
}
static void gpu_read(struct pipe_resource *p,unsigned offset,unsigned size,const void *expected) {
 struct u_upload_mgr *u=p->upload;
 assert(u->coherent||u->published);
 assert(!memcmp((u->coherent?u->cpu:u->gpu)+offset,expected,size));
}
static void tc_resource_copy_region(struct pipe_context *pipe,struct pipe_resource *dst,unsigned dl,unsigned dx,unsigned dy,unsigned dz,struct pipe_resource *src,unsigned sl,const struct pipe_box *box) {
 (void)pipe;(void)dst;(void)dl;(void)dx;(void)dy;(void)dz;(void)sl;
 const uint8_t expected[]={2,4,6,8};assert(box->width==4);gpu_read(src,box->x,4,expected);
}
static void test_constant(bool coherent) {
 struct u_upload_mgr u={.coherent=coherent};struct zink_context c={.base.const_uploader=&u},*ctx=&c;
 struct zink_screen s={.info.props.limits.minUniformBufferOffsetAlignment=4},*screen=&s;
 uint32_t data[]={0x12345678,0xabcdef12};struct pipe_constant_buffer b={sizeof(data),data},*cb=&b;
 struct pipe_resource *buffer=NULL;unsigned offset=0;
''' + constant + r'''
 gpu_read(buffer,offset,sizeof(data),data);assert(u.flushes==!coherent);
}
static void do_staging(struct threaded_context *tc,struct threaded_transfer *ttrans,const struct pipe_box *box) {
''' + staging + r'''
}
static void test_single(struct pipe_context *_pipe,const struct pipe_draw_info *info,const struct pipe_draw_start_count_bias *draws) {
''' + single + r'''
 gpu_read(buffer,offset,size,(uint8_t*)info->index.user+draws[0].start*index_size);
}
static void test_draw_id(struct pipe_context *_pipe,const struct pipe_draw_info *info,const struct pipe_draw_start_count_bias *draws) {
''' + draw_id + r'''
 gpu_read(buffer,offset,size,(uint8_t*)info->index.user+draws[0].start*index_size);
}
static void test_multi(struct pipe_context *_pipe,const struct pipe_draw_info *info,const struct pipe_draw_start_count_bias *draws,unsigned num_draws) {
''' + multi + r'''
 if (!_pipe->screen->caps.buffer_map_persistent_coherent) {
  uint16_t expected[]={11,12,15,16,17};gpu_read(buffer,buffer_offset,sizeof(expected),expected);
 }
}
int main(void) {
 for (unsigned coherent=0;coherent<2;coherent++) {
  test_constant(coherent);
  struct u_upload_mgr u={.coherent=coherent};struct pipe_screen screen={.caps.buffer_map_persistent_coherent=coherent};
  struct threaded_context tc={.base={&screen,&u,NULL},.map_buffer_alignment=4};
  struct pipe_resource *p;unsigned offset;void *ptr;u_upload_alloc(&u,0,4,4,&offset,&p,&ptr);
  const uint8_t bytes[]={2,4,6,8};memcpy(ptr,bytes,4);
  struct threaded_transfer transfer={.b={.resource=&resource.b,.box={0,4}},.staging=p};struct pipe_box box={0,4};
  do_staging(&tc,&transfer,&box);assert(u.flushes==!coherent);
  uint16_t indices[]={11,12,13,14,15,16,17};
  struct pipe_draw_info info={.index_size=2,.index.user=indices};
  struct pipe_draw_start_count_bias draws[]={{0,2,0},{3,0,0},{4,3,0}};
  test_single(&tc.base,&info,draws);test_draw_id(&tc.base,&info,draws);test_multi(&tc.base,&info,draws,3);
 }
 puts("PASS: real constant, staging-copy and index-upload call sites publish non-coherent writes before GPU use");
}
'''
with tempfile.TemporaryDirectory(prefix="zink-upload-test-") as directory:
    path = Path(directory)
    (path / "test.c").write_text(source)
    subprocess.run(["gcc", "-std=gnu11", "-Wall", "-Wextra", "-Wno-unused-variable",
                    "-Wno-unused-function", str(path / "test.c"), "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)

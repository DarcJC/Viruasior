#!/usr/bin/env python3
"""Bounded by the caller: EGL/Zink/gfxstream draw + pixel readback, no display required.

Run as the test guest's UID 1000 with render-node access. Select only the gfxstream
Vulkan ICD and Zink before launch. No packages, device permissions, or settings are changed.
This tests GPU rendering separately from Weston, KMS, and Android Surface presentation.
"""

import ctypes as C
import os
import sys

GFX_ICD = "/usr/share/vulkan/icd.d/gfxstream_vk_icd.json"
SIZE = 32
I, U, P = C.c_int, C.c_uint, C.c_void_p


def bind(lib, name, result, args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


def run():
    if os.geteuid() != 1000:
        raise RuntimeError("refusing to render outside test UID 1000")
    if not os.access("/dev/dri/renderD128", os.R_OK | os.W_OK):
        raise RuntimeError("render node is not readable/writable")
    if (os.environ.get("VK_DRIVER_FILES") != GFX_ICD
            or os.environ.get("VK_ICD_FILENAMES") != GFX_ICD
            or os.environ.get("MESA_LOADER_DRIVER_OVERRIDE") != "zink"
            or os.environ.get("LIBGL_ALWAYS_SOFTWARE", "").lower() not in ("0", "false")):
        raise RuntimeError("explicit gfxstream/Zink/nonsoftware environment is required")
    print("DRAW_UID=%s DRAW_GID=%s DRAW_GROUPS=%s" %
          (os.geteuid(), os.getegid(), os.getgroups()), flush=True)

    print("DRAW_STAGE=load-EGL", flush=True)
    egl = C.CDLL("libEGL.so.1")
    print("DRAW_STAGE=load-GLES", flush=True)
    gl = C.CDLL("libGLESv2.so.2")
    print("DRAW_STAGE=get-platform-display-entrypoint", flush=True)
    get_proc = bind(egl, "eglGetProcAddress", P, [C.c_char_p])
    address = get_proc(b"eglGetPlatformDisplayEXT")
    if not address:
        raise RuntimeError("EGL platform-display extension unavailable")
    platform_display = C.CFUNCTYPE(P, U, P, C.POINTER(I))(address)
    print("DRAW_STAGE=get-surfaceless-display", flush=True)
    display = platform_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
    print("DRAW_STAGE=bind-EGL-functions", flush=True)
    initialize = bind(egl, "eglInitialize", U, [P, C.POINTER(I), C.POINTER(I)])
    egl_error = bind(egl, "eglGetError", I, [])
    bind_api = bind(egl, "eglBindAPI", U, [U])
    choose = bind(egl, "eglChooseConfig", U, [P, C.POINTER(I), C.POINTER(P), I, C.POINTER(I)])
    pbuffer = bind(egl, "eglCreatePbufferSurface", P, [P, P, C.POINTER(I)])
    context_create = bind(egl, "eglCreateContext", P, [P, P, P, C.POINTER(I)])
    current = bind(egl, "eglMakeCurrent", U, [P, P, P, P])
    destroy_context = bind(egl, "eglDestroyContext", U, [P, P])
    destroy_surface = bind(egl, "eglDestroySurface", U, [P, P])
    terminate = bind(egl, "eglTerminate", U, [P])
    surface = context = None

    def check(value, operation):
        if not value:
            raise RuntimeError("%s: EGL error 0x%x" % (operation, egl_error()))
        return value

    try:
        major, minor = I(), I()
        print("DRAW_STAGE=eglInitialize", flush=True)
        check(initialize(display, C.byref(major), C.byref(minor)), "eglInitialize")
        print("EGL_VERSION=%s.%s" % (major.value, minor.value), flush=True)
        check(bind_api(0x30A0), "eglBindAPI")  # EGL_OPENGL_ES_API
        attributes = (I * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8,
                              0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = P(), I()
        check(choose(display, attributes, C.byref(config), 1, C.byref(count)), "eglChooseConfig")
        if count.value != 1:
            raise RuntimeError("no RGBA8 GLES2 pbuffer config")
        print("DRAW_STAGE=create-pbuffer", flush=True)
        surface = check(pbuffer(display, config, (I * 5)(0x3057, SIZE, 0x3056, SIZE, 0x3038)),
                        "eglCreatePbufferSurface")
        print("DRAW_STAGE=create-context", flush=True)
        context = check(context_create(display, config, None, (I * 3)(0x3098, 2, 0x3038)),
                        "eglCreateContext")
        print("DRAW_STAGE=make-current", flush=True)
        check(current(display, surface, surface, context), "eglMakeCurrent")
        get_string = bind(gl, "glGetString", C.c_char_p, [U])
        renderer = (get_string(0x1F01) or b"").decode("utf8", "replace")
        print("GL_VENDOR=" + (get_string(0x1F00) or b"").decode("utf8", "replace"), flush=True)
        print("GL_RENDERER=" + renderer, flush=True)
        print("GL_VERSION=" + (get_string(0x1F02) or b"").decode("utf8", "replace"), flush=True)
        lower = renderer.lower()
        if ("zink" not in lower or "mali" not in lower
                or any(s in lower for s in ("llvmpipe", "lavapipe", "softpipe", "swiftshader"))):
            raise RuntimeError("renderer is not the intended Zink/Mali path")

        create_shader = bind(gl, "glCreateShader", U, [U])
        shader_source = bind(gl, "glShaderSource", None, [U, I, C.POINTER(C.c_char_p), C.POINTER(I)])
        compile_shader = bind(gl, "glCompileShader", None, [U])
        shader_iv = bind(gl, "glGetShaderiv", None, [U, U, C.POINTER(I)])
        shader_log = bind(gl, "glGetShaderInfoLog", None, [U, I, C.POINTER(I), P])

        def shader(kind, source):
            handle = create_shader(kind)
            shader_source(handle, 1, (C.c_char_p * 1)(source), None)
            compile_shader(handle)
            status = I()
            shader_iv(handle, 0x8B81, C.byref(status))  # GL_COMPILE_STATUS
            if not status.value:
                log = C.create_string_buffer(2048)
                shader_log(handle, len(log), None, log)
                raise RuntimeError("shader compile: " + log.value.decode("utf8", "replace"))
            return handle

        print("DRAW_STAGE=compile-shaders", flush=True)
        vertex = shader(0x8B31, b"attribute vec2 position; void main(){gl_Position=vec4(position,0.,1.);}")
        fragment = shader(0x8B30, b"precision highp float; void main(){gl_FragColor=vec4(gl_FragCoord.x/32.,gl_FragCoord.y/32.,0.5,1.);}")
        program = bind(gl, "glCreateProgram", U, [])()
        attach = bind(gl, "glAttachShader", None, [U, U])
        attach(program, vertex)
        attach(program, fragment)
        bind(gl, "glBindAttribLocation", None, [U, U, C.c_char_p])(program, 0, b"position")
        bind(gl, "glLinkProgram", None, [U])(program)
        link = I()
        bind(gl, "glGetProgramiv", None, [U, U, C.POINTER(I)])(program, 0x8B82, C.byref(link))
        if not link.value:
            log = C.create_string_buffer(2048)
            bind(gl, "glGetProgramInfoLog", None, [U, I, C.POINTER(I), P])(program, len(log), None, log)
            raise RuntimeError("program link: " + log.value.decode("utf8", "replace"))

        # A full-screen triangle shades three different samples; a constant clear cannot pass.
        vertices = (C.c_float * 6)(-1, -1, 3, -1, -1, 3)
        buffer = U()
        bind(gl, "glGenBuffers", None, [I, C.POINTER(U)])(1, C.byref(buffer))
        bind(gl, "glBindBuffer", None, [U, U])(0x8892, buffer)
        bind(gl, "glBufferData", None, [U, C.c_ssize_t, P, U])(0x8892, C.sizeof(vertices), vertices, 0x88E4)
        bind(gl, "glViewport", None, [I, I, I, I])(0, 0, SIZE, SIZE)
        bind(gl, "glClearColor", None, [C.c_float] * 4)(0, 0, 0, 1)
        bind(gl, "glClear", None, [U])(0x4000)
        bind(gl, "glUseProgram", None, [U])(program)
        bind(gl, "glEnableVertexAttribArray", None, [U])(0)
        bind(gl, "glVertexAttribPointer", None, [U, I, U, C.c_ubyte, I, P])(0, 2, 0x1406, 0, 0, None)
        print("DRAW_STAGE=draw-and-finish", flush=True)
        bind(gl, "glDrawArrays", None, [U, I, I])(4, 0, 3)
        bind(gl, "glFinish", None, [])()
        read_pixels = bind(gl, "glReadPixels", None, [I, I, I, I, U, U, P])
        get_error = bind(gl, "glGetError", U, [])
        error = get_error()
        if error:
            raise RuntimeError("draw: GL error 0x%x" % error)
        print("DRAW_STAGE=read-pixels", flush=True)
        for x, y in ((1, 1), (16, 16), (30, 30)):
            pixel = (C.c_ubyte * 4)()
            read_pixels(x, y, 1, 1, 0x1908, 0x1401, pixel)
            error = get_error()
            if error:
                raise RuntimeError("readback: GL error 0x%x" % error)
            expected = (round(255 * (x + 0.5) / SIZE), round(255 * (y + 0.5) / SIZE), 128, 255)
            print("PIXEL(%s,%s)=%s expected=%s" % (x, y, list(pixel), expected), flush=True)
            if any(abs(actual - target) > 2 for actual, target in zip(pixel, expected)):
                raise RuntimeError("shader readback mismatch")
        print("GPU_DRAW_READBACK_PASS (not Android Surface presentation)", flush=True)
    finally:
        if display:
            current(display, None, None, None)
            if context:
                destroy_context(display, context)
            if surface:
                destroy_surface(display, surface)
            terminate(display)


if __name__ == "__main__":
    try:
        run()
    except Exception as error:
        print("GPU_DRAW_READBACK_FAIL: " + str(error), file=sys.stderr, flush=True)
        sys.exit(1)

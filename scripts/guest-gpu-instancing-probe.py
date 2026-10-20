#!/usr/bin/env python3
"""Render instanced colors through guest Zink/Venus and check actual pixels.

Run as the desktop user with its normal GPU environment. No browser flags,
packages, device permissions, or system settings are changed. Divisors 1 and 2
cover the KHR-only host path; repeated single-pixel reads cover constant uploads.
"""
import ctypes as C
import os
import struct
import sys

SIZE = 1024
I, U, P = C.c_int, C.c_uint, C.c_void_p


def bind(lib, name, result, args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


def run():
    if os.geteuid() != 1000 or not os.access('/dev/dri/renderD128', os.R_OK | os.W_OK):
        raise RuntimeError('run as the guest desktop user with render-node access')
    if os.environ.get('MESA_LOADER_DRIVER_OVERRIDE') != 'zink':
        raise RuntimeError('select the normal Zink/Venus guest environment')
    egl, gl = C.CDLL('libEGL.so.1'), C.CDLL('libGLESv2.so.2')
    get_proc = bind(egl, 'eglGetProcAddress', P, [C.c_char_p])
    platform = C.CFUNCTYPE(P, U, P, C.POINTER(I))(get_proc(b'eglGetPlatformDisplayEXT'))
    display = platform(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
    current = bind(egl, 'eglMakeCurrent', U, [P, P, P, P])
    surface = context = None
    try:
        major, minor = I(), I()
        assert bind(egl, 'eglInitialize', U, [P, C.POINTER(I), C.POINTER(I)])(display, C.byref(major), C.byref(minor))
        assert bind(egl, 'eglBindAPI', U, [U])(0x30A0)  # EGL_OPENGL_ES_API
        attrs = (I * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = P(), I()
        assert bind(egl, 'eglChooseConfig', U, [P, C.POINTER(I), C.POINTER(P), I, C.POINTER(I)])(display, attrs, C.byref(config), 1, C.byref(count)) and count.value == 1
        surface = bind(egl, 'eglCreatePbufferSurface', P, [P, P, C.POINTER(I)])(display, config, (I * 5)(0x3057, SIZE, 0x3056, SIZE, 0x3038))
        context = bind(egl, 'eglCreateContext', P, [P, P, P, C.POINTER(I)])(display, config, None, (I * 3)(0x3098, 2, 0x3038))
        assert surface and context and current(display, surface, surface, context)
        get_string = bind(gl, 'glGetString', C.c_char_p, [U])
        renderer = get_string(0x1F01).decode()
        print('GL_RENDERER=' + renderer, flush=True)
        assert 'zink' in renderer.lower() and 'venus' in renderer.lower(), renderer
        assert not any(x in renderer.lower() for x in ('llvmpipe', 'lavapipe', 'swiftshader', 'softpipe'))
        assert b'GL_EXT_instanced_arrays' in get_string(0x1F03).split(), 'missing GL_EXT_instanced_arrays'

        shader_iv = bind(gl, 'glGetShaderiv', None, [U, U, C.POINTER(I)])
        def shader(kind, text):
            handle = bind(gl, 'glCreateShader', U, [U])(kind)
            bind(gl, 'glShaderSource', None, [U, I, C.POINTER(C.c_char_p), P])(handle, 1, (C.c_char_p * 1)(text), None)
            bind(gl, 'glCompileShader', None, [U])(handle)
            status = I()
            shader_iv(handle, 0x8B81, C.byref(status))
            assert status.value, 'shader compilation failed'
            return handle

        vs = shader(0x8B31, b'attribute vec2 position; attribute vec2 offset; attribute vec4 color; varying vec4 vcolor; void main(){gl_Position=vec4(position+offset,0.,1.);vcolor=color;}')
        fs = shader(0x8B30, b'precision highp float; varying vec4 vcolor; void main(){gl_FragColor=vcolor;}')
        program = bind(gl, 'glCreateProgram', U, [])()
        attach = bind(gl, 'glAttachShader', None, [U, U])
        attach(program, vs)
        attach(program, fs)
        for index, name in enumerate((b'position', b'color', b'offset')):
            bind(gl, 'glBindAttribLocation', None, [U, U, C.c_char_p])(program, index, name)
        bind(gl, 'glLinkProgram', None, [U])(program)
        status = I()
        bind(gl, 'glGetProgramiv', None, [U, U, C.POINTER(I)])(program, 0x8B82, C.byref(status))
        assert status.value, 'program link failed'
        bind(gl, 'glUseProgram', None, [U])(program)
        bind(gl, 'glViewport', None, [I, I, I, I])(0, 0, SIZE, SIZE)

        def upload(data):
            buf = U()
            bind(gl, 'glGenBuffers', None, [I, C.POINTER(U)])(1, C.byref(buf))
            bind(gl, 'glBindBuffer', None, [U, U])(0x8892, buf)
            bind(gl, 'glBufferData', None, [U, C.c_ssize_t, P, U])(0x8892, len(data), C.create_string_buffer(data), 0x88E4)

        attrib = bind(gl, 'glVertexAttribPointer', None, [U, I, U, C.c_ubyte, I, P])
        enable = bind(gl, 'glEnableVertexAttribArray', None, [U])
        divisor = bind(gl, 'glVertexAttribDivisor', None, [U, U])
        upload(b''.join(struct.pack('<2f', x / SIZE * 2, y / SIZE * 2) for x, y in ((20, 20), (100, 20), (100, 100), (20, 20), (100, 100), (20, 100))))
        enable(0)
        attrib(0, 2, 0x1406, 0, 8, None)
        colors = ((255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255), (0, 0, 0), (0, 255, 255), (255, 0, 255), (255, 255, 0))
        upload(b''.join(struct.pack('<2f4B', col * 100 / SIZE * 2 - 1, row * 100 / SIZE * 2 - 1, *colors[(row + col) % 8], 255) for row in range(8) for col in range(8)))
        enable(1)
        attrib(1, 4, 0x1401, 1, 12, P(8))
        enable(2)
        attrib(2, 2, 0x1406, 0, 12, None)
        error = bind(gl, 'glGetError', U, [])
        read = bind(gl, 'glReadPixels', None, [I, I, I, I, U, U, P])
        for rate in (1, 2):
            divisor(1, rate)
            divisor(2, rate)
            for index in (1, 2):
                actual = I()
                bind(gl, 'glGetVertexAttribiv', None, [U, U, C.POINTER(I)])(index, 0x88FE, C.byref(actual))
                assert actual.value == rate and error() == 0, 'vertex divisor was discarded'
            for iteration in range(3):
                bind(gl, 'glClearColor', None, [C.c_float] * 4)(1, 1, 1, 1)
                bind(gl, 'glClear', None, [U])(0x4000)
                bind(gl, 'glDrawArraysInstanced', None, [U, I, I, I])(4, 0, 6, 64 * rate)
                bind(gl, 'glFinish', None, [])()
                for row in range(8):
                    for col in range(8):
                        pixel = (C.c_ubyte * 4)()
                        read(60 + col * 100, 60 + row * 100, 1, 1, 0x1908, 0x1401, pixel)
                        expected = (*colors[(row + col) % 8], 255)
                        assert all(abs(v - t) <= 2 for v, t in zip(pixel, expected)), (rate, iteration, row, col, list(pixel), expected)
                assert error() == 0, 'GL error during instanced rendering'
                print('PASS divisor=%d iteration=%d samples=64' % (rate, iteration), flush=True)
    finally:
        current(display, None, None, None)
        if context:
            bind(egl, 'eglDestroyContext', U, [P, P])(display, context)
        if surface:
            bind(egl, 'eglDestroySurface', U, [P, P])(display, surface)
        bind(egl, 'eglTerminate', U, [P])(display)


if __name__ == '__main__':
    try:
        run()
    except Exception as exc:
        print('GPU_INSTANCING_FAIL: ' + str(exc), file=sys.stderr, flush=True)
        sys.exit(1)

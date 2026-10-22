/* Pixel regression for non-coherent GLthread uploads (Weston's draw path).
 * Build in the guest: cc -O2 -Wall -Wextra gles-client-arrays.c -o gles-client-arrays
 *                    -lEGL -lGLESv2 -lX11
 * Run in the Venus desktop environment. A black/stale frame is a failure,
 * even when all GL calls succeed. No environment workaround is required.
 */
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <stdlib.h>

static void require(int condition, const char *message)
{
    if (!condition) {
        fprintf(stderr, "%s (EGL error %#x)\n", message, eglGetError());
        exit(1);
    }
}

static GLuint shader(GLenum type, const char *source)
{
    GLuint result = glCreateShader(type);
    glShaderSource(result, 1, &source, NULL);
    glCompileShader(result);
    GLint compiled;
    glGetShaderiv(result, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log[4096];
        glGetShaderInfoLog(result, sizeof(log), NULL, log);
        fprintf(stderr, "Shader: %s\n", log);
        exit(1);
    }
    return result;
}

int main(void)
{
    enum { WIDTH = 320, HEIGHT = 240, FRAMES = 240 };
    Display *x11 = XOpenDisplay(NULL);
    require(x11 != NULL, "XOpenDisplay");
    EGLDisplay display = eglGetDisplay((EGLNativeDisplayType)x11);
    require(eglInitialize(display, NULL, NULL), "eglInitialize");
    require(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI");
    const EGLint attributes[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE
    };
    EGLConfig config;
    EGLint count, visual_id;
    require(eglChooseConfig(display, attributes, &config, 1, &count) && count,
            "eglChooseConfig");
    require(eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &visual_id),
            "EGL_NATIVE_VISUAL_ID");
    XVisualInfo query = {.visualid = visual_id};
    int visual_count;
    XVisualInfo *visual = XGetVisualInfo(x11, VisualIDMask, &query, &visual_count);
    require(visual != NULL && visual_count, "XGetVisualInfo");
    Window root = RootWindow(x11, visual->screen);
    Colormap colormap = XCreateColormap(x11, root, visual->visual, AllocNone);
    XSetWindowAttributes window_attributes = {.colormap = colormap};
    Window window = XCreateWindow(x11, root, 0, 0, WIDTH, HEIGHT, 0,
                                 visual->depth, InputOutput, visual->visual,
                                 CWColormap, &window_attributes);
    XFree(visual);
    XStoreName(x11, window, "Viruaisor GL upload regression");
    XMapWindow(x11, window);
    XFlush(x11);
    EGLSurface surface = eglCreateWindowSurface(display, config, window, NULL);
    require(surface != EGL_NO_SURFACE, "eglCreateWindowSurface");
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT,
                                         context_attributes);
    require(context != EGL_NO_CONTEXT, "eglCreateContext");
    require(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent");
    eglSwapInterval(display, 0);

    GLuint vertex = shader(GL_VERTEX_SHADER,
        "#version 300 es\nlayout(location=0) in vec2 p; uniform mat4 m;"
        "void main(){gl_Position=m*vec4(p,0,1);}");
    GLuint fragment = shader(GL_FRAGMENT_SHADER,
        "#version 300 es\nprecision highp float; uniform vec4 color; out vec4 f;"
        "void main(){f=color;}");
    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    require(linked, "glLinkProgram");
    glUseProgram(program);
    GLint color = glGetUniformLocation(program, "color");
    GLint matrix = glGetUniformLocation(program, "m");
    const GLfloat identity[] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    glUniformMatrix4fv(matrix, 1, GL_FALSE, identity);
    GLuint buffer;
    glGenBuffers(1, &buffer);
    glViewport(0, 0, WIDTH, HEIGHT);
    glEnableVertexAttribArray(0);

    const char *names[] = {"clear", "client-arrays", "client-indices", "vbo"};
    unsigned failures = 0;
    for (unsigned mode = 0; mode < 4; mode++) {
        unsigned bad = 0;
        for (unsigned frame = 0; frame < FRAMES; frame++) {
            /* Change both uploads so stale cached data cannot pass. */
            float extent = frame & 1 ? 0.8f : 0.9f;
            const GLfloat vertices[] = {-extent,-extent, extent,-extent,
                                        -extent,extent, extent,extent};
            const GLushort indices[] = {0,1,2,3};
            float gray = frame & 1 ? 0.5f : 0.75f;
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glUniform4f(color, gray, gray, gray, 1);
            if (!mode) {
                glClearColor(gray, gray, gray, 1);
                glClear(GL_COLOR_BUFFER_BIT);
            } else {
                glBindBuffer(GL_ARRAY_BUFFER, mode == 3 ? buffer : 0);
                if (mode == 3)
                    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STREAM_DRAW);
                glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0,
                                      mode == 3 ? NULL : vertices);
                if (mode == 2)
                    glDrawElements(GL_TRIANGLE_STRIP, 4, GL_UNSIGNED_SHORT, indices);
                else
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            }
            GLubyte pixel[4] = {0};
            glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            GLenum error = glGetError();
            int expected = frame & 1 ? 128 : 191;
            if (error || abs(pixel[0] - expected) > 2 ||
                abs(pixel[1] - expected) > 2 || abs(pixel[2] - expected) > 2) {
                if (++bad <= 3)
                    fprintf(stderr, "%s frame=%u pixel=%u,%u,%u expected=%d GL=%#x\n",
                            names[mode], frame, pixel[0], pixel[1], pixel[2], expected, error);
            }
            require(eglSwapBuffers(display, surface), "eglSwapBuffers");
        }
        printf("%s: %u/%u incorrect frames\n", names[mode], bad, FRAMES);
        failures += bad;
    }
    glDeleteBuffers(1, &buffer);
    glDeleteProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);
    XDestroyWindow(x11, window);
    XFreeColormap(x11, colormap);
    XCloseDisplay(x11);
    return failures ? 1 : 0;
}

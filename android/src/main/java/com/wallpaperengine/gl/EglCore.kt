package com.wallpaperengine.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.SurfaceHolder

/** Minimal EGL wrapper for rendering GLES2 directly onto a `SurfaceHolder`'s Surface
 * (`WallpaperService` provides no `GLSurfaceView`). */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun init(holder: SurfaceHolder): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            release()
            return false
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            release()
            return false
        }
        val config = configs[0]
        if (config == null) {
            release()
            return false
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            release()
            return false
        }

        surface = try {
            EGL14.eglCreateWindowSurface(display, config, holder, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: Exception) {
            EGL14.EGL_NO_SURFACE
        }
        if (surface == EGL14.EGL_NO_SURFACE) {
            release()
            return false
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            release()
            return false
        }
        return true
    }

    /** EGL context binding is per-thread global state; with multiple engines alive on the same
     * thread (system picker preview + real engine), reassert before any GL call. */
    fun makeCurrent(): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
            return false
        }
        return EGL14.eglMakeCurrent(display, surface, surface, context)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(display, surface)
    }

    /** Tolerates partial state — `init()` failure paths call this too; destroying
     * `EGL_NO_SURFACE`/`EGL_NO_CONTEXT` is harmless. */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }
}

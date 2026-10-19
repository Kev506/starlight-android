package com.starlight.utils

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Created by Kevin Andrews on 15/06/2026.
 */

data class GpuInfo(
    val renderer: String,
    val vendor: String,
    val version: String
)

fun fetchDeviceGpuInfo(): GpuInfo {
    val eglDisplay: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
        return GpuInfo("Unknown", "Unknown", "Unknown")
    }

    val version = IntArray(2)
    EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

    val attribList = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_NONE
    )
    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
    val config = configs[0]

    val ctxAttribList = intArrayOf(
        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL14.EGL_NONE
    )
    val eglContext: EGLContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribList, 0)

    val pbufferAttribList = intArrayOf(
        EGL14.EGL_WIDTH, 1,
        EGL14.EGL_HEIGHT, 1,
        EGL14.EGL_NONE
    )
    val eglSurface: EGLSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribList, 0)

    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

    val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
    val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
    val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"

    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(eglDisplay, eglSurface)
    EGL14.eglDestroyContext(eglDisplay, eglContext)
    EGL14.eglTerminate(eglDisplay)

    return GpuInfo(renderer, vendor, glVersion)
}
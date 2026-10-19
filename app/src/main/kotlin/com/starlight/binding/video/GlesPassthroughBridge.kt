package com.starlight.binding.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES31
import android.view.Surface
import com.starlight.utils.LimeLog.info
import com.starlight.utils.LimeLog.severe
import com.starlight.utils.LimeLog.warning
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * With thanks to Bruno Martin
 * Created by https://github.com/linckosz
 * Refactored and updated by Kevin Andrews on 02/07/2026.
 *
 */

class GlesPassthroughBridge(private val context: Context) : OnFrameAvailableListener {

    private val lock = ReentrantLock()
    private val frameAvailableQueue = ArrayBlockingQueue<Unit>(1)

    // EGL State
    private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE

    // Shader Programs
    private var blitProgram = 0
    private var postProgram = 0

    // Textures and Framebuffers
    private var oesTextureId = 0 // Texture ID for the hardware decoder output
    private var fboTextureId = 0 // Standard 2D texture ID attached to the FBO
    private var fboId = 0 // Framebuffer Object ID for the first pass

    // Surface management
    private var surfaceTexture: SurfaceTexture? = null

    var decoderSurface: Surface? = null
        private set
    private val transformMatrix = FloatArray(16)

    // Handles for Pass 1 (Blit)
    private var blitUstmatrixhandle = 0
    private var blitApositionhandle = 0
    private var blitAtexcoordhandle = 0
    private var blitStexturehandle = 0

    // Handles for Pass 2 (Custom Shader)
    private var postApositionhandle = 0
    private var postAtexcoordhandle = 0
    private var postStexturehandle = 0

    // Allocate direct memory for the vertex buffer
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Resolution information
    private var streamWidth = 0
    private var streamHeight = 0
    private var displayWidth = 0
    private var displayHeight = 0

    init {
        vertexBuffer.put(VERTICES).position(0)
    }

    private fun loadAsset(fileName: String): String? {
        val sb = StringBuilder()
        try {
            context.assets.open(fileName).use { `is` ->
                BufferedReader(InputStreamReader(`is`)).use { reader ->
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        sb.append(line).append("\n")
                    }
                }
            }
        } catch (e: IOException) {
            checkGlError(TAG + ": Failed to load asset: " + fileName + " - " + e.message)
            return null
        }
        info("$TAG: Loaded asset: $fileName")
        return sb.toString()
    }

    private fun checkGlError(message: String?) {
        var error: Int
        while ((GLES31.glGetError().also { error = it }) != GLES31.GL_NO_ERROR) {
            severe("$TAG: glError $error: $message")
        }
    }

    fun initialize(targetSurface: Surface?, streamWidth: Int, streamHeight: Int) {

        lock.withLock {
            this@GlesPassthroughBridge.streamWidth = streamWidth
            this@GlesPassthroughBridge.streamHeight = streamHeight

            // Initialize EGL Display
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            // Configure EGL for OpenGL ES 3.0+
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            // Create EGL Context
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext =
                EGL14.eglCreateContext(
                    eglDisplay,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttribs,
                    0
                )


            // Create EGL Surface connected to the target display
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                configs[0],
                targetSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )

            if (eglSurface === EGL14.EGL_NO_SURFACE) {
                val error = EGL14.eglGetError()
                checkGlError("$TAG: eglCreateWindowSurface failed: $error")
                throw IllegalStateException("eglCreateWindowSurface failed with error $error")
            }

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                val error = EGL14.eglGetError()
                checkGlError("$TAG: eglMakeCurrent failed: $error")
                throw IllegalStateException("eglMakeCurrent failed with error $error")
            }

            // Disable VSync synchronization inside EGL to avoid adding latency
            EGL14.eglSwapInterval(eglDisplay, 0)

            // Query the actual dimensions of the display surface
            val queryW = IntArray(1)
            val queryH = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, queryW, 0)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, queryH, 0)
            this@GlesPassthroughBridge.displayWidth = queryW[0]
            this@GlesPassthroughBridge.displayHeight = queryH[0]

            // 1. Setup Blit Program (OES -> 2D Texture)
            blitProgram = createProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
            blitUstmatrixhandle = GLES31.glGetUniformLocation(blitProgram, "uSTMatrix")
            blitApositionhandle = GLES31.glGetAttribLocation(blitProgram, "aPosition")
            blitAtexcoordhandle = GLES31.glGetAttribLocation(blitProgram, "aTexCoord")
            blitStexturehandle = GLES31.glGetUniformLocation(blitProgram, "sTexture")

            // 2. Setup Post-Process Program (Custom Shader)
            setupPostProgram()

            // 3. Generate Textures
            val textures = IntArray(2)
            GLES31.glGenTextures(2, textures, 0)
            oesTextureId = textures[0]
            fboTextureId = textures[1]

            // Configure OES Texture (used by MediaCodec)
            GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES31.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_MIN_FILTER,
                GLES31.GL_NEAREST.toFloat()
            )
            GLES31.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_MAG_FILTER,
                GLES31.GL_NEAREST.toFloat()
            )
            GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_WRAP_S,
                GLES31.GL_CLAMP_TO_EDGE
            )
            GLES31.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES31.GL_TEXTURE_WRAP_T,
                GLES31.GL_CLAMP_TO_EDGE
            )

            // Configure Standard 2D Texture (used as FBO color attachment)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, fboTextureId)
            GLES31.glTexImage2D(
                GLES31.GL_TEXTURE_2D,
                0,
                GLES31.GL_RGBA,
                streamWidth,
                streamHeight,
                0,
                GLES31.GL_RGBA,
                GLES31.GL_UNSIGNED_BYTE,
                null
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MIN_FILTER,
                GLES31.GL_LINEAR
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_MAG_FILTER,
                GLES31.GL_LINEAR
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_WRAP_S,
                GLES31.GL_CLAMP_TO_EDGE
            )
            GLES31.glTexParameteri(
                GLES31.GL_TEXTURE_2D,
                GLES31.GL_TEXTURE_WRAP_T,
                GLES31.GL_CLAMP_TO_EDGE
            )

            // Setup Framebuffer Object (FBO) for Pass 1
            val fbos = IntArray(1)
            GLES31.glGenFramebuffers(1, fbos, 0)
            fboId = fbos[0]
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fboId)
            GLES31.glFramebufferTexture2D(
                GLES31.GL_FRAMEBUFFER,
                GLES31.GL_COLOR_ATTACHMENT0,
                GLES31.GL_TEXTURE_2D,
                fboTextureId,
                0
            )


            // Ensure the Framebuffer is complete
            val status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER)
            if (status != GLES31.GL_FRAMEBUFFER_COMPLETE) {
                checkGlError("$TAG: Framebuffer incomplete: $status")
            }

            // Unbind FBO
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)

            // Create SurfaceTexture to receive hardware decoded frames
            surfaceTexture = SurfaceTexture(oesTextureId)
            surfaceTexture!!.setOnFrameAvailableListener(this@GlesPassthroughBridge)
            decoderSurface = Surface(surfaceTexture)


            // IMPORTANT: Unbind context so it can be picked up by the rendering coroutine thread!
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            info(
                TAG + ": GLES 3.1 2-Pass Bridge initialized: Stream=" + streamWidth + "x" + streamHeight + " Display=" + displayWidth + "x" + displayHeight
            )
        }
    }

    private fun setupPostProgram() {
        if (postProgram != 0) {
            GLES31.glDeleteProgram(postProgram)
            postProgram = 0
        }

        var postSource = loadAsset("sgsr1_shader_mobile_edge_direction.frag")

        // Define stream size resolutions constants directly into the GLSL Code at compile time
        // This leverages "Constant Folding", an optimization where the GPU calculates all
        // resolutions and math functions explicitly at compile time instead of per-frame.
        val compileTimeDefines = "\n#define SRC_W " + streamWidth + ".0\n" +
                "#define SRC_H " + streamHeight + ".0\n" +
                "#define INV_SRC_W " + (1.0f / streamWidth) + "\n" +
                "#define INV_SRC_H " + (1.0f / streamHeight) + "\n"

        if (postSource == null || postSource.trim { it <= ' ' }.isEmpty()) {
            warning("$TAG: sgsr1_shader_mobile_edge_direction.frag is missing or empty. Using fallback shader.")
            postSource = FALLBACK_POST_FRAGMENT_SHADER
        } else {
            // Inject the dynamic resolutions into the source code of the Fragment Shader.
            postSource =
                postSource.replaceFirst("(#version 310 es)".toRegex(), "$1$compileTimeDefines")
        }

        // Vertex shader for the final post-processing pass
        // It has been highly optimized to pre-calculate image coordinates across the 4 corners
        // of the vertex, allowing the hardware interpolator to naturally pass the coordinate
        // per-fragment down into the pipeline for completely free calculations.
        val postVertexShader =
            "#version 310 es\n" +
                    compileTimeDefines +
                    "in vec4 aPosition;\n" +
                    "in vec4 aTexCoord;\n" +
                    "out vec2 v_texCoord;\n" +
                    "out vec2 v_imgCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = aPosition;\n" +
                    "  v_texCoord = aTexCoord.xy;\n" +
                    "  v_imgCoord = (aTexCoord.xy * vec2(SRC_W, SRC_H)) + vec2(-0.5, 0.5);\n" +
                    "}\n"

        postProgram = createProgram(postVertexShader, postSource)
        postApositionhandle = GLES31.glGetAttribLocation(postProgram, "aPosition")
        postAtexcoordhandle = GLES31.glGetAttribLocation(postProgram, "aTexCoord")


        // Find sampler uniform (supports different naming conventions for flexibility)
        postStexturehandle = GLES31.glGetUniformLocation(postProgram, "sTexture")
        if (postStexturehandle == -1) postStexturehandle =
            GLES31.glGetUniformLocation(postProgram, "uTexture")
        if (postStexturehandle == -1) postStexturehandle =
            GLES31.glGetUniformLocation(postProgram, "Source")
        if (postStexturehandle == -1) postStexturehandle =
            GLES31.glGetUniformLocation(postProgram, "ps0") // Used by SGSR1
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailableQueue.clear()
        frameAvailableQueue.offer(Unit)
    }

    fun renderFrame(presentationTimeNanos: Long) {
        // Wait for a new frame to be available from the decoder
        frameAvailableQueue.poll(50, TimeUnit.MILLISECONDS) ?: return

        lock.withLock {
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) return@withLock

            // Ensure the EGL context is current on the rendering coroutine thread
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                val error = EGL14.eglGetError()
                checkGlError("$TAG: eglMakeCurrent failed: $error")
                return@withLock
            }

            try {

                // Consume the new frame from the decoder
                surfaceTexture!!.updateTexImage()
                surfaceTexture!!.getTransformMatrix(transformMatrix)

                // ==========================================================
                // PASS 1: Decode to 2D Texture (FBO)
                // ==========================================================

                // Bind the FBO to render off-screen
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, fboId)
                GLES31.glViewport(0, 0, streamWidth, streamHeight)
                GLES31.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

                GLES31.glUseProgram(blitProgram)


                // Pass the SurfaceTexture transform matrix to correct orientation
                if (blitUstmatrixhandle >= 0) GLES31.glUniformMatrix4fv(
                    blitUstmatrixhandle,
                    1,
                    false,
                    transformMatrix,
                    0
                )
                if (blitStexturehandle >= 0) GLES31.glUniform1i(blitStexturehandle, 0)

                // Bind the hardware OES texture
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
                GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)


                // Draw a full-screen quad to execute the blit
                drawQuad(blitApositionhandle, blitAtexcoordhandle)

                // ==========================================================
                // PASS 2: Post-Process to Screen
                // ==========================================================

                // Bind the default framebuffer to render to the display
                GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0)
                GLES31.glViewport(0, 0, displayWidth, displayHeight)
                GLES31.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)

                GLES31.glUseProgram(postProgram)

                if (postStexturehandle >= 0) GLES31.glUniform1i(postStexturehandle, 0)

                // Bind the 2D texture populated during Pass 1
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, fboTextureId)

                // Draw the final post-processed image
                drawQuad(postApositionhandle, postAtexcoordhandle)


                // Present the frame to the display
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            } finally {

                // IMPORTANT: Unbind context so it can be picked up by the rendering coroutine thread!
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
        }
    }

    private fun drawQuad(posHandle: Int, texHandle: Int) {
        if (posHandle >= 0) {
            GLES31.glEnableVertexAttribArray(posHandle)
            vertexBuffer.position(0)
            // Each vertex is 4 floats: [PosX, PosY, TexU, TexV]. Stride = 4 * 4 bytes = 16.
            GLES31.glVertexAttribPointer(posHandle, 2, GLES31.GL_FLOAT, false, 16, vertexBuffer)
        }
        if (texHandle >= 0) {
            GLES31.glEnableVertexAttribArray(texHandle)
            vertexBuffer.position(2) // Offset to start at TexU
            GLES31.glVertexAttribPointer(texHandle, 2, GLES31.GL_FLOAT, false, 16, vertexBuffer)
        }

        // Execute the draw call
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)

        // Cleanup attribute bindings
        if (posHandle >= 0) GLES31.glDisableVertexAttribArray(posHandle)
        if (texHandle >= 0) GLES31.glDisableVertexAttribArray(texHandle)
    }

    private fun createProgram(vertexSource: String?, fragmentSource: String?): Int {
        val vertexShader = loadShader(GLES31.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES31.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        var program = GLES31.glCreateProgram()
        if (program != 0) {
            GLES31.glAttachShader(program, vertexShader)
            GLES31.glAttachShader(program, fragmentShader)
            GLES31.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] != GLES31.GL_TRUE) {
                checkGlError("$TAG: Could not link program: " + GLES31.glGetProgramInfoLog(program))
                GLES31.glDeleteProgram(program)
                program = 0
            }
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String?): Int {
        var shader = GLES31.glCreateShader(type)
        GLES31.glShaderSource(shader, shaderCode)
        GLES31.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            checkGlError("$TAG: Could not compile shader: " + GLES31.glGetShaderInfoLog(shader))
            GLES31.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    fun release() {
        lock.withLock {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            if (surfaceTexture != null) {
                surfaceTexture!!.release()
            }
            if (decoderSurface != null) {
                decoderSurface!!.release()
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        info("$TAG: GLES 3.1 2-Pass Bridge released")
    }

    companion object {
        private const val TAG = "GlesBridge"

        // --- PASS 1: OES to 2D Texture ---
        // Vertex shader for the first pass (hardware decode to FBO)
        private const val BLIT_VERTEX_SHADER = "#version 310 es\n" +
                "uniform mat4 uSTMatrix;\n" +
                "in vec4 aPosition;\n" +
                "in vec4 aTexCoord;\n" +
                "out vec2 vTexCoord;\n" +
                "void main() {\n" +
                "  gl_Position = aPosition;\n" +
                "  vTexCoord = (uSTMatrix * aTexCoord).xy;\n" +  // Apply the SurfaceTexture transform matrix
                "}\n"

        // Fragment shader for the first pass (reads from OES texture)
        private const val BLIT_FRAGMENT_SHADER = "#version 310 es\n" +
                "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                "precision mediump float;\n" +
                "in vec2 vTexCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "  fragColor = texture(sTexture, vTexCoord);\n" +
                "}\n"

        // Fallback shader used if the external custom shader (e.g., SGSR) fails to load
        private const val FALLBACK_POST_FRAGMENT_SHADER = "#version 310 es\n" +
                "precision mediump float;\n" +
                "in vec2 v_texCoord;\n" +
                "in vec2 v_imgCoord;\n" +
                "uniform sampler2D sTexture;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "  fragColor = texture(sTexture, v_texCoord);\n" +
                "}\n"

        // Full-screen quad vertices (Position X, Y, Texture X, Y)
        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,  // Bottom Left
            1.0f, -1.0f, 1.0f, 0.0f,  // Bottom Right
            -1.0f, 1.0f, 0.0f, 1.0f,  // Top Left
            1.0f, 1.0f, 1.0f, 1.0f,  // Top Right
        )
    }
}

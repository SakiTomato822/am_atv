package dev.amatv.flyme

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** RC29's TextureView and worker-thread EGL pipeline, with a single welcome shader pass. */
internal class FolioRibbonBackdropView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val worker = RibbonRenderWorker()
    private var lastFrame: WelcomeRibbonFrame? = null
    private var lastRunning = false

    init {
        isOpaque = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        surfaceTextureListener = this
    }

    fun update(frame: WelcomeRibbonFrame, running: Boolean) {
        if (frame == lastFrame && running == lastRunning) return
        lastFrame = frame
        lastRunning = running
        worker.update(frame, running)
    }

    private fun bufferSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, 480f / width.coerceAtLeast(1))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = bufferSize(width, height)
        worker.attach(surface, w, h, width)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = bufferSize(width, height)
        worker.resize(surface, w, h, width)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        worker.detach(surface)
        // The worker releases the texture after EGL stops using it.
        return false
    }

    fun release() = worker.close()
}

private class RibbonRenderWorker {
    private val thread = HandlerThread("AMATV-ribbons", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val choreographer by lazy { Choreographer.getInstance() }
    @Volatile private var closed = false
    private var surface: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var viewWidth = 1
    private var egl: RibbonEglSession? = null
    private lateinit var parameters: WelcomeRibbonFrame
    private var running = false
    private var scheduled = false
    private var previousFrame = 0L
    private var nextRenderNanos = 0L
    private var elapsedNanos = 0L

    private val frame = Choreographer.FrameCallback { timestamp ->
        scheduled = false
        val session = egl
        if (!closed && running && session != null) {
            if (nextRenderNanos != 0L && timestamp + VSYNC_TOLERANCE < nextRenderNanos) {
                requestFrame()
                return@FrameCallback
            }
            nextRenderNanos = if (nextRenderNanos == 0L) timestamp + FRAME_INTERVAL else
                maxOf(nextRenderNanos + FRAME_INTERVAL, timestamp + FRAME_INTERVAL / 2)
            if (previousFrame != 0L) elapsedNanos += timestamp - previousFrame
            previousFrame = timestamp
            session.draw(parameters, (elapsedNanos % PHASE_CYCLE_NANOS) / 1_000_000_000f, viewWidth)
            requestFrame()
        }
    }

    private fun requestFrame() {
        if (!scheduled && !closed && running && egl != null) {
            scheduled = true
            choreographer.postFrameCallback(frame)
        }
    }

    fun update(parameters: WelcomeRibbonFrame, running: Boolean) = handler.post {
        if (closed) return@post
        this.parameters = parameters
        this.running = running
        if (running) startSession() else stopFrames()
    }

    fun attach(texture: SurfaceTexture, width: Int, height: Int, viewWidth: Int) = handler.post {
        if (closed) return@post
        destroySession()
        surface = texture
        this.width = width
        this.height = height
        this.viewWidth = viewWidth
        if (running) startSession()
    }

    fun resize(texture: SurfaceTexture, width: Int, height: Int, viewWidth: Int) = handler.post {
        if (closed || surface !== texture) return@post
        this.width = width
        this.height = height
        this.viewWidth = viewWidth
        if (running) startSession()
    }

    private fun startSession() {
        val texture = surface ?: return
        val session = egl
        if (session == null) egl = RibbonEglSession(texture, width, height) else session.resize(width, height)
        requestFrame()
    }

    fun detach(texture: SurfaceTexture) {
        if (!handler.post {
            if (surface === texture) {
                destroySession()
                surface = null
            }
            texture.release()
        }) texture.release()
    }

    fun close() {
        closed = true
        handler.post {
            destroySession()
            surface = null
            thread.quitSafely()
        }
    }

    private fun stopFrames() {
        if (scheduled) choreographer.removeFrameCallback(frame)
        scheduled = false
        previousFrame = 0L
        nextRenderNanos = 0L
    }

    private fun destroySession() {
        stopFrames()
        egl?.release()
        egl = null
    }

    private companion object {
        const val FRAME_INTERVAL = 1_000_000_000L / 30
        const val VSYNC_TOLERANCE = 250_000L
        // Common multiple of the four ribbon periods, so wrapping time preserves every phase.
        const val PHASE_CYCLE_NANOS = 5_040_000_000_000L
    }
}

private class RibbonEglSession(private val texture: SurfaceTexture, width: Int, height: Int) {
    private var bufferWidth = width
    private var bufferHeight = height
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val configuration: EGLConfig
    private val context: EGLContext
    private var surface = EGL14.EGL_NO_SURFACE
    private val surfaceSize = IntArray(2)
    private val window = Surface(texture)
    private val renderer: RibbonGlRenderer

    init {
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        configuration = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, configuration, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        createWindowSurface(width, height)
        renderer = RibbonGlRenderer()
    }

    private fun createWindowSurface(width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
        surface = EGL14.eglCreateWindowSurface(display, configuration, window, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        check(EGL14.eglSwapInterval(display, 1))
    }

    fun resize(width: Int, height: Int) {
        if (width == bufferWidth && height == bufferHeight) return
        // Like RC29, recreate the window surface so the new default buffer size takes effect.
        destroyWindowSurface()
        createWindowSurface(width, height)
        bufferWidth = width
        bufferHeight = height
    }

    fun draw(parameters: WelcomeRibbonFrame, elapsedSeconds: Float, viewWidth: Int) {
        check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, surfaceSize, 0))
        check(EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, surfaceSize, 1))
        renderer.draw(surfaceSize[0], surfaceSize[1], parameters, elapsedSeconds, viewWidth)
        check(EGL14.eglSwapBuffers(display, surface)) { "EGL swap failed: ${EGL14.eglGetError()}" }
    }

    private fun destroyWindowSurface() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        renderer.release()
        destroyWindowSurface()
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        window.release()
    }
}

private class RibbonGlRenderer {
    private val vertices = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }
    private val program = linkProgram()
    private val time = GLES20.glGetUniformLocation(program, "seconds")
    private val base = GLES20.glGetUniformLocation(program, "base")
    private val primary = GLES20.glGetUniformLocation(program, "primary")
    private val secondary = GLES20.glGetUniformLocation(program, "secondary")
    private val motion = GLES20.glGetUniformLocation(program, "motion")

    init {
        GLES20.glDisable(GLES20.GL_DITHER)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertices)
    }

    fun draw(width: Int, height: Int, frame: WelcomeRibbonFrame, seconds: Float, viewWidth: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUniform1f(time, seconds)
        GLES20.glUniform3f(base, frame.base.red, frame.base.green, frame.base.blue)
        GLES20.glUniform3f(primary, frame.primary.red, frame.primary.green, frame.primary.blue)
        GLES20.glUniform3f(secondary, frame.secondary.red, frame.secondary.green, frame.secondary.blue)
        GLES20.glUniform4f(motion, frame.gatherScale, frame.ridgeAlpha, ribbonStrength(frame.opacity),
            frame.dragOffsetPx * 0.25f / viewWidth)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() = GLES20.glDeleteProgram(program)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun linkProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glBindAttribLocation(program, 0, "a")
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private companion object {
        const val VERTEX = "attribute vec2 a; varying vec2 uv; void main(){uv=a*.5+.5;gl_Position=vec4(a,0.,1.);}"
        val FRAGMENT = """
            precision highp float;
            varying vec2 uv;
            uniform float seconds;
            uniform vec3 base, primary, secondary;
            uniform vec4 motion;

            vec4 ribbon(vec2 p, float height, float phase, float slope, float width,
                        float feather, float opacity, float period, float colorMix) {
                float t = 6.2831853 * seconds / period;
                float wave = height + slope * (p.x - .5)
                    + .098 * sin(5.4 * p.x + phase + t)
                    + .031 * sin(10. * p.x - phase - t)
                    + .024 * sin(t + phase);
                float d = (p.y - wave) / width;
                float body = 1. - smoothstep(1. - feather, 1. + .40 * feather, abs(d));
                float crest = 1. - smoothstep(0., .31 + .44 * feather, abs(d + .28));
                float light = (.64 + .36 * cos(5.3 * p.x + phase))
                    * mix(.46, 1., smoothstep(.10, .45, abs(p.x - .5)));
                float alpha = body * light * opacity * motion.z;
                vec3 color = mix(primary, secondary, colorMix) * (.76 + .24 * light);
                color = mix(color, vec3(.84, .87, .98), crest * (.14 + .22 * light + motion.y));
                return vec4(color * alpha, alpha);
            }

            void main() {
                vec2 origin = vec2(.62, .42);
                vec2 p = origin + (vec2(uv.x - motion.w, 1. - uv.y) - origin) / motion.x;
                vec4 result = vec4(base * $RIBBON_BASE_ALPHA, $RIBBON_BASE_ALPHA);
                vec4 band;
                ${welcomeRibbons.joinToString("\n") {
                    "band = ribbon(p, ${it.height}, ${it.phase}, ${it.slope}, ${it.width}, ${it.feather}, " +
                        "${it.opacity}, ${it.period}, ${it.colorMix}); result = band + result * (1. - band.a);"
                }}
                // Screen-locked interleaved gradient noise avoids temporal shimmer.
                float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(.06711056, .00583715))));
                gl_FragColor = vec4(result.rgb + (noise - .5) / 255., result.a);
            }
        """.trimIndent()
    }
}

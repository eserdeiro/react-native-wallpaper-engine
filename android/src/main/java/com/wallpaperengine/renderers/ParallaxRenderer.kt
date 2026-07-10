package com.wallpaperengine.renderers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import com.wallpaperengine.assets.BitmapUtils
import com.wallpaperengine.assets.ParallaxUnzipper
import com.wallpaperengine.config.WallpaperConfigStore
import com.wallpaperengine.gl.EglCore
import com.wallpaperengine.gl.STATIC_TEXTURE_FRAGMENT_SHADER
import com.wallpaperengine.gl.STATIC_TEXTURE_VERTEX_SHADER
import com.wallpaperengine.gl.buildProgram
import com.wallpaperengine.gl.floatBufferOf
import com.wallpaperengine.gl.queryMaxTextureSize
import com.wallpaperengine.gl.uploadBitmapAsTexture
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max

private const val LOW_PASS_ALPHA = 0.15f
/** Max offset (fraction of screen width) for the highest z-order layer. */
private const val MAX_OFFSET_FRACTION = 0.10f
/** Tilt angle (degrees) at which the normalized response reaches its max (clamped to ±1). */
private const val TILT_SENSITIVITY_DEGREES = 28f
private const val FRAME_INTERVAL_MS = 16L
/** Low-end devices: skip the draw call if the filtered tilt hasn't moved enough to change
 * anything visible (avoids redrawing ~60 times/sec while sitting still on a table). */
private const val TILT_EPSILON = 0.0015f

/** One layer already uploaded to GL: texture id + decoded bitmap size (post cap-to-max, needed
 * for the NDC math in `drawFrame`) + its z-order. */
private data class LayerTexture(val textureId: Int, val bitmapWidth: Int, val bitmapHeight: Int, val zOrder: Int)

/**
 * `parallax`: zip layers extracted by `ParallaxUnzipper`, translated by the rotation sensor
 * (accelerometer fallback) with a low-pass filter. Draws via GL (one textured quad per layer,
 * translated in NDC by tilt) — never `Canvas`, see `StaticTextureQuad.kt`.
 */
class ParallaxRenderer(private val context: Context) : WallpaperRenderer, SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Written on the main thread (SurfaceHolder/WallpaperEnginePreviewView callbacks), read on
    // the render thread in drawFrame().
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var isVisible = false

    private var layersDir: String? = null
    /** Last loaded `WallpaperConfigStore.KEY_CONFIG_VERSION_FIELD` — the applied path always
     * extracts to the same fixed dir name, and two different zips can produce an identical file
     * listing, so only this counter reliably identifies a new apply. Main-thread only. `-1` =
     * nothing loaded yet. */
    private var loadedConfigVersion = -1
    @Volatile private var layerTextures: List<LayerTexture> = emptyList()
    private var maxZOrder = 1

    private var egl: EglCore? = null
    private var programId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1
    private var uAlphaLoc = -1
    private var maxTextureSize = 0

    // Reused by drawFrame (render-thread only) to avoid allocating a FloatBuffer per layer per
    // frame. UVs (positions 2-3 of each vertex) are constant; the layer loop only rewrites NDC.
    private val layerVertexArray = floatArrayOf(
        0f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
        0f, 0f, 1f, 1f
    )
    private val layerVertexBuffer: FloatBuffer = floatBufferOf(layerVertexArray)

    // Written by onSensorChanged (delivered on renderHandler, see registerSensor), read by
    // drawFrame (also renderThread).
    @Volatile private var filteredTiltX = 0f
    @Volatile private var filteredTiltY = 0f

    // Preallocated for onSensorChanged (~50Hz at SENSOR_DELAY_GAME) — events are serialized on
    // one thread, no race.
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Dirty-flag: written from the main thread (loadLayers posting, onVisibilityChanged), read
    // and cleared on renderThread.
    @Volatile private var needsRedraw = true
    private var lastDrawnTiltX = 0f
    private var lastDrawnTiltY = 0f

    private val renderThread = HandlerThread("WallpaperEngine-Parallax").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private var loopRunning = false

    private val drawRunnable = object : Runnable {
        override fun run() {
            drawFrame()
            if (isVisible) {
                renderHandler.postDelayed(this, FRAME_INTERVAL_MS)
            } else {
                loopRunning = false
            }
        }
    }

    override fun onSurfaceCreated(holder: SurfaceHolder, width: Int, height: Int) {
        this.holder = holder
        this.width = width
        this.height = height
        // Same Handler, FIFO: ensureGlInitialized always runs before any later loadLayers.
        renderHandler.post { ensureGlInitialized(holder) }
        loadLayersIfNeeded()
    }

    private fun ensureGlInitialized(surfaceHolder: SurfaceHolder) {
        if (egl != null) return
        val core = EglCore()
        if (!core.init(surfaceHolder)) {
            return
        }
        val program = buildProgram(STATIC_TEXTURE_VERTEX_SHADER, STATIC_TEXTURE_FRAGMENT_SHADER)
        if (program == 0) {
            core.release()
            return
        }
        egl = core
        programId = program
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        maxTextureSize = queryMaxTextureSize()
    }

    override fun onConfigUpdated(config: JSONObject) {
        val newDir = config.optString("layersDir").ifEmpty { null }
        if (newDir == null) {
            return
        }

        // `configVersion` (WallpaperConfigStore counter, unique per real apply) is the only
        // reliable way to detect a new apply here: the applied path always extracts to the same
        // fixed dir name, and two different zips can produce an identical file listing. The
        // in-app preview path never goes through WallpaperConfigStore (no configVersion, -1) —
        // there the dir name itself is enough (namespaced per source asset).
        val configVersion = config.optInt(WallpaperConfigStore.KEY_CONFIG_VERSION_FIELD, -1)
        val alreadyLoaded = if (configVersion != -1) {
            configVersion == loadedConfigVersion
        } else {
            newDir == layersDir
        }
        if (alreadyLoaded) {
            return
        }
        loadedConfigVersion = configVersion
        layersDir = newDir

        val order = mutableMapOf<String, Int>()
        config.optJSONArray("layers")?.let { array ->
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                order[entry.getString("fileName")] = entry.getInt("zOrder")
            }
        }
        // Posted to renderThread so it's serialized with drawFrame() (see loadLayers).
        renderHandler.post { loadLayers(newDir, order) }
    }

    private fun loadLayersIfNeeded() {
        val dir = layersDir ?: run {
            return
        }
        if (layerTextures.isNotEmpty()) return
        renderHandler.post { loadLayers(dir, emptyMap()) }
    }

    /** Only ever invoked posted on renderHandler, never directly from the caller thread — keeps
     * the texture delete + reassignment serialized with drawFrame() on the same render thread. */
    private fun loadLayers(dir: String, orderHint: Map<String, Int>) {
        val core = egl
        if (core == null) {
            return
        }
        if (!core.makeCurrent()) {
            return
        }
        deleteLayerTextures()
        if (width == 0 || height == 0) {
            return
        }

        // Same filter/z-order derivation as ParallaxUnzipper.parseLayers (fallback path, used
        // when onConfigUpdated ran before width/height were known).
        val files = (File(dir).listFiles { f -> f.isFile } ?: emptyArray())
            .filter { ParallaxUnzipper.isRenderableLayer(it.name) }
            .sortedBy { it.name }
        // Decoded with extra margin over screen size so sensor translation never reveals an
        // empty edge.
        val targetW = width + (width * MAX_OFFSET_FRACTION * 2).toInt()
        val targetH = height + (height * MAX_OFFSET_FRACTION * 2).toInt()

        layerTextures = files.mapIndexedNotNull { index, f ->
            val zOrder = orderHint[f.name] ?: ParallaxUnzipper.deriveZOrder(f.name) ?: index
            val bitmap = BitmapUtils.decodeCoverBitmap(f.absolutePath, targetW, targetH)
            if (bitmap == null) {
                return@mapIndexedNotNull null
            }
            val bitmapWidth = bitmap.width
            val bitmapHeight = bitmap.height
            val textureId = uploadBitmapAsTexture(bitmap, maxTextureSize)
            LayerTexture(textureId, bitmapWidth, bitmapHeight, zOrder)
        }.sortedBy { it.zOrder }

        maxZOrder = max(1, layerTextures.maxOfOrNull { it.zOrder } ?: 1)
        needsRedraw = true
    }

    private fun deleteLayerTextures() {
        if (layerTextures.isNotEmpty()) {
            GLES20.glDeleteTextures(layerTextures.size, layerTextures.map { it.textureId }.toIntArray(), 0)
        }
        layerTextures = emptyList()
    }

    override fun onVisibilityChanged(visible: Boolean) {
        isVisible = visible
        if (visible) {
            registerSensor()
            loadLayersIfNeeded()
            // The compositor may have dropped the last frame while backgrounded.
            needsRedraw = true
            if (!loopRunning) {
                loopRunning = true
                renderHandler.post(drawRunnable)
            }
        } else {
            unregisterSensor()
        }
    }

    // Delivered on renderHandler (4th param) instead of the main looper: keeps the ~50Hz matrix
    // work off the main thread and on the same thread that consumes filteredTiltX/Y (drawFrame).
    private fun registerSensor() {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME, renderHandler)
            return
        }
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) {
            return
        }
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, renderHandler)
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (rawX, rawY) = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val tiltReference = Math.toRadians(TILT_SENSITIVITY_DEGREES.toDouble()).toFloat()
                (orientationAngles[2] / tiltReference) to (orientationAngles[1] / tiltReference)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Same relative sensitivity bump as the rotation-vector path (referenced to 45°
                // before, now to TILT_SENSITIVITY_DEGREES).
                val sensitivityScale = 45f / TILT_SENSITIVITY_DEGREES
                (-event.values[0] / SensorManager.GRAVITY_EARTH * sensitivityScale) to
                    (event.values[1] / SensorManager.GRAVITY_EARTH * sensitivityScale)
            }
            else -> return
        }

        val clampedX = rawX.coerceIn(-1f, 1f)
        val clampedY = rawY.coerceIn(-1f, 1f)

        // Low-pass filter: smooths sensor noise without losing responsiveness.
        filteredTiltX += LOW_PASS_ALPHA * (clampedX - filteredTiltX)
        filteredTiltY += LOW_PASS_ALPHA * (clampedY - filteredTiltY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun drawFrame() {
        val core = egl ?: return
        val surfaceHolder = holder ?: return
        if (!surfaceHolder.surface.isValid || layerTextures.isEmpty()) return

        // Dirty-flag: a device sitting still still produces sensor noise of a few thousandths,
        // so redrawing unconditionally would touch the canvas at ~60fps for nothing. Volatile
        // values are copied once to avoid rereading them per layer below.
        val tiltX = filteredTiltX
        val tiltY = filteredTiltY
        val tiltChanged = abs(tiltX - lastDrawnTiltX) > TILT_EPSILON ||
            abs(tiltY - lastDrawnTiltY) > TILT_EPSILON
        if (!needsRedraw && !tiltChanged) return
        needsRedraw = false
        lastDrawnTiltX = tiltX
        lastDrawnTiltY = tiltY

        if (!core.makeCurrent()) {
            return
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Real blending: parallax layers usually have transparent zones so layers behind show
        // through (Canvas.drawBitmap gave this for free before).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programId)
        GLES20.glUniform1f(uAlphaLoc, 1f)

        val maxOffsetPx = width * MAX_OFFSET_FRACTION
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        for (layer in layerTextures) {
            val depthFactor = layer.zOrder.toFloat() / maxZOrder.toFloat()
            val offsetX = -tiltX * maxOffsetPx * depthFactor
            val offsetY = -tiltY * maxOffsetPx * depthFactor
            val left = (width - layer.bitmapWidth) / 2f + offsetX
            val top = (height - layer.bitmapHeight) / 2f + offsetY
            val right = left + layer.bitmapWidth
            val bottom = top + layer.bitmapHeight

            // Pixels (top-left origin) -> NDC (-1..1, center origin, Y up).
            val ndcLeft = (left / width) * 2f - 1f
            val ndcRight = (right / width) * 2f - 1f
            val ndcTop = 1f - (top / height) * 2f
            val ndcBottom = 1f - (bottom / height) * 2f

            layerVertexArray[0] = ndcLeft; layerVertexArray[1] = ndcBottom
            layerVertexArray[4] = ndcRight; layerVertexArray[5] = ndcBottom
            layerVertexArray[8] = ndcLeft; layerVertexArray[9] = ndcTop
            layerVertexArray[12] = ndcRight; layerVertexArray[13] = ndcTop
            layerVertexBuffer.clear()
            layerVertexBuffer.put(layerVertexArray)
            layerVertexBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, layerVertexBuffer)
            layerVertexBuffer.position(2)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, layerVertexBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.textureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        core.swapBuffers()
    }

    override fun onSurfaceDestroyed() {
        isVisible = false
        unregisterSensor()
        renderHandler.removeCallbacksAndMessages(null)
        loopRunning = false
        // Posted so an in-flight drawFrame finishes before teardown.
        renderHandler.post { releaseGl() }
    }

    override fun onDestroy() {
        isVisible = false
        unregisterSensor()
        renderHandler.removeCallbacksAndMessages(null)
        // Posted so an in-flight drawFrame finishes before resources are torn down; quitSafely
        // runs after so the queued release still executes.
        renderHandler.post {
            releaseGl()
        }
        renderThread.quitSafely()
    }

    private fun releaseGl() {
        deleteLayerTextures()
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        egl?.release()
        egl = null
    }
}

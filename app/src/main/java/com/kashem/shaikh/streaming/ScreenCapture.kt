package com.kashem.shaikh.streaming

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.kashem.shaikh.services.AppLogs
import java.io.ByteArrayOutputStream

class ScreenCapture(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val onFrame: (ByteArray) -> Unit,
    private val onRotation: ((frameW: Int, frameH: Int, realW: Int, realH: Int) -> Unit)? = null
) {

    companion object {
        const val FRAME_INTERVAL_MS = 500L
        const val JPEG_QUALITY = 30
        const val TARGET_WIDTH = 540
    }

    @Volatile private var mediaProjection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayManager: DisplayManager? = null

    @Volatile private var running = false
    private var lastCapture = 0L
    private var frameCount = 0L
    private var frameErrorCount = 0L

    // ✅ FIX: @Volatile — main ↔ capture thread visibility
    @Volatile private var width = 540
    @Volatile private var height = 960
    private var density = 320

    @Volatile private var realWidth = 0
    @Volatile private var realHeight = 0

    val screenWidth: Int get() = width
    val screenHeight: Int get() = height
    val screenRealWidth: Int get() = realWidth
    val screenRealHeight: Int get() = realHeight

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppLogs.info("ScreenCapture: MediaProjection.onStop() called by system")
            running = false
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            if (!running) return
            try { handleRotation() }
            catch (e: Exception) { AppLogs.error("ScreenCapture: rotation handler failed", e) }
        }
    }

    private fun registerDisplayListener() {
        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager = dm
            dm.registerDisplayListener(displayListener, null)
            AppLogs.info("ScreenCapture: display listener registered")
        } catch (e: Exception) {
            AppLogs.warn("ScreenCapture: registerDisplayListener failed – ${e.message}")
        }
    }

    private fun unregisterDisplayListener() {
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        displayManager = null
    }

    fun start(): Boolean {
        if (running) {
            AppLogs.warn("ScreenCapture: already running")
            return false
        }

        return try {
            AppLogs.info("ScreenCapture: ========== START ==========")

            val metrics = DisplayMetrics()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val originalWidth = metrics.widthPixels
            val originalHeight = metrics.heightPixels
            val scale = TARGET_WIDTH.toFloat() / originalWidth.toFloat()

            width = TARGET_WIDTH
            height = (originalHeight * scale).toInt()
            density = metrics.densityDpi

            realWidth = originalWidth
            realHeight = originalHeight

            AppLogs.info(
                "ScreenCapture: capture=${width}x${height}, real=${realWidth}x${realHeight}, dpi=$density"
            )

            val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            mediaProjection = try { pm.getMediaProjection(resultCode, data) }
                              catch (e: Exception) {
                                  AppLogs.error("ScreenCapture: getMediaProjection threw", e)
                                  null
                              }
            if (mediaProjection == null) {
                AppLogs.error("ScreenCapture: getMediaProjection returned null")
                return false
            }
            AppLogs.info("ScreenCapture: ✓ MediaProjection obtained")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    mediaProjection?.registerCallback(projectionCallback, Handler(context.mainLooper))
                    AppLogs.info("ScreenCapture: ✓ Callback registered")
                } catch (e: Exception) {
                    AppLogs.error("ScreenCapture: registerCallback failed", e)
                }
            }

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            AppLogs.info("ScreenCapture: ✓ ImageReader created")

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            if (virtualDisplay == null) {
                AppLogs.error("ScreenCapture: VirtualDisplay is null")
                stop()
                return false
            }
            AppLogs.info("ScreenCapture: ✓ VirtualDisplay created")

            captureThread = HandlerThread("ScreenCaptureThread").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            frameCount = 0L
            frameErrorCount = 0L
            lastCapture = 0L
            running = true

            registerDisplayListener()

            scheduleNext()
            AppLogs.info(
                "ScreenCapture: ========== STARTED ${width}x${height} (real ${realWidth}x${realHeight}) =========="
            )
            true
        } catch (e: Exception) {
            AppLogs.error("ScreenCapture: start() exception", e)
            stop()
            false
        }
    }

    private fun scheduleNext() {
        captureHandler?.postDelayed({
            if (!running) return@postDelayed
            val now = System.currentTimeMillis()
            if (now - lastCapture >= FRAME_INTERVAL_MS) {
                lastCapture = now
                try { captureFrame() }
                catch (e: Exception) { AppLogs.error("captureFrame error", e) }
            }
            scheduleNext()
        }, FRAME_INTERVAL_MS / 2)
    }

    private fun captureFrame() {
        val reader = imageReader ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        var image: Image? = null
        var bmp: Bitmap? = null
        try {
            image = reader.acquireLatestImage() ?: return

            // ✅ FIX: plane count check
            if (image.planes.isEmpty()) return

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * w
            val paddedWidth = w + rowPadding / pixelStride

            bmp = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)

            val cropped = if (paddedWidth == w) bmp
                          else Bitmap.createBitmap(bmp, 0, 0, w, h)

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (cropped !== bmp) cropped.recycle()

            val bytes = out.toByteArray()
            frameCount++
            if (frameCount % 20 == 0L) {
                AppLogs.debug("ScreenCapture: frame #$frameCount (${bytes.size} bytes)")
            }
            onFrame(bytes)
        } catch (e: IllegalStateException) {
            AppLogs.warn("ScreenCapture: imageReader closed during capture")
        } catch (e: Exception) {
            frameErrorCount++
            if (frameErrorCount <= 5) {
                AppLogs.error("ScreenCapture: captureFrame error #$frameErrorCount", e)
            }
        } finally {
            try { bmp?.recycle() } catch (_: Exception) {}
            try { image?.close() } catch (_: Exception) {}
        }
    }

    // ✅ rotation — ImageReader + VirtualDisplay পুনর্নির্মাণ
    private fun handleRotation() {
        if (!running) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val newRealW = metrics.widthPixels
        val newRealH = metrics.heightPixels
        if (newRealW == realWidth && newRealH == realHeight) return

        val scale = TARGET_WIDTH.toFloat() / newRealW.toFloat()
        val newW = TARGET_WIDTH
        val newH = (newRealH * scale).toInt()

        AppLogs.info(
            "ScreenCapture: rotating ${realWidth}x${realHeight} → ${newRealW}x${newRealH}"
        )

        // Teardown old
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        // Update dims
        realWidth = newRealW
        realHeight = newRealH
        width = newW
        height = newH
        density = metrics.densityDpi

        // Recreate
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = newReader

        val newDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            newReader.surface, null, null
        )

        // ✅ FIX: createVirtualDisplay fail হলে rollback
        if (newDisplay == null) {
            AppLogs.error("ScreenCapture: rotation createVirtualDisplay failed — stopping")
            try { newReader.close() } catch (_: Exception) {}
            imageReader = null
            running = false
            return
        }

        virtualDisplay = newDisplay

        AppLogs.info(
            "ScreenCapture: rotated → capture=${width}x${height}, real=${realWidth}x${realHeight}"
        )

        try {
            onRotation?.invoke(width, height, realWidth, realHeight)
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!running && mediaProjection == null && virtualDisplay == null && imageReader == null) return
        AppLogs.info("ScreenCapture: ========== STOP ==========")
        AppLogs.info("ScreenCapture: total frames=$frameCount, errors=$frameErrorCount")

        running = false
        unregisterDisplayListener()

        try { captureHandler?.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        captureHandler = null
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { mediaProjection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        }

        try { virtualDisplay?.release() } catch (e: Exception) {
            AppLogs.warn("virtualDisplay.release: ${e.message}")
        }
        virtualDisplay = null

        try { imageReader?.close() } catch (e: Exception) {
            AppLogs.warn("imageReader.close: ${e.message}")
        }
        imageReader = null

        try { mediaProjection?.stop() } catch (e: Exception) {
            AppLogs.warn("mediaProjection.stop: ${e.message}")
        }
        mediaProjection = null

        AppLogs.info("ScreenCapture: stopped completely")
    }
}
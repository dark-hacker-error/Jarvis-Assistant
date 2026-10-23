package com.jarvis.assistant.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages background screen capture via Android's MediaProjection API.
 * Streams real-time downscaled JPEG frames to Gemini Live WebSocket
 * and supports on-demand high-resolution screen verification snapshots.
 */
class ScreenCaptureManager private constructor() {

    companion object {
        private const val TAG = "ScreenCaptureManager"

        @Volatile
        private var instance: ScreenCaptureManager? = null

        fun getInstance(): ScreenCaptureManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenCaptureManager().also { instance = it }
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var projectionCallback: MediaProjection.Callback? = null

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()
    private val _hasReceivedFrame = MutableStateFlow(false)
    val hasReceivedFrame: StateFlow<Boolean> = _hasReceivedFrame.asStateFlow()

    private var onFrameCallback: ((ByteArray) -> Unit)? = null
    var onStoppedListener: (() -> Unit)? = null
    private var lastFrameTime = 0L
    @Volatile
    private var lastBitmapTime = 0L
    private var minFrameIntervalMs = 1000L // 1 FPS keeps shared-screen context fresh while remaining lightweight
    private var framesSent = 0

    @Volatile
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()

    fun isScreenShareActive(): Boolean = _isSharing.value

    fun hasRecentFrame(maxAgeMs: Long = 2_000L): Boolean =
        _isSharing.value && _hasReceivedFrame.value &&
            lastBitmapTime > 0L && System.currentTimeMillis() - lastBitmapTime <= maxAgeMs

    @SuppressLint("WrongConstant")
    fun startCapture(
        projection: MediaProjection,
        metrics: DisplayMetrics,
        frameListener: ((ByteArray) -> Unit)? = null
    ): Boolean {
        if (_isSharing.value) {
            Log.w(TAG, "Screen capture is already active")
            return false
        }

        this.mediaProjection = projection
        this.onFrameCallback = frameListener
        this.framesSent = 0

        val originalWidth = metrics.widthPixels
        val originalHeight = metrics.heightPixels
        val density = metrics.densityDpi

        // Target 768px max dimension (Google's official specification for Gemini Multimodal Live API)
        val maxDimension = 768f
        val scale = (maxDimension / maxOf(originalWidth, originalHeight)).coerceAtMost(1.0f)
        val captureWidth = (originalWidth * scale).toInt() and 1.inv() // must be even
        val captureHeight = (originalHeight * scale).toInt() and 1.inv()

        Log.i(TAG, "Starting screen capture: ${captureWidth}x${captureHeight} (orig: ${originalWidth}x${originalHeight}) at density $density")

        backgroundThread = HandlerThread("JarvisScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // Mandatory on Android 14+ (API 34/35): Must register callback before createVirtualDisplay
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                stopCapture()
                onStoppedListener?.invoke()
            }
        }
        this.projectionCallback = callback
        return try {
            projection.registerCallback(callback, backgroundHandler)

            imageReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                handleImageAvailable(reader, captureWidth, captureHeight)
            }, backgroundHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "JarvisScreenMirror",
                captureWidth,
                captureHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Log.d(TAG, "VirtualDisplay paused")
                    }
                    override fun onResumed() {
                        Log.d(TAG, "VirtualDisplay resumed")
                    }
                    override fun onStopped() {
                        Log.d(TAG, "VirtualDisplay stopped")
                    }
                },
                backgroundHandler
            ) ?: throw IllegalStateException("Android did not create a screen capture display")

            _hasReceivedFrame.value = false
            _isSharing.value = true
            Log.i(TAG, "Screen capture display started; waiting for first frame")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start screen capture: ${e.message}", e)
            stopCapture()
            false
        }
    }

    private fun handleImageAvailable(reader: ImageReader, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        // Throttle bitmap creation to at most 2 FPS (500ms) to eliminate GC churn on 90Hz/120Hz displays
        if (now - lastBitmapTime < 500L && latestBitmap != null) {
            val img = reader.acquireLatestImage()
            img?.close()
            return
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            if (planes.isEmpty()) return

            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val cleanBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rowBytes = width * pixelStride

            if (rowPadding == 0 && buffer.remaining() >= width * height * pixelStride) {
                buffer.position(0)
                cleanBitmap.copyPixelsFromBuffer(buffer)
            } else {
                val rowBuffer = ByteArray(rowBytes)
                val destBuffer = ByteBuffer.allocate(width * height * 4)
                val totalRows = minOf(height, image.height)
                for (row in 0 until totalRows) {
                    val srcPos = row * rowStride
                    if (srcPos + rowBytes <= buffer.capacity()) {
                        buffer.position(srcPos)
                        buffer.get(rowBuffer, 0, rowBytes)
                        destBuffer.put(rowBuffer, 0, rowBytes)
                    }
                }
                destBuffer.rewind()
                cleanBitmap.copyPixelsFromBuffer(destBuffer)
            }

            synchronized(bitmapLock) {
                val old = latestBitmap
                latestBitmap = cleanBitmap
                old?.recycle()
                lastBitmapTime = now
            }
            _hasReceivedFrame.value = true

            // Check if we should stream this frame out to Gemini Live
            if (now - lastFrameTime >= minFrameIntervalMs) {
                lastFrameTime = now
                val jpegBytes = compressBitmapToJpeg(cleanBitmap, 70)
                framesSent++
                if (framesSent % 10 == 1) {
                    Log.i(TAG, "Streamed frame #$framesSent to Gemini (${jpegBytes.size / 1024} KB, ${cleanBitmap.width}x${cleanBitmap.height})")
                }
                onFrameCallback?.invoke(jpegBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image frame: ${e.message}", e)
        } finally {
            image?.close()
        }
    }

    /**
     * Captures a single current snapshot of the screen as JPEG bytes.
     * Ideal for immediate verification after an action was executed.
     */
    fun captureSnapshotJpeg(quality: Int = 70): ByteArray? {
        synchronized(bitmapLock) {
            val bmp = latestBitmap ?: return null
            if (bmp.isRecycled) return null
            lastFrameTime = System.currentTimeMillis()
            return compressBitmapToJpeg(bmp, quality)
        }
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    fun setStreamingInterval(intervalMs: Long) {
        this.minFrameIntervalMs = intervalMs.coerceAtLeast(300L)
    }

    fun stopCapture() {
        Log.i(TAG, "Stopping screen capture...")
        _isSharing.value = false
        _hasReceivedFrame.value = false
        onFrameCallback = null
        framesSent = 0

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display: ${e.message}")
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader: ${e.message}")
        }
        imageReader = null

        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering projection callback: ${e.message}")
        }
        projectionCallback = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection: ${e.message}")
        }
        mediaProjection = null

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        Log.i(TAG, "Screen capture stopped cleanly")
    }
}

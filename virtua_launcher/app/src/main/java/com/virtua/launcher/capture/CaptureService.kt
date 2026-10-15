package com.virtua.launcher.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service owning the MediaProjection → VirtualDisplay → ImageReader
 * chain. This is real screen capture, not a stub: it mirrors the composited
 * display into an ImageReader and exposes the last two frames as Bitmaps.
 *
 * What this does NOT do: intercept frames before the compositor (true
 * "between game & screen" capture needs an OEM hook or a SurfaceFlinger-level
 * trick that isn't portable across devices). This captures the final
 * on-screen output instead — the approach every third-party Android overlay
 * tool actually ships, since the alternative isn't accessible off-OEM.
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != 0 && data != null) {
            startCapture(resultCode, data)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mgr.getMediaProjection(resultCode, data)
        if (projection == null) {
            stopSelf()
            return
        }
        mediaProjection = projection

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val paddedWidth = width + rowPadding / pixelStride
                val raw = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(buffer)
                val framed = if (rowPadding == 0) raw
                else Bitmap.createBitmap(raw, 0, 0, width, height)

                pushFrame(framed)
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = projection.createVirtualDisplay(
            "virtua-capture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    private fun pushFrame(bitmap: Bitmap) {
        previousFrame.set(latestFrame.get())
        latestFrame.set(bitmap)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Virtua capture", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtua Launcher")
            .setContentText("Capturing frames for the pipeline")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        latestFrame.set(null)
        previousFrame.set(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "virtua_capture"
        private const val NOTIFICATION_ID = 42

        private val latestFrame = AtomicReference<Bitmap?>(null)
        private val previousFrame = AtomicReference<Bitmap?>(null)

        fun latest(): Bitmap? = latestFrame.get()
        fun previous(): Bitmap? = previousFrame.get()
        fun isCapturing(): Boolean = latestFrame.get() != null
    }
}

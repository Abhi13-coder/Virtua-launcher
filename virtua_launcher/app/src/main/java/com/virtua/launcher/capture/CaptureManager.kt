package com.virtua.launcher.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Wraps the real MediaProjection permission flow + CaptureService lifecycle
 * so an Activity only has to call requestCapture() / stopCapture(). Must be
 * constructed before the Activity reaches STARTED (registerForActivityResult
 * requirement) — construct it in onCreate.
 */
class CaptureManager(private val activity: AppCompatActivity) {

    var onDenied: (() -> Unit)? = null
    var onGranted: (() -> Unit)? = null

    private val projectionManager =
        activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                startService(result.resultCode, data)
                onGranted?.invoke()
            } else {
                onDenied?.invoke()
            }
        }

    fun requestCapture() {
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startService(resultCode: Int, data: Intent) {
        val intent = Intent(activity, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(activity, intent)
    }

    fun stopCapture() {
        activity.stopService(Intent(activity, CaptureService::class.java))
    }
}

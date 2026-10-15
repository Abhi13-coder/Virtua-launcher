package com.virtua.launcher.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.virtua.launcher.R
import com.virtua.launcher.VirtuaApp
import com.virtua.launcher.capture.CaptureManager
import com.virtua.launcher.capture.CaptureService
import com.virtua.launcher.core.MotionEstimator
import com.virtua.launcher.core.PipelineController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var featureList: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtBackend: TextView
    private lateinit var captureManager: CaptureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        featureList = findViewById(R.id.featureList)
        txtStatus = findViewById(R.id.txtStatus)
        txtBackend = findViewById(R.id.txtBackend)

        val app = VirtuaApp.instance
        txtBackend.text = app.backendSelector.label()

        // Must be constructed before STARTED — registerForActivityResult requirement.
        captureManager = CaptureManager(this).apply {
            onGranted = { runOnUiThread { txtStatus.text = "Capture running — frames flowing into the pipeline" } }
            onDenied = { runOnUiThread { txtStatus.text = "Capture permission denied" } }
        }

        bindFeatures()

        findViewById<MaterialButton?>(R.id.btnCapture)?.setOnClickListener {
            txtStatus.text = "Requesting capture permission…"
            captureManager.requestCapture()
        }

        // Real pipeline apply: runs the actual depth (MiDaS) and motion
        // (block-matching CompactFlow substitute) nets against whatever
        // CaptureService has captured, when those features are enabled.
        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener {
            val pipe = PipelineController(app.featureRegistry, app.backendSelector)
            val baseMsg = pipe.applyEnabled()

            val latest = CaptureService.latest()
            val previous = CaptureService.previous()

            if (latest == null) {
                txtStatus.text = "$baseMsg | no captured frame yet — tap Start Capture first"
                Toast.makeText(this, "Pipeline applied", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            txtStatus.text = "$baseMsg | running nets on captured frame…"
            lifecycleScope.launch {
                val extra = withContext(Dispatchers.Default) {
                    val parts = mutableListOf<String>()

                    if (app.featureRegistry.get("depth")?.enabled == true) {
                        val d = app.depthEstimator.estimate(latest)
                        parts += "depth avg=${"%.3f".format(d.average)} (${d.inferenceMs}ms)"
                    }
                    if (previous != null && app.featureRegistry.get("compactflow")?.enabled == true) {
                        val m = MotionEstimator.estimate(previous, latest)
                        parts += "motion avg=${"%.2f".format(m.avgMagnitudePx)}px conf=${"%.2f".format(m.avgConfidence)}"
                    }
                    if (app.featureRegistry.get("fsrcnn")?.enabled == true) {
                        if (app.superResEstimator.isModelAvailable()) {
                            val t0 = System.nanoTime()
                            val up = app.superResEstimator.upscale(latest, 64)
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            parts += if (up != null) "fsrcnn ${up.width}x${up.height} (${ms}ms)"
                            else "fsrcnn: upscale failed"
                        } else {
                            parts += "fsrcnn: model not converted yet (see README)"
                        }
                    }
                    parts
                }
                txtStatus.text = if (extra.isEmpty()) "$baseMsg | enable depth/compactflow to see live numbers"
                else "$baseMsg | " + extra.joinToString(" | ")
                Toast.makeText(this@MainActivity, "Pipeline applied", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnUpdates).setOnClickListener {
            txtStatus.text = "Checking stream updates…"
            lifecycleScope.launch {
                val result = app.updateManager.checkAndApply()
                txtStatus.text = result.message
                if (result.ok) {
                    bindFeatures()
                }
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }

        // Landscape-only controls (optional)
        findViewById<MaterialButton?>(R.id.btnCool)?.setOnClickListener {
            txtStatus.text = "Cool-down profile: reduce nets, spatial+HDR only"
        }
        findViewById<MaterialButton?>(R.id.btnPerf)?.setOnClickListener {
            txtStatus.text = "Perf lock: prefer GPU/NPU, cap resolution"
        }
    }

    private fun bindFeatures() {
        featureList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val reg = VirtuaApp.instance.featureRegistry
        for (f in reg.all()) {
            val row = inflater.inflate(R.layout.item_feature, featureList, false)
            row.findViewById<TextView>(R.id.txtName).text = f.spec.name
            row.findViewById<TextView>(R.id.txtMeta).text =
                "set ${f.spec.set} · v${f.spec.version} · ${if (f.bundled) "bundled" else "streamed"}"
            val sw = row.findViewById<SwitchMaterial>(R.id.swEnable)
            sw.isChecked = f.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                reg.setEnabled(f.spec.id, checked)
            }
            featureList.addView(row)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Capture keeps running in its own foreground service across Activity
        // destruction on purpose (e.g. screen rotation) — only stopped
        // explicitly, not tied to MainActivity's lifecycle.
    }
}

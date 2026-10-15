package com.virtua.launcher.features

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Discovers feature modules from assets + filesDir/features.
 * Stream updates install .so (+ sidecar json) without rebuilding the APK.
 */
class FeatureRegistry(private val context: Context) {

    private val featuresDir: File =
        File(context.filesDir, "features").also { if (!it.exists()) it.mkdirs() }

    private val local = linkedMapOf<String, LocalFeature>()

    fun all(): List<LocalFeature> = local.values.toList()

    fun get(id: String): LocalFeature? = local[id]

    fun setEnabled(id: String, enabled: Boolean) {
        local[id]?.enabled = enabled
        persistEnabled()
    }

    fun bootstrapBundled() {
        local.clear()
        // Built-in catalog (set 1 / 1b). Native .so may be stub until streamed.
        val catalog = listOf(
            FeatureSpec(
                id = "vpr",
                name = "VPR Upscale",
                version = "1.0.0",
                set = "1",
                abi = currentAbi(),
                soFile = "libvpr.so",
                sha256 = "bundled",
                description = "Virtual sub-pixel lattice + exact-slot accumulation"
            ),
            FeatureSpec(
                id = "compactflow",
                name = "CompactFlow Motion",
                version = "1.0.0",
                set = "1",
                abi = currentAbi(),
                soFile = "libcompactflow.so",
                sha256 = "bundled",
                description = "Block-matching motion + confidence (CompactFlowNet has no public weights yet)"
            ),
            FeatureSpec(
                id = "fsrcnn",
                name = "FSRCNN Spatial",
                version = "1.0.0",
                set = "1",
                abi = currentAbi(),
                soFile = "libfsrcnn.so",
                sha256 = "bundled",
                description = "Tiny SR prior for empty / rejected slots"
            ),
            FeatureSpec(
                id = "hdrplus",
                name = "Virtual HDR+",
                version = "1.0.0",
                set = "1",
                abi = currentAbi(),
                soFile = "libhdrplus.so",
                sha256 = "bundled",
                description = "Local tone + highlight roll-off + mild bloom"
            ),
            FeatureSpec(
                id = "rays",
                name = "Virtual Ray-Cast",
                version = "1.0.0",
                abi = currentAbi(),
                soFile = "librays.so",
                sha256 = "bundled",
                description = "God rays + light SSR when depth available",
                set = "1b"
            ),
            FeatureSpec(
                id = "depth",
                name = "Neural Depth",
                version = "1.0.0",
                abi = currentAbi(),
                soFile = "libdepth.so",
                sha256 = "bundled",
                description = "Real MiDaS v2.1-small TFLite inference (bundled, assets/models/midas_small.tflite)",
                set = "1b"
            )
        )
        val enabledMap = loadEnabled()
        for (spec in catalog) {
            val path = File(featuresDir, "${spec.id}/${spec.soFile}").absolutePath
            local[spec.id] = LocalFeature(
                spec = spec,
                enabled = enabledMap[spec.id] ?: spec.enabledDefault,
                path = path,
                bundled = true
            )
        }
        // Merge any streamed modules already on disk
        scanStreamed()
    }

    fun scanStreamed() {
        val manifest = File(featuresDir, "installed_manifest.json")
        if (!manifest.exists()) return
        try {
            val root = JSONObject(manifest.readText())
            val arr = root.optJSONArray("features") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val spec = FeatureSpec(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    version = o.getString("version"),
                    set = o.optString("set", "1"),
                    abi = o.optString("abi", currentAbi()),
                    soFile = o.getString("soFile"),
                    sha256 = o.optString("sha256", ""),
                    replaces = jsonStringList(o.optJSONArray("replaces")),
                    description = o.optString("description", "")
                )
                val dir = File(featuresDir, spec.id)
                val so = File(dir, spec.soFile)
                if (so.exists()) {
                    local[spec.id] = LocalFeature(
                        spec = spec,
                        enabled = loadEnabled()[spec.id] ?: true,
                        path = so.absolutePath,
                        bundled = false
                    )
                }
            }
        } catch (_: Exception) {
            // keep bundled catalog
        }
    }

    fun installStreamed(spec: FeatureSpec, soBytes: ByteArray) {
        // Auto-delete features this one replaces
        for (oldId in spec.replaces) {
            local.remove(oldId)
            File(featuresDir, oldId).deleteRecursively()
        }
        val dir = File(featuresDir, spec.id).also { it.mkdirs() }
        val so = File(dir, spec.soFile)
        so.writeBytes(soBytes)
        File(dir, "feature.json").writeText(
            JSONObject()
                .put("id", spec.id)
                .put("name", spec.name)
                .put("version", spec.version)
                .put("set", spec.set)
                .put("abi", spec.abi)
                .put("soFile", spec.soFile)
                .put("sha256", spec.sha256)
                .put("replaces", JSONArray(spec.replaces))
                .put("description", spec.description)
                .toString(2)
        )
        local[spec.id] = LocalFeature(
            spec = spec,
            enabled = true,
            path = so.absolutePath,
            bundled = false
        )
        rewriteInstalledManifest()
    }

    private fun rewriteInstalledManifest() {
        val arr = JSONArray()
        for (f in local.values.filter { !it.bundled }) {
            val s = f.spec
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("version", s.version)
                    .put("set", s.set)
                    .put("abi", s.abi)
                    .put("soFile", s.soFile)
                    .put("sha256", s.sha256)
                    .put("replaces", JSONArray(s.replaces))
                    .put("description", s.description)
            )
        }
        File(featuresDir, "installed_manifest.json")
            .writeText(JSONObject().put("features", arr).toString(2))
    }

    private fun persistEnabled() {
        val o = JSONObject()
        for ((id, f) in local) o.put(id, f.enabled)
        File(featuresDir, "enabled.json").writeText(o.toString())
    }

    private fun loadEnabled(): Map<String, Boolean> {
        val f = File(featuresDir, "enabled.json")
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { o.getBoolean(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    companion object {
        fun currentAbi(): String {
            val abis = android.os.Build.SUPPORTED_ABIS
            return when {
                abis.any { it.contains("arm64") } -> "arm64-v8a"
                abis.any { it.contains("armeabi") } -> "armeabi-v7a"
                abis.any { it.contains("x86_64") } -> "x86_64"
                else -> "x86"
            }
        }
    }
}

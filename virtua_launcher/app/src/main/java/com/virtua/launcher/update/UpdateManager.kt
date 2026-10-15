package com.virtua.launcher.update

import android.content.Context
import com.virtua.launcher.features.FeatureRegistry
import com.virtua.launcher.features.FeatureSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stream delivery:
 *  - Remote update_manifest.json lists feature modules (.so per ABI)
 *  - SHA-256 verify before install
 *  - JSON matcher decides newer version / replaces[]
 *  - Auto-delete old modules listed in replaces
 */
class UpdateManager(
    private val context: Context,
    private val registry: FeatureRegistry
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Default placeholder CDN path — change in settings / BuildConfig later. */
    var manifestUrl: String =
        "https://example.com/virtua/update_manifest.json"

    data class CheckResult(
        val ok: Boolean,
        val message: String,
        val installed: List<String> = emptyList()
    )

    suspend fun checkAndApply(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val body = httpGet(manifestUrl)
                ?: return@withContext CheckResult(false, "Manifest download failed")
            val root = JSONObject(body)
            val features = root.optJSONArray("features")
                ?: return@withContext CheckResult(false, "No features array")
            val abi = FeatureRegistry.currentAbi()
            val installed = mutableListOf<String>()

            for (i in 0 until features.length()) {
                val o = features.getJSONObject(i)
                val id = o.getString("id")
                val version = o.getString("version")
                val soName = o.getString("soFile")
                val sha = o.getString("sha256")
                val url = o.optString("url", "")
                val replaces = mutableListOf<String>()
                val rep = o.optJSONArray("replaces")
                if (rep != null) {
                    for (j in 0 until rep.length()) replaces.add(rep.getString(j))
                }
                val abis = o.optJSONObject("abis")
                val resolvedUrl = when {
                    abis != null && abis.has(abi) -> abis.getString(abi)
                    else -> url
                }
                val local = registry.get(id)
                if (local != null && !isNewer(version, local.spec.version) && local.spec.sha256 == sha) {
                    continue
                }
                val bytes = httpGetBytes(resolvedUrl)
                    ?: return@withContext CheckResult(false, "Download failed: $id")
                val dig = sha256(bytes)
                if (!sha.equals("bundled", true) && !dig.equals(sha, ignoreCase = true)) {
                    return@withContext CheckResult(false, "SHA mismatch for $id")
                }
                val spec = FeatureSpec(
                    id = id,
                    name = o.optString("name", id),
                    version = version,
                    set = o.optString("set", "1"),
                    abi = abi,
                    soFile = soName,
                    sha256 = sha,
                    replaces = replaces,
                    description = o.optString("description", "")
                )
                registry.installStreamed(spec, bytes)
                installed.add("$id@$version")
            }
            CheckResult(
                true,
                if (installed.isEmpty()) "Already up to date" else "Installed: ${installed.joinToString()}",
                installed
            )
        } catch (e: Exception) {
            CheckResult(false, e.message ?: "Update error")
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(local)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    fun writeSampleManifestToCache(): File {
        val sample = """
        {
          "appMinVersion": "1.0.0",
          "features": [
            {
              "id": "vpr",
              "name": "VPR Upscale",
              "version": "1.0.1",
              "set": "1",
              "soFile": "libvpr.so",
              "sha256": "REPLACE_WITH_REAL_SHA256",
              "url": "https://example.com/virtua/arm64-v8a/libvpr.so",
              "abis": {
                "arm64-v8a": "https://example.com/virtua/arm64-v8a/libvpr.so",
                "armeabi-v7a": "https://example.com/virtua/armeabi-v7a/libvpr.so"
              },
              "replaces": [],
              "description": "Virtual sub-pixel lattice"
            }
          ]
        }
        """.trimIndent()
        val f = File(context.cacheDir, "update_manifest.sample.json")
        f.writeText(sample)
        return f
    }
}

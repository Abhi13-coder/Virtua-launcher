package com.virtua.launcher.core

import com.virtua.launcher.features.FeatureRegistry
import com.virtua.launcher.nativeapi.VirtuaNative

/**
 * Feature set 1 / 1b pipeline order:
 * depth? → flow → VPR(slots+FSRCNN) → rays? → HDR+
 */
class PipelineController(
    private val registry: FeatureRegistry,
    private val backend: BackendSelector
) {
    fun applyEnabled(): String {
        val enabled = registry.all().filter { it.enabled }.map { it.spec.id }
        for (f in registry.all()) {
            try {
                VirtuaNative.nativeSetFeatureEnabled(f.spec.id, f.enabled)
            } catch (_: Throwable) {
            }
        }
        val init = try {
            VirtuaNative.nativePipelineInit()
        } catch (_: Throwable) {
            -1
        }
        val backendName = try {
            VirtuaNative.nativeBackendName()
        } catch (_: Throwable) {
            backend.label()
        }
        val stub = try {
            VirtuaNative.nativeProcessFrameStub(1280, 720)
        } catch (_: Throwable) {
            "cpu-fallback"
        }
        return "backend=$backendName init=$init features=${enabled.joinToString()} path=$stub"
    }
}

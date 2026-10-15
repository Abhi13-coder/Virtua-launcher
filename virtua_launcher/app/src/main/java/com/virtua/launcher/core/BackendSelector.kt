package com.virtua.launcher.core

import android.content.Context
import android.os.Build

/**
 * Adaptive compute path: NPU → GPU → CPU.
 * Actual NNAPI/Vulkan detection is best-effort; never fails the pipeline.
 */
class BackendSelector(private val context: Context) {

    enum class Backend { NPU, GPU, CPU }

    fun preferred(): Backend {
        // NNAPI availability is a weak proxy for NPU on API 27+
        if (Build.VERSION.SDK_INT >= 27) {
            return Backend.NPU
        }
        return Backend.GPU
    }

    fun chain(): List<Backend> = listOf(Backend.NPU, Backend.GPU, Backend.CPU)

    fun label(): String = when (preferred()) {
        Backend.NPU -> "Adaptive: NPU → GPU → CPU"
        Backend.GPU -> "Adaptive: GPU → CPU"
        Backend.CPU -> "Adaptive: CPU"
    }
}

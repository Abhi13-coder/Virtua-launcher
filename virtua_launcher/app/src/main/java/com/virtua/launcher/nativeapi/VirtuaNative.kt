package com.virtua.launcher.nativeapi

/**
 * JNI bridge to bundled / streamed native feature libs.
 * Stub implementations live in libvirtua_core.so until real nets are linked.
 */
object VirtuaNative {
    init {
        try {
            System.loadLibrary("virtua_core")
        } catch (_: UnsatisfiedLinkError) {
            // UI still runs; pipeline reports fallback
        }
    }

    external fun nativeBackendName(): String
    external fun nativePipelineInit(): Int
    external fun nativeSetFeatureEnabled(featureId: String, enabled: Boolean)
    external fun nativeProcessFrameStub(width: Int, height: Int): String

    /**
     * Real dense motion estimate (block-matching SAD search) between two
     * luma planes. Stands in for CompactFlowNet, which has no public code or
     * weights (arXiv 2412.13273, no release found as of Oct 2026).
     * Returns a flat [dx, dy, confidence] triple per block, row-major.
     */
    external fun nativeComputeMotionField(
        prevLuma: ByteArray,
        currLuma: ByteArray,
        width: Int,
        height: Int,
        blockSize: Int,
        searchRadius: Int
    ): FloatArray
}

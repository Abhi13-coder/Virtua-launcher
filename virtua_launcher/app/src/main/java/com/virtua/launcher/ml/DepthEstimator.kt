package com.virtua.launcher.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Real MiDaS v2.1-small inference. This is the one net in the pipeline that
 * actually has a public, mobile-ready artifact: the official isl-org/MiDaS
 * release at
 * https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite
 * bundled here at assets/models/midas_small.tflite.
 *
 * Verified directly against the bundled flatbuffer (not assumed from docs):
 *   input  tensor: [1, 256, 256, 3] float32, range [-1, 1]
 *   output tensor: [1, 256, 256, 1] float32 (relative depth, higher = closer)
 */
class DepthEstimator(private val context: Context) {

    data class Result(
        val width: Int,
        val height: Int,
        val depth: FloatArray,
        val average: Float,
        val inferenceMs: Long
    )

    private val interpreter: Interpreter by lazy {
        Interpreter(loadModelFile(), Interpreter.Options().apply { setNumThreads(4) })
    }

    private fun loadModelFile(): ByteBuffer {
        val afd = context.assets.openFd(MODEL_PATH)
        FileInputStream(afd.fileDescriptor).use { stream ->
            return stream.channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
        }
    }

    fun estimate(bitmap: Bitmap): Result {
        val start = System.nanoTime()
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 127.5f - 1f) // R
            input.putFloat(((p shr 8) and 0xFF) / 127.5f - 1f)  // G
            input.putFloat((p and 0xFF) / 127.5f - 1f)          // B
        }
        input.rewind()

        // Output shape is [1, 256, 256, 1] — the trailing 1 matters, dropping
        // it silently corrupts the TFLite output-copy at the native level.
        val output = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(1) } } }
        interpreter.run(input, output)

        val depth = FloatArray(INPUT_SIZE * INPUT_SIZE)
        var sum = 0f
        var idx = 0
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val v = output[0][y][x][0]
                depth[idx++] = v
                sum += v
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return Result(INPUT_SIZE, INPUT_SIZE, depth, sum / depth.size, elapsedMs)
    }

    companion object {
        private const val MODEL_PATH = "models/midas_small.tflite"
        private const val INPUT_SIZE = 256
    }
}

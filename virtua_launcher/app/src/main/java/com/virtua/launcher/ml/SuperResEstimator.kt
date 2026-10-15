package com.virtua.launcher.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Runs a converted FSRCNN model (suxrobGM/fsrcnn, Y-channel only, default
 * scale=4 d=56 s=12 m=4 -- see tools/convert_fsrcnn_to_tflite.py and the
 * "Convert FSRCNN" CI step for the exact export this expects).
 *
 * Checks two places, in order:
 *   1. Bundled asset: assets/models/fsrcnn_div2k.tflite -- what the CI
 *      workflow produces (clones suxrobGM/fsrcnn, exports to ONNX, converts
 *      with onnx2tf, drops the result here before the Gradle build).
 *   2. Streamed install: files/features/fsrcnn/libfsrcnn.tflite -- same
 *      path UpdateManager already uses for .so features, works for .tflite
 *      too since it's just bytes on disk.
 * If neither exists, isModelAvailable() is false and upscale() is a no-op --
 * this never crashes an app built before the CI conversion step has run.
 *
 * FSRCNN operates on the Y (luma) channel only: this handles YCbCr
 * conversion, runs the net on Y, and upsamples Cb/Cr with simple bilinear
 * scaling before recombining -- the standard FSRCNN inference pattern.
 */
class SuperResEstimator(private val context: Context, private val scale: Int = 4) {

    private val interpreter: Interpreter? by lazy {
        (loadFromAssets() ?: loadFromStreamed())?.let { Interpreter(it) }
    }

    fun isModelAvailable(): Boolean = interpreter != null

    private fun loadFromAssets(): ByteBuffer? = try {
        val afd = context.assets.openFd(ASSET_PATH)
        FileInputStream(afd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    } catch (_: IOException) {
        null
    }

    private fun loadFromStreamed(): MappedByteBuffer? {
        val file = File(context.filesDir, STREAMED_PATH)
        if (!file.exists()) return null
        return RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }

    /** inputSize is the LR patch side fed to the net; output is inputSize*scale. */
    fun upscale(bitmap: Bitmap, inputSize: Int): Bitmap? {
        val interp = interpreter ?: return null

        val lr = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        lr.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        val yIn = FloatArray(inputSize * inputSize)
        val cb = FloatArray(inputSize * inputSize)
        val cr = FloatArray(inputSize * inputSize)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            yIn[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            cb[i] = (-0.169f * r - 0.331f * g + 0.5f * b + 128f) / 255f
            cr[i] = (0.5f * r - 0.419f * g - 0.081f * b + 128f) / 255f
        }

        // Single-channel NHWC input: [1, inputSize, inputSize, 1]
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize)
            .order(ByteOrder.nativeOrder())
        for (v in yIn) input.putFloat(v)
        input.rewind()

        val outSize = inputSize * scale
        val output = Array(1) { Array(outSize) { Array(outSize) { FloatArray(1) } } }
        interp.run(input, output)

        // Upsample Cb/Cr with simple bilinear scaling to match the net's Y output.
        val outBitmap = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outSize * outSize)
        val cbHr = Bitmap.createScaledBitmap(colorArrayToBitmap(cb, inputSize), outSize, outSize, true)
        val crHr = Bitmap.createScaledBitmap(colorArrayToBitmap(cr, inputSize), outSize, outSize, true)

        var idx = 0
        for (y in 0 until outSize) {
            for (x in 0 until outSize) {
                val yVal = (output[0][y][x][0] * 255f).coerceIn(0f, 255f)
                val cbVal = (Color.red(cbHr.getPixel(x, y))).toFloat()
                val crVal = (Color.red(crHr.getPixel(x, y))).toFloat()

                val r = (yVal + 1.402f * (crVal - 128f)).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344f * (cbVal - 128f) - 0.714f * (crVal - 128f))
                    .toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * (cbVal - 128f)).toInt().coerceIn(0, 255)

                outPixels[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        outBitmap.setPixels(outPixels, 0, outSize, 0, 0, outSize, outSize)
        return outBitmap
    }

    private fun colorArrayToBitmap(values: FloatArray, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        for (i in values.indices) {
            val v = (values[i] * 255f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    companion object {
        private const val ASSET_PATH = "models/fsrcnn_div2k.tflite"
        private const val STREAMED_PATH = "features/fsrcnn/libfsrcnn.tflite"
    }
}

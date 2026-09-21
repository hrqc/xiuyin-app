package com.xiuyin.app.pitch

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.exp
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** PCM-only model: no target score, note list, timing hint, gain or remote request enters inference. */
class PianoModel private constructor(modelBytes: ByteBuffer, threads: Int) : AutoCloseable {
    private val interpreter = Interpreter(modelBytes, Interpreter.Options().setNumThreads(threads.coerceIn(1, 4)).setUseXNNPACK(true))
    private val outputs = linkedMapOf<Int, ByteBuffer>()
    private val outputArguments = linkedMapOf<Int, Any>()
    private val outputNames = mutableMapOf<String, Int>()
    private val input = ByteBuffer.allocateDirect(INPUT_SAMPLES * 4).order(ByteOrder.nativeOrder())
    private var closed = false

    init {
        try {
            require(interpreter.inputTensorCount == 1)
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(INPUT_SAMPLES)))
            require(interpreter.outputTensorCount == 4)
            for (index in 0 until interpreter.outputTensorCount) {
                val tensor = interpreter.getOutputTensor(index)
                require(tensor.dataType() == DataType.FLOAT32)
                outputNames[tensor.name()] = index
                outputs[index] = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
                outputArguments[index] = outputs.getValue(index)
            }
            listOf("frame_logits", "onset_logits", "velocity_values").forEach { name ->
                val index = checkNotNull(outputNames[name]) { "模型缺少输出 $name" }
                require(interpreter.getOutputTensor(index).shape().contentEquals(intArrayOf(1, FRAME_COUNT, PIANO_KEYS)))
            }
            // In this exact model the scalar is constant zero, not an offset detector.
            require(interpreter.getOutputTensor(outputNames.getValue("offset_logits")).shape().isEmpty())
        } catch (error: Exception) { interpreter.close(); throw error }
    }

    @Synchronized
    fun infer(samples16k: FloatArray): Prediction {
        check(!closed) { "识音模型已关闭" }
        require(samples16k.size == INPUT_SAMPLES)
        // Preserve finite FIR overshoot, report it separately; never clip or amplify to manufacture confidence.
        require(samples16k.all { it.isFinite() && it in -2.0f..2.0f })
        input.clear()
        samples16k.forEach { input.putFloat(it) }
        input.rewind()
        outputs.values.forEach { it.clear() }
        val started = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputArguments)
        val elapsed = System.nanoTime() - started
        val frames = read("frame_logits")
        val onsets = read("onset_logits")
        val velocities = read("velocity_values")
        val offset = read("offset_logits")
        check(offset.size == 1 && offset[0] == 0.0f) { "无offset模型输出已改变" }
        return Prediction(frames, onsets, FloatArray(frames.size) { sigmoid(frames[it]) },
            FloatArray(onsets.size) { sigmoid(onsets[it]) }, velocities, elapsed / 1_000_000.0)
    }

    private fun read(name: String): FloatArray {
        val buffer = outputs.getValue(outputNames.getValue(name)).duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        return FloatArray(buffer.remaining() / 4) { buffer.float.also { check(it.isFinite()) { "识音模型输出非有限值" } } }
    }
    @Synchronized
    override fun close() { if (!closed) { closed = true; interpreter.close() } }
    data class Prediction(val frameLogits: FloatArray, val onsetLogits: FloatArray, val frameScores: FloatArray,
        val onsetScores: FloatArray, val rawVelocities: FloatArray, val computeMs: Double)

    companion object {
        const val MODEL_ASSET = "piano/onsets_frames_wavinput_no_offset_uni.tflite"
        const val MODEL_BYTES = 75_860_620L
        const val MODEL_SHA256 = "69ac3567d8ee5fda620225a49c62839c3e82165e89d54d1af63caac877605dfc"
        const val SAMPLE_RATE = 16_000
        const val INPUT_SAMPLES = 17_920
        const val FRAME_COUNT = 32
        const val PIANO_KEYS = 88
        const val MIDI_MIN = 21
        const val HOP_SAMPLES = 512
        const val SPECTRAL_WINDOW_SAMPLES = 2_048
        fun fromAssets(context: Context, threads: Int = 2): PianoModel {
            context.assets.open(MODEL_ASSET).use { verify(it) }
            val mapped = context.assets.openFd(MODEL_ASSET).use { descriptor ->
                require(descriptor.declaredLength == MODEL_BYTES)
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            return PianoModel(mapped, threads)
        }
        fun fromFile(file: File, threads: Int = 2): PianoModel {
            require(file.length() == MODEL_BYTES)
            file.inputStream().use { verify(it) }
            val mapped = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, MODEL_BYTES) }
            return PianoModel(mapped, threads)
        }
        private fun verify(stream: InputStream) {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(65536)
            var total = 0L
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                total += count
                require(total <= MODEL_BYTES) { "钢琴模型文件过大" }
                digest.update(chunk, 0, count)
            }
            require(total == MODEL_BYTES && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == MODEL_SHA256) {
                "钢琴模型缺失或SHA256不匹配；识音不能启动"
            }
        }
        private fun sigmoid(logit: Float): Float = if (logit >= 0f) (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
            else { val exponent = exp(logit.toDouble()); (exponent / (1.0 + exponent)).toFloat() }
    }
}
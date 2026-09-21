package com.xiuyin.app.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.xiuyin.app.score.DigitalScore
import com.xiuyin.app.score.ScoreIssueSeverity
import com.xiuyin.app.score.ScoreTimelineCompiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Actual local synthesis. Playback position comes from AudioTrack, never a practice prediction. */
data class PlaybackSnapshot(
    val playing: Boolean = false,
    val positionBeat: Double = 0.0,
    val writtenSamples: Long = 0,
    val playedSamples: Long = 0,
    val message: String = "试听未开始",
    val error: String? = null,
)

class ScorePlaybackEngine(context: Context) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    private val mutableState = MutableStateFlow(PlaybackSnapshot())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun play(score: DigitalScore, bpm: Int, volume: Float, startMeasure: Int = 0, loop: Boolean = false, metronome: Boolean = false) {
        check(!closed) { "试听引擎已关闭" }
        require(bpm in 30..240 && volume in 0f..1f) { "试听速度或音量超出范围" }
        job?.cancel()
        val session = ++generation
        mutableState.value = PlaybackSnapshot(message = "正在准备本地合成试听…")
        job = scope.launch {
            serial.withLock {
                if (!isCurrent(session)) return@withLock
                var track: AudioTrack? = null
                var focus: AudioFocusRequest? = null
                try {
                    val timeline = ScoreTimelineCompiler.compile(score)
                    require(timeline.issues.none { it.severity == ScoreIssueSeverity.ERROR }) { "存在结构错误，请先修谱后试听" }
                    require(timeline.notes.isNotEmpty()) { "乐谱中没有可试听音符" }
                    require(startMeasure in timeline.measures.indices) { "起始小节不存在" }
                    val firstBeat = timeline.measures[startMeasure].startBeat.toDouble()
                    val endBeat = timeline.totalBeats.toDouble()
                    val secondsPerBeat = 60.0 / bpm
                    val totalSamples = ((endBeat - firstBeat) * secondsPerBeat * SAMPLE_RATE).toLong()
                    require(totalSamples in 1..(SAMPLE_RATE * 60L * 30)) { "单次试听需在30分钟以内" }
                    val notes = ScorePlaybackPlan.tones(timeline.notes, firstBeat, secondsPerBeat, SAMPLE_RATE)
                    val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "设备不支持本地试听音频格式" }
                    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    val requestedFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener({ change -> if (change != AudioManager.AUDIOFOCUS_GAIN) stop("音频焦点被其他应用占用，试听已停止") }, Handler(Looper.getMainLooper())).build()
                    check(manager.requestAudioFocus(requestedFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "当前无法取得音频焦点，请稍后试听" }
                    focus = requestedFocus
                    val output = AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, BLOCK * 4)).build()
                    track = output
                    check(output.state == AudioTrack.STATE_INITIALIZED) { "扬声器音轨初始化失败" }
                    output.setVolume(volume)
                    do {
                        output.play()
                        val data = ShortArray(BLOCK)
                        var written = 0L
                        var lastPlayed = 0L
                        var progressAt = System.nanoTime()
                        while (written < totalSamples) {
                            coroutineContext.ensureActive()
                            val count = minOf(BLOCK.toLong(), totalSamples - written).toInt()
                            val active = notes.filter { it.start < written + count && it.end > written }
                            for (index in 0 until count) {
                                val sample = written + index
                                var signal = 0.0
                                var voices = 0
                                for (tone in active) if (sample >= tone.start && sample < tone.end) {
                                    val seconds = (sample - tone.start).toDouble() / SAMPLE_RATE
                                    val remaining = (tone.end - sample).toDouble() / SAMPLE_RATE
                                    val envelope = minOf(1.0, seconds / 0.006) * minOf(1.0, remaining / 0.025) * (0.25 + 0.75 * exp(-seconds * 1.7))
                                    val angle = 2 * PI * tone.frequency * seconds
                                    signal += envelope * (sin(angle) + 0.25 * sin(2 * angle) + 0.09 * sin(3 * angle))
                                    voices++
                                }
                                if (metronome) {
                                    val beatSample = (sample.toDouble() / (secondsPerBeat * SAMPLE_RATE)) % 1.0
                                    val clickTime = beatSample * secondsPerBeat
                                    if (clickTime < 0.018) signal += 0.3 * sin(2 * PI * 1400 * clickTime) * exp(-clickTime * 180)
                                }
                                data[index] = (signal * 6500 / sqrt(maxOf(1, voices).toDouble())).coerceIn(-30000.0, 30000.0).toInt().toShort()
                            }
                            var offset = 0
                            while (offset < count) {
                                coroutineContext.ensureActive()
                                val accepted = output.write(data, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                                check(accepted > 0) { "试听输出失败：$accepted" }
                                offset += accepted
                            }
                            written += count
                            val played = (output.playbackHeadPosition.toLong() and 0xffffffffL).coerceAtMost(totalSamples)
                            if (played != lastPlayed) { lastPlayed = played; progressAt = System.nanoTime() }
                            check(System.nanoTime() - progressAt < 5_000_000_000L) { "试听设备没有输出进度" }
                            publish(session, PlaybackSnapshot(true, firstBeat + played / (SAMPLE_RATE * secondsPerBeat), written, played, "本地合成音色试听"))
                        }
                        while ((output.playbackHeadPosition.toLong() and 0xffffffffL) < totalSamples) {
                            coroutineContext.ensureActive()
                            val played = output.playbackHeadPosition.toLong() and 0xffffffffL
                            if (played != lastPlayed) { lastPlayed = played; progressAt = System.nanoTime() }
                            check(System.nanoTime() - progressAt < 5_000_000_000L) { "试听结束时输出设备无进度" }
                            publish(session, PlaybackSnapshot(true, firstBeat + played / (SAMPLE_RATE * secondsPerBeat), written, played, "本地合成音色试听"))
                            delay(20)
                        }
                        output.stop()
                        output.flush()
                    } while (loop && isCurrent(session))
                    publish(session, PlaybackSnapshot(positionBeat = endBeat, writtenSamples = totalSamples, playedSamples = totalSamples, message = "试听结束"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { publish(session, PlaybackSnapshot(message = "试听失败", error = error.message ?: error.javaClass.simpleName)) }
                finally {
                    try { if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
                    catch (error: Exception) { publish(session, PlaybackSnapshot(message = "试听停止异常", error = error.message)) }
                    finally {
                        try { track?.release() } catch (error: Exception) { android.util.Log.e("Xiuyin", "试听音轨释放失败", error) }
                        focus?.let { manager.abandonAudioFocusRequest(it) }
                    }
                }
            }
        }
    }

    @Synchronized fun stop(reason: String = "试听已停止") {
        generation++
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(playing = false, message = reason)
    }
    @Synchronized fun close() { stop(); closed = true; scope.cancel() }
    @Synchronized private fun isCurrent(session: Long): Boolean = !closed && generation == session
    @Synchronized private fun publish(session: Long, snapshot: PlaybackSnapshot) { if (isCurrent(session)) mutableState.value = snapshot }
    companion object { const val SAMPLE_RATE = 24_000; private const val BLOCK = 512 }
}

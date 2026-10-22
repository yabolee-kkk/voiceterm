package ai.local.voiceagent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 全双工对话用的连续录音器：一直开着麦，靠能量检测切句。
 *
 * 与 VoiceRecorder（按住说话）的区别：
 *   · 按住说话靠手指决定起止；这个靠 VAD —— 说 200ms 算开始，静 900ms 算一句说完
 *   · 用 VOICE_COMMUNICATION 音源：系统会挂上回声消除/降噪，播放时开麦才不自激
 *   · 门限分两档：Agent 正在说话时用 bargeThreshold（更高），避免把自己的喇叭声当成打断
 *
 * 实测要点：
 *   · MI6 上 AcousticEchoCanceler 可用，但不挂 VOICE_COMMUNICATION 基本无效
 *   · 阈值按 16bit PCM 幅度算，安静房间底噪 ~200~400，正常说话峰值 2000~8000
 */
class DialogRecorder(private val sampleRate: Int = 16_000) {

    companion object {
        private const val TAG = "DialogRecorder"
        private const val FRAME_BYTES = 1024 * 2          // 1024 个采样 ≈ 64ms
        private const val SPEECH_ON_MS = 200             // 连续有声多久算"开始说话"
        // 静多久算"这句完了"。900ms 太急：说话稍一停就被切到下一句，用户反馈"跑到下一句话上"
        // 改成 1500ms —— 正常换气/想词不再被切断，代价是每轮回复晚 0.6 秒开口
        private const val SILENCE_END_MS = 1500
        private const val MAX_SEGMENT_MS = 20_000
        const val DEFAULT_THRESHOLD = 900                // 平时门限
        const val BARGE_THRESHOLD = 2600                 // Agent 说话时的打断门限
    }

    @Volatile
    var level = 0
        private set

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** 当前门限由调用方决定（播报期调高，防自激） */
    @Volatile
    var thresholdProvider: () -> Int = { DEFAULT_THRESHOLD }

    /**
     * 播报期开关。开着时：
     *   · 前 600ms 把听到的电平当成"回声基线"（echoFloor）
     *   · 只有超过 max(BARGE_THRESHOLD, 回声基线×1.7) 才算用户在插话
     *   · 不往外抛 onSegment —— 否则手机听自己的喇叭就会无限自激（实测必现）
     */
    @Volatile
    var playingPhase = false

    @Volatile
    private var echoFloor = 0

    private var playPhaseStartAt = 0L

    fun enterPlayingPhase() {
        echoFloor = 0
        playPhaseStartAt = System.currentTimeMillis()
        playingPhase = true
    }

    fun exitPlayingPhase() {
        playingPhase = false
        echoFloor = 0
    }

    @SuppressLint("MissingPermission")
    fun start(onSegment: (ByteArray, Long) -> Unit, onLevel: (Int) -> Unit, onSpeechStart: () -> Unit = {}) {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, FRAME_BYTES * 4)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: Exception) {
            Log.w(TAG, "VOICE_COMMUNICATION 不可用，退回 MIC：${e.message}")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("麦克风初始化失败")
        }
        // 回声消除 + 降噪：没有也能跑，只是播放期自激风险高
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AEC 开启失败：${e.message}")
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NS 开启失败：${e.message}")
        }

        record = r
        running = true
        r.startRecording()

        thread = Thread {
            val buf = ByteArray(FRAME_BYTES)
            val seg = ByteArrayOutputStream()
            var speechMs = 0
            var silenceMs = 0
            var started = false
            var segStartAt = 0L
            val frameMs = FRAME_BYTES / 2 * 1000 / sampleRate

            while (running) {
                val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                val rms = rms(buf, n)
                level = rms
                onLevel(rms)

                // 播报期：先采回声基线，再判"是不是人在插话"
                val playing = playingPhase
                if (playing && System.currentTimeMillis() - playPhaseStartAt < 600) {
                    if (rms > echoFloor) echoFloor = rms
                }
                val th = if (playing) {
                    maxOf(BARGE_THRESHOLD, (echoFloor * 1.7f).toInt())
                } else {
                    thresholdProvider()
                }
                val voiced = rms > th

                if (!started) {
                    if (voiced) {
                        speechMs += frameMs
                        if (speechMs >= SPEECH_ON_MS) {
                            started = true
                            segStartAt = System.currentTimeMillis()
                            silenceMs = 0
                            seg.reset()
                            seg.write(buf, 0, n)
                            // 立刻通知"用户开口了"，打断播报要抢在这 1 秒之前
                            try { onSpeechStart() } catch (_: Exception) {}
                        }
                    } else {
                        speechMs = 0
                    }
                } else {
                    seg.write(buf, 0, n)
                    if (voiced) silenceMs = 0 else silenceMs += frameMs
                    val durMs = System.currentTimeMillis() - segStartAt
                    if (silenceMs >= SILENCE_END_MS || durMs > MAX_SEGMENT_MS) {
                        started = false
                        speechMs = 0
                        silenceMs = 0
                        val pcm = seg.toByteArray()
                        seg.reset()
                        // 播报期的段落一律丢弃：那基本是自己的喇叭声（AEC 在 MI6 上不够用）
                        if (!playingPhase && pcm.size > sampleRate / 4) {   // 至少 0.25s
                            onSegment(WavCodec.wrap(pcm, sampleRate), durMs)
                        }
                    }
                }
            }
            try {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
                r.release()
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(600) } catch (_: Exception) {}
        thread = null
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
        record = null
        level = 0
    }

    private fun rms(buf: ByteArray, n: Int): Int {
        var sum = 0.0
        var i = 0
        var count = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += (v.toDouble() * v)
            count++
            i += 2
        }
        if (count == 0) return 0
        return kotlin.math.sqrt(sum / count).toInt()
    }
}

/** wav 头封装（按住说话和连续对话共用） */
object WavCodec {
    fun wrap(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bits: Int = 16): ByteArray {
        val total = pcm.size + 44
        val h = ByteArray(44)
        fun i32(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun i16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(h, 0)
        i32(4, total - 8)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        i32(16, 16)
        i16(20, 1)
        i16(22, channels)
        i32(24, sampleRate)
        i32(28, sampleRate * channels * bits / 8)
        i16(32, channels * bits / 8)
        i16(34, bits)
        "data".toByteArray().copyInto(h, 36)
        i32(40, pcm.size)
        val out = ByteArray(total)
        h.copyInto(out, 0)
        pcm.copyInto(out, 44)
        return out
    }
}

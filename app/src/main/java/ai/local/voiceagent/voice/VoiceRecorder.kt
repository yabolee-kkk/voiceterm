package ai.local.voiceagent.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 按住说话的录音器：16kHz / 单声道 / PCM16 → 内存里的 wav。
 *
 * 为什么 16k：MiMo ASR 与本机 whisper 都吃 16k；再高只是白白增大上传体积。
 * 不写文件：一条语音通常 1~10 秒（≤320KB），直接留在内存里传给 hub 更省事。
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_MS = 60_000          // 单条上限 1 分钟
        const val MIN_MS = 400             // 太短当成误触
    }

    private var job: Job? = null
    private var record: AudioRecord? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    var recording = false
        private set

    @Volatile
    var peak = 0                          // 最近一段的峰值幅度（给 UI 画波形）
        private set

    var startedAt = 0L
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (recording) return
        pcm.reset()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2)   // 至少 200ms 的缓冲
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize * 2,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("麦克风初始化失败（可能没有权限或被占用）")
        }
        record = r
        startedAt = System.currentTimeMillis()
        recording = true
        r.startRecording()

        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(bufSize)
            while (isActive && recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    pcm.write(buf, 0, n)
                    peak = peakOf(buf, n)
                    if (System.currentTimeMillis() - startedAt > MAX_MS) break
                }
            }
        }
    }

    /** 停止并返回 wav 字节；太短返回 null（当误触处理） */
    private val lock = Any()

    suspend fun stop(): ByteArray? {
        // 双保险：并发调用（手势抬手 + 60 秒兜底）时只让一个人真正停
        synchronized(lock) { if (!recording) return null }
        if (!recording) return null
        recording = false
        job?.join()
        job = null
        try {
            record?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (t: Throwable) {
            android.util.Log.w("VoiceRecorder", "release 失败：${t.message}")
        }
        record = null
        peak = 0

        val ms = System.currentTimeMillis() - startedAt
        val data = pcm.toByteArray()
        lastPcmData = data
        pcm.reset()
        if (ms < MIN_MS || data.isEmpty()) return null
        return toWav(data)
    }

    @Synchronized
    fun cancel() {
        recording = false
        job = null
        try {
            record?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        record = null
        pcm.reset()
        peak = 0
    }

    private fun peakOf(buf: ByteArray, n: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > max) max = a
            i += 2
        }
        return max
    }

    /** PCM16 → wav（44 字节标准头，ASR 服务端普遍只认 wav） */
    /** 上一次 stop() 时的完整 PCM（松手后按偏移切尾巴用） */
    @Volatile
    private var lastPcmData: ByteArray? = null

    fun lastPcm(): ByteArray? = lastPcmData

    /** 录音中取原始 PCM 快照（分段转写要按字节切） */
    fun snapshotPcm(): ByteArray? {
        if (!recording) return null
        return try {
            pcm.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** 把一段裸 PCM 包成 wav（给分段转写用） */
    fun wavOf(pcmData: ByteArray): ByteArray = toWav(pcmData)

    /**
     * 录音过程中取一份"到目前为止"的 wav 快照 —— 边录边转写用。
     * pcm 是 ByteArrayOutputStream，toByteArray 自带同步，录音线程继续写也不影响。
     */
    fun snapshotWav(): ByteArray? {
        if (!recording) return null
        val data = try {
            pcm.toByteArray()
        } catch (e: Exception) {
            return null
        }
        if (data.size < 8000) return null      // 不到 0.25 秒不值得转
        return toWav(data)
    }

    private fun toWav(pcmData: ByteArray): ByteArray {
        val total = pcmData.size + 44
        val header = ByteArray(44)
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
            header[off + 2] = ((v shr 16) and 0xFF).toByte()
            header[off + 3] = ((v shr 24) and 0xFF).toByte()
        }

        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v shr 8) and 0xFF).toByte()
        }

        "RIFF".toByteArray().copyInto(header, 0)
        putInt(4, total - 8)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putInt(16, 16)                       // fmt chunk 长度
        putShort(20, 1)                      // PCM
        putShort(22, 1)                      // 单声道
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)          // byteRate = rate * channels * 2
        putShort(32, 2)                      // blockAlign
        putShort(34, 16)                     // bits
        "data".toByteArray().copyInto(header, 36)
        putInt(40, pcmData.size)

        val out = ByteArray(total)
        header.copyInto(out, 0)
        pcmData.copyInto(out, 44)
        return out
    }
}

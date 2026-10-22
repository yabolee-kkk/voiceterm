package ai.local.voiceagent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * PCM 流播放器：hub 边合成边推 PCM，这里收到就写进 AudioTrack，不等整段。
 *
 * 为什么不用 WavPlayer：那个是"拿到完整 wav 再播"，首字延迟 = 整段合成时间（实测 3.7s）。
 * 全双工对话里必须"拿到第一个包就出声"（实测首包 0.5~0.8s）。
 *
 * 24kHz / 单声道 / PCM16 —— MiMo 流式固定这个格式，别改。
 */
class PcmStreamPlayer(private val sampleRate: Int = 24_000) {

    private var track: AudioTrack? = null

    @Volatile
    var playing = false
        private set

    fun start() {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, 32 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack 创建失败：${e.message}")
            return
        }
        track = t
        t.play()
        playing = true
    }

    /** 写入一段 PCM；写不进去（已停）就返回 false，让上游取消 */
    fun write(pcm: ByteArray): Boolean {
        val t = track ?: return false
        var off = 0
        while (off < pcm.size) {
            val n = try {
                t.write(pcm, off, pcm.size - off)
            } catch (e: Exception) {
                Log.w(TAG, "write 异常：${e.message}")
                return false
            }
            if (n <= 0) return false
            off += n
        }
        return playing
    }

    /** 数据写完：让它把缓冲区播完再停（直接 stop 会截断尾巴） */
    fun finish() {
        val t = track
        playing = false
        if (t == null) return
        Thread {
            try {
                Thread.sleep(120)
                t.stop()
                t.release()
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }
        track = null
    }

    fun stop() {
        playing = false
        try {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    companion object {
        private const val TAG = "PcmStreamPlayer"
    }
}

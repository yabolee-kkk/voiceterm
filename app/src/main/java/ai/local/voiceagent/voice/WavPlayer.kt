package ai.local.voiceagent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * wav 播放器：解出 PCM 后走 AudioTrack 边写边播。
 *
 * 为什么不用 MediaPlayer：wav 落在缓存文件里再播要多一次磁盘往返，
 * 而且 M5 通话要的是"收到 chunk 就能出声"，AudioTrack 才是那条路（先在这里铺好）。
 * 只支持 PCM16 wav（MiMo 返回的就是 24k/单声道/16bit）。
 */
class WavPlayer {

    private var track: AudioTrack? = null
    private var job: Thread? = null

    @Volatile
    var playing = false
        private set

    /** 播放 wav；onDone 在播完或被打断后回调（主线程无关，调用方自己切） */
    fun play(wav: ByteArray, onDone: () -> Unit = {}) {
        stop()
        val pcmInfo = parseWav(wav) ?: run {
            Log.w(TAG, "不是可解析的 PCM16 wav，放弃播放")
            onDone()
            return
        }
        val track = createTrack(pcmInfo.sampleRate)
            ?: run {
                Log.w(TAG, "AudioTrack 创建失败")
                onDone()
                return
            }
        this.track = track
        playing = true
        track.play()

        job = Thread {
            try {
                var off = 0
                val chunk = 4096
                while (off < pcmInfo.pcm.size && playing) {
                    val n = minOf(chunk, pcmInfo.pcm.size - off)
                    val w = track.write(pcmInfo.pcm, off, n)
                    if (w <= 0) break
                    off += w
                }
            } catch (e: Exception) {
                Log.w(TAG, "播放异常：${e.message}")
            } finally {
                playing = false
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {
                }
                this@WavPlayer.track = null
                onDone()
            }
        }.also { it.isDaemon = true; it.start() }
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

    private fun createTrack(sampleRate: Int): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
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
            .setBufferSizeInBytes(maxOf(minBuf, 4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.playbackHeadPosition }
    } catch (e: Exception) {
        Log.w(TAG, "AudioTrack 初始化失败：${e.message}")
        null
    }

    private data class Wav(val sampleRate: Int, val pcm: ByteArray)

    /** 只解 44 字节标准头的 PCM16 wav；带额外 chunk 的走逐块扫描 */
    private fun parseWav(wav: ByteArray): Wav? {
        if (wav.size < 44) return null
        if (String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") return null

        var sampleRate = 0
        var data: ByteArray? = null
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val size = le32(wav, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (size >= 16 && body + 16 <= wav.size) {
                    val channels = le16(wav, body + 2)
                    sampleRate = le32(wav, body + 4)
                    val bits = le16(wav, body + 14)
                    if (channels != 1 || bits != 16) return null
                }

                "data" -> {
                    val end = minOf(body + size, wav.size)
                    data = wav.copyOfRange(body, end)
                }
            }
            pos = body + size + (size and 1)
            if (data != null && sampleRate > 0) break
        }
        val d = data ?: return null
        if (sampleRate <= 0) return null
        return Wav(sampleRate, d)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val TAG = "WavPlayer"

        /** 供调试：转成可打印的时长/参数 */
        fun describe(wav: ByteArray): String = try {
            val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
                ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
            "wav ${wav.size}B @${rate}Hz ≈${(wav.size - 44) * 1000 / (rate * 2)}ms"
        } catch (e: Exception) {
            "wav ${wav.size}B"
        }
    }
}

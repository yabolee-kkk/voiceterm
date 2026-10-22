package ai.local.voiceagent.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import ai.local.voiceagent.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 来电语音通报。
 *
 * 用户原话："不接听就一直说这件事；我一接听，声音马上断掉，然后我们就能接着聊。"
 *
 * 所以每一轮 = 短铃 1.2 秒 → 语音通报（TTS 念那句话）→ 停 2.5 秒 → 再念一遍；
 * 最多 4 轮（约 45 秒），中途"接听 / 挂断 / 打开那条会话 / 服务销毁"都会立刻打断。
 * 系统静音也没关系：通知本身还在（文字通报），不会漏事。
 */
object CallAnnouncer {

    private const val TAG = "CallAnnouncer"
    /** 用户要求：5~6 秒念一遍，别 45 秒才一遍 */
    private const val PERIOD_MS = 5500L
    /** 最多念 20 遍（约 2 分钟），之后只留通知文字，不再吵 */
    private const val MAX_ROUNDS = 20

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private var player: MediaPlayer? = null

    fun start(ctx: Context, text: String) {
        stop()
        if (text.isBlank()) return
        val app = ctx.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        job = s.launch {
            try {
                var round = 0
                while (isActive && round < MAX_ROUNDS) {
                    round++
                    Log.i(TAG, "第 $round 遍：$text")
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val spoke = speak(app, text)
                    if (!spoke) {
                        // TTS 不通就退回铃声（至少能听见）
                        Notify.startRinging(app)
                        delay(1500)
                        Notify.stopRinging()
                    }
                    val used = android.os.SystemClock.elapsedRealtime() - t0
                    val wait = (PERIOD_MS - used).coerceAtLeast(1200L)
                    if (isActive) delay(wait)
                }
                Log.i(TAG, "念满 $MAX_ROUNDS 遍，停（通知还在）")
            } catch (e: Exception) {
                Log.w(TAG, "通报中断：${e.message}")
            } finally {
                stopPlayer()
                Notify.stopRinging()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        stopPlayer()
        Notify.stopRinging()
    }

    val active: Boolean get() = job?.isActive == true

    private fun stopPlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    /** 合成一句并播完（读完或 20 秒超时就返回）；返回是否真的出声了 */
    private suspend fun speak(ctx: Context, text: String): Boolean = withContext(Dispatchers.IO) {
        val wav = try {
            App.client.tts(text)
        } catch (e: Exception) {
            Log.w(TAG, "TTS 失败（退回铃声）：${e.message}")
            return@withContext false
        }
        if (wav.isEmpty()) return@withContext false
        val f = File(ctx.cacheDir, "announce.wav")
        f.writeBytes(wav)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            val done = CompletableDeferred<Unit>()
            mp.setOnCompletionListener { if (!done.isCompleted) done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ ->
                if (!done.isCompleted) done.complete(Unit)
                true
            }
            mp.start()
            Log.i(TAG, "开始播报 ${wav.size}B")
            withTimeoutOrNull(20_000) { done.await() }
            Log.i(TAG, "播报结束")
            true
        } catch (e: Exception) {
            Log.w(TAG, "播放失败：${e.message}")
            false
        } finally {
            stopPlayer()
        }
    }
}

package ai.local.voiceagent.voice

import android.content.Context
import java.io.File

/**
 * 语音文件仓：语音条要能反复播放，所以音频落 app 私有目录，Room 里只存文件名。
 *
 * 为什么不把 base64 塞进 Room：
 *   cc-connect 推回来的语音一条就有 ~190KB（base64 后 256KB），
 *   塞进 payload 会让每条消息的查询都背着几百 KB，列表滚动直接卡。
 *   落文件后数据库只有一行 JSON {"file":"v1698.wav","durMs":6100}。
 */
object VoiceStore {

    private const val DIR = "voice"
    private const val KEEP = 120          // 只留最近 120 条，避免无限长

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 存一段音频，返回相对文件名 */
    fun save(ctx: Context, bytes: ByteArray, ext: String = "wav"): String {
        val name = "v${System.currentTimeMillis()}.$ext"
        File(dir(ctx), name).writeBytes(bytes)
        prune(ctx)
        return name
    }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun read(ctx: Context, name: String): ByteArray? = try {
        val f = File(dir(ctx), name)
        if (f.exists()) f.readBytes() else null
    } catch (e: Exception) {
        null
    }

    fun exists(ctx: Context, name: String): Boolean = File(dir(ctx), name).exists()

    private fun prune(ctx: Context) {
        try {
            val files = dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (files.size <= KEEP) return
            files.drop(KEEP).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}

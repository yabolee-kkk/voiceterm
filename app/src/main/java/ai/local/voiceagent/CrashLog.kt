package ai.local.voiceagent

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 闪退抓现场：把未捕获异常的堆栈写到 filesDir/crash-*.txt。
 * 用户报"按住说话闪退"但 logcat 抓不到（缓冲会滚掉），所以落到文件里，之后 adb run-as 就能捞。
 */
object CrashLog {
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(ctx, t, e) }
            prev?.uncaughtException(t, e)
        }
    }

    private fun write(ctx: Context, t: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { p ->
            p.println("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.CHINA).format(java.util.Date())}")
            p.println("线程: ${t.name} (id=${t.id})")
            p.println("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            val rt = Runtime.getRuntime()
            p.println("内存: max=${rt.maxMemory() / 1048576}MB total=${rt.totalMemory() / 1048576}MB free=${rt.freeMemory() / 1048576}MB")
            p.println("---")
            e.printStackTrace(p)
        }
        val dir = File(ctx.filesDir, "crash").apply { mkdirs() }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(sw.toString())
        // 只留最近 5 份
        dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        android.util.Log.e("CrashLog", sw.toString())
    }
}

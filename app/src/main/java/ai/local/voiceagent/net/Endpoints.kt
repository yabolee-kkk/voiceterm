package ai.local.voiceagent.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一条线路：地址 + 走的哪条（局域网/公网）+ 探测耗时 */
data class Route(val url: String, val via: String, val ms: Long) {
    val label: String get() = if (ms >= 0) "$via · ${ms}ms" else "$via · 不通"
}

/**
 * 局域网优先、公网兜底。
 *
 * 为什么不在 App 里写死一个地址：在家走公网要绕一圈 CF 边缘（实测首字节 1~2 秒，
 * 语音会明显滞后），出门走局域网根本不通。所以每次联网时先探一下，谁通用谁。
 */
object Endpoints {

    /** hub /ping 会回 {"ok":true,"hub":"voiceterm",...}，用它确认对面是我们自己的服务（端口填错时别的服务也会回 200） */
    private const val MARKER = "voiceterm"

    /** 只看 HTTP 能不能通；通就返回耗时毫秒，否则 null */
    suspend fun probe(url: String, timeoutMs: Long): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val c = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val t0 = System.currentTimeMillis()
            c.newCall(Request.Builder().url(url.trimEnd('/') + "/ping").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                // 必须是我们自己的 hub：光看 200 不够，端口填错时别的服务也会回 200（踩过）
                if (r.isSuccessful && body.contains(MARKER)) System.currentTimeMillis() - t0 else null
            }
        }.getOrNull()
    }

    /** 手机当前是不是连的 Wi-Fi（用移动数据时没必要等局域网探测超时） */
    fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val n = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /**
     * 选线：Wi-Fi 下先探局域网（1.5s），不通再探公网（6s）；
     * 移动数据下直接探公网。两边都不通就返回局域网地址，让界面照常报错。
     */
    suspend fun pick(lan: String, pub: String?, wifi: Boolean): Route {
        val pubUrl = pub?.takeIf { it.isNotBlank() }
        val lanMs = if (wifi) probe(lan, 1500) else null
        if (lanMs != null) return Route(lan, "局域网", lanMs)
        if (pubUrl != null) {
            // 公网走 CF，实测首字节 1~6 秒都可能，超时给足 12 秒
            val pubMs = probe(pubUrl, 12000)
            if (pubMs != null) return Route(pubUrl, "公网", pubMs)
            // 探不通也别放弃：不在家的时候公网才是对的，照样先试着用（真不通界面会报错）
            if (!wifi) return Route(pubUrl, "公网(探测超时)", -1)
        }
        return Route(lan, if (wifi) "局域网" else "局域网(未连Wi-Fi)", -1)
    }
}

package ai.local.voiceagent.net

import android.content.Context
import android.content.SharedPreferences
import ai.local.voiceagent.BuildConfig

/**
 * 手机侧连接配置。令牌只存在本机 SharedPreferences，不进代码、不进 Git、不进构建产物。
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)

    /** 局域网地址（家里用） */
    var baseUrl: String
        get() = sp.getString(KEY_BASE, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_BASE_URL
        set(v) = sp.edit().putString(KEY_BASE, v.trim().trimEnd('/')).apply()

    /** 公网地址（CF 隧道，如 https://voice.borong.de5.net）；留空 = 只用局域网 */
    var publicUrl: String
        get() = sp.getString(KEY_PUBLIC, "") ?: ""
        set(v) = sp.edit().putString(KEY_PUBLIC, v.trim().trimEnd('/')).apply()

    /** 探测选出来的当前线路（运行时，不参与用户配置） */
    var activeBase: String?
        get() = sp.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_ACTIVE, v?.trim()?.trimEnd('/')).apply()

    /** 给界面看的当前线路说明，如「公网 · 780ms」 */
    var activeRoute: String
        get() = sp.getString(KEY_ROUTE, "") ?: ""
        set(v) = sp.edit().putString(KEY_ROUTE, v).apply()

    /**
     * 当前线路是不是局域网。公网（CF）每条请求首字节 1~3 秒，
     * 按住说话那种"边说边分段转写"在公网上会把延迟叠成十几秒，所以只在局域网下开实时转写。
     */
    val onLanRoute: Boolean get() = activeRoute.startsWith("局域网") || activeRoute.isBlank()

    /** 实际发请求用的地址：探测结果优先，没探测过就用局域网地址 */
    val activeBaseUrl: String get() = activeBase ?: baseUrl

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    var deviceId: String
        get() = sp.getString(KEY_DEVICE, null)?.takeIf { it.isNotBlank() } ?: "dev01"
        set(v) = sp.edit().putString(KEY_DEVICE, v.trim()).apply()

    /** 最后打开过的会话：划掉任务再点图标时直接回到那条对话 */
    var lastSessionId: Long
        get() = sp.getLong(KEY_LAST_SESSION, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SESSION, v).apply()

    /**
     * SSE 断线续传游标（按 Agent 各存一份）。
     * 收到带 id: 的事件就往前挪；重连时把它作为 ?since= 带给 hub，
     * hub 把这期间完成的事件补投回来 —— 手机上就不会出现「任务其实跑完了但我这没显示」。
     */
    fun cursorOf(agent: String): Long = sp.getLong("cursor_$agent", 0L)
    fun setCursor(agent: String, seq: Long) {
        if (seq > cursorOf(agent)) sp.edit().putLong("cursor_$agent", seq).apply()
    }

    val configured: Boolean get() = token.isNotBlank()

    fun save(base: String, token: String) {
        baseUrl = base
        this.token = token
    }

    companion object {
        private const val KEY_BASE = "base_url"
        private const val KEY_PUBLIC = "public_url"
        private const val KEY_ACTIVE = "active_base"
        private const val KEY_ROUTE = "active_route"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LAST_SESSION = "last_session_id"
    }
}

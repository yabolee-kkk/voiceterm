package ai.local.voiceagent.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ai.local.voiceagent.App
import ai.local.voiceagent.net.ConnState
import ai.local.voiceagent.net.HubEvent
import ai.local.voiceagent.notify.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：把进程钉在前台，SSE 长连接才不会在锁屏/切后台时被系统掐掉。
 *
 * 为什么必须有它：
 *   之前 App 只在前台活着 —— 锁屏十分钟回来，Agent 的回复、授权请求、任务完成全丢了，
 *   而那正是"替代飞书"最要紧的场景（人不在看屏幕）。
 *
 * 它做三件事：
 *   ① startForeground + 常驻通知（在线 Agent 数量），拿进程保活
 *   ② 收 hub 事件，给"用户没在看的那条会话"补一条系统通知
 *   ③ 点通知带着 session_id 回到对应会话（MainActivity singleTask → onNewIntent）
 *
 * 注意：不在这里碰数据库/网络本身 —— engine 是进程级单例，服务只做保活 + 通知。
 */
class HubService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate：进入前台")
        Notify.ensureChannels(this)
        startForeground(Notify.ID_FOREGROUND, Notify.foreground(this, "正在连接…"))

        if (App.prefs.configured) {
            App.engine.start()
        } else {
            Log.w(TAG, "还没配置地址/令牌，只保活不连")
        }

        // 在线数量 → 改常驻通知的文字
        scope.launch {
            App.engine.conn.collect { m ->
                val open = m.values.count { it == ConnState.OPEN }
                val total = m.size
                val text = when {
                    total == 0 -> "正在连接…"
                    open == 0 -> "重连中（0/$total）"
                    else -> "$open/$total 个 Agent 在线"
                }
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(Notify.ID_FOREGROUND, Notify.foreground(this@HubService, text))
                } catch (e: Exception) {
                    Log.w(TAG, "更新常驻通知失败：${e.message}")
                }
            }
        }

        // 事件 → 通知
        scope.launch {
            App.engine.events.collect { ev ->
                try {
                    // 多台手机共用同一个会话时：不是我这台发出的问题，就别响（消息已经在库里了）
                    val rc = when (ev) {
                        is HubEvent.Done -> ev.replyCtx
                        is HubEvent.Push -> ev.replyCtx
                        is HubEvent.Card -> ev.replyCtx
                        is HubEvent.Buttons -> ev.replyCtx
                        else -> null
                    }
                    if (rc != null && !App.engine.isMyReply(rc)) {
                        Log.i(TAG, "另一台手机的对话（replyCtx=$rc），不响铃")
                        return@collect
                    }
                    when (ev) {
                        // 任务完成 / 需要审批：来电式（铃声 + 震动 + 高重要度，直到用户处理）
                        is HubEvent.Done -> alert(ev.agentId, ev.sessionName, ev.text, needAction = false)
                        is HubEvent.Buttons -> alert(
                            ev.agentId, ev.sessionName,
                            "需要你确认：" + (ev.content?.take(160) ?: "点开处理"),
                            needAction = true,
                        )
                        // 其余：普通通知，响一声
                        is HubEvent.Push -> notifyIfNotWatching(ev.agentId, ev.sessionName, ev.text)
                        is HubEvent.Card -> {
                            val t = App.engine.cardTitleOf(ev.cardJson)
                            notifyIfNotWatching(ev.agentId, ev.sessionName, if (t.isBlank()) "发来一张卡片" else "卡片：$t")
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "处理事件失败：${e.message}")
                }
            }
        }
    }

    /** 来电式提醒：只对"干完活"和"要审批"用，普通回复不吵人 */
    private suspend fun alert(agentId: String, sessionName: String?, body: String, needAction: Boolean) {
        Log.i(TAG, "收到完成事件 agent=$agentId body=${body.take(20)} appVisible=${App.appVisible} viewing=${App.engine.viewingSessionId}")
        if (body.isBlank()) return
        if (App.appVisible && App.engine.isViewing(agentId, sessionName)) {
            Log.i(TAG, "App 正在看这条会话，不打扰")
            return
        }
        val sid = App.engine.sessionIdOf(agentId, sessionName)
        val title = if (needAction) "$agentId 需要你确认" else "$agentId 干完了"
        // 通报就说一句话（用户指定），细节留在通知/来电窗/会话里
        val script = when {
            needAction -> "主人，有任务需要你审批了"
            else -> "主人，我的任务完成了，请您验收"
        }
        Log.i(TAG, "来电提醒 agent=$agentId sid=$sid appVisible=${App.appVisible} viewing=${App.engine.viewingSessionId}")
        Notify.callAlert(
            this, "agent-$agentId", title, body.take(300), sid,
            speak = script, agent = agentId,
        )
        // 语音通报：不接听就反复念这件事，接听/挂断/进会话立刻停
        ai.local.voiceagent.notify.CallAnnouncer.start(this, script)
    }

    /** 去掉换行和连续空白，截到指定长度，别让 TTS 念一堆符号 */
    private fun gist(text: String, max: Int): String =
        text.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ").trim().take(max)

    private suspend fun notifyIfNotWatching(agentId: String, sessionName: String?, body: String) {
        if (body.isBlank()) return
        // 只有"App 在前台 + 正看着这条会话"才不打扰；锁屏/切后台一律要弹
        if (App.appVisible && App.engine.isViewing(agentId, sessionName)) return
        val sid = App.engine.sessionIdOf(agentId, sessionName)
        Notify.message(this, "agent-$agentId", agentId, body.take(300), sid)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户打开会话时发来的"别响了"
        if (intent?.action == ACTION_STOP_RING) {
            ai.local.voiceagent.notify.CallAnnouncer.stop()
            Notify.stopRinging()
        }
        // 重启后自己回来：Android 杀进程后 START_STICKY 会重建服务
        return START_STICKY
    }

    /**
     * 用户把任务从最近任务里划掉：服务不要跟着死。
     * 返回 START_STICKY + 重新贴常驻通知，这样"主动联系 Agent"这条路一直在。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "任务被划掉：服务继续保活")
        try {
            Notify.ensureChannels(this)
            startForeground(Notify.ID_FOREGROUND, Notify.foreground(this, "后台运行中"))
        } catch (e: Exception) {
            Log.w(TAG, "重贴常驻通知失败：${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy：退出前台")
        ai.local.voiceagent.notify.CallAnnouncer.stop()
        Notify.stopRinging()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HubService"
        private const val RING_MAX_MS = 30_000
        const val ACTION_STOP_RING = "ai.local.voiceagent.STOP_RING"

        /** 用户打开会话/处理完了 → 让服务立刻停止振铃 */
        fun stopRing(ctx: android.content.Context) {
            try {
                ctx.startService(Intent(ctx, HubService::class.java).setAction(ACTION_STOP_RING))
            } catch (e: Exception) {
                Log.w(TAG, "停振铃失败：${e.message}")
            }
        }
    }
}

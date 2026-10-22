package ai.local.voiceagent.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.local.voiceagent.MainActivity

/**
 * 通知与渠道。
 *
 * 三条渠道：
 *   svc  —— 常驻"在线状态"（IMPORTANCE_MIN，不响不震，只为把进程留在前台）
 *   msgs —— 普通回复（默认重要度，响一声）
 *   call —— "来电式"提醒：任务完成 / 需要你审批
 *
 * 为什么"来电式"要自己放铃声：
 *   Android 14 起，非通话/闹钟类 App 拿不到 normal 的全屏意图，系统也不会替你循环振铃。
 *   所以：高重要度渠道 + 全屏意图（能弹就弹）+ 我们自己放铃声/震动，直到用户处理
 *   （打开那条会话 / 划掉通知 / 超时）。时长由 HubService 控制。
 */
object Notify {

    const val CH_MSG = "msgs"
    const val CH_SVC = "svc"
    const val CH_CALL = "call"
    const val ID_FOREGROUND = 1001

    private const val TAG = "Notify"

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_SVC) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_SVC, "在线状态", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "保持与语音中继的长连接（锁屏也能收到消息）"
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CH_MSG) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "Agent 消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Agent 的普通回复"
                }
            )
        }
        if (nm.getNotificationChannel(CH_CALL) == null) {
            val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            nm.createNotificationChannel(
                NotificationChannel(CH_CALL, "任务完成 / 待审批（来电式）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Agent 干完活或需要你确认时，像来电一样提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 700)
                    setSound(
                        ring,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(false)
                }
            )
        }
    }

    /** 常驻通知：contentIntent 点开回到 App */
    fun foreground(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SVC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(ctx.getString(ai.local.voiceagent.R.string.app_short))
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(ctx, null))
            .build()

    /** 消息通知：同一个 Agent 只留最新一条（tag = agentId） */
    fun message(ctx: Context, tag: String, title: String, body: String, sessionId: Long?) {
        val n = NotificationCompat.Builder(ctx, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent(ctx, sessionId))
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(tag, tag.hashCode(), n)
    }

    /** 来电式提醒：任务完成 / 需要审批（铃声与震动由调用方另行控制） */
    fun callAlert(
        ctx: Context,
        tag: String,
        title: String,
        body: String,
        sessionId: Long?,
        speak: String = body,
        agent: String = "",
    ) {
        // 全屏/横幅都先弹"来电窗"；接听按钮直接进会话
        val pi = answerIntent(ctx, sessionId, speak)
        val call = PendingIntent.getActivity(
            ctx,
            ((sessionId ?: 0L).toInt() * 31) + 11,
            ai.local.voiceagent.ui.call.CallActivity.intent(ctx, sessionId, tag, agent, title, speak),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(call)             // 点横幅 → 来电窗
            .setFullScreenIntent(call, true)    // 锁屏/后台 → 直接弹来电窗（没授权就降级成横幅）
            // 像电话一样给两个按钮：接听 / 挂断
            .addAction(0, "接听", pi)
            .addAction(0, "挂断", hangupIntent(ctx, tag))
            .build()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(tag, tag.hashCode(), n)
    }

    /** 接听：打开 App 并告诉它"进这条会话、让 Agent 先开口说这句" */
    private fun answerIntent(ctx: Context, sessionId: Long?, speak: String): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("session_id", sessionId ?: 0L)
            putExtra("answer", true)
            putExtra("speak", speak)
        }
        return PendingIntent.getActivity(
            ctx,
            ((sessionId ?: 0L).toInt() * 31) + 7,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hangupIntent(ctx: Context, tag: String): PendingIntent {
        val i = Intent(ctx, ai.local.voiceagent.service.CallActionReceiver::class.java).apply {
            action = ai.local.voiceagent.service.CallActionReceiver.ACTION_HANGUP
            putExtra("tag", tag)
        }
        return PendingIntent.getBroadcast(
            ctx,
            tag.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun clear(ctx: Context, tag: String) {
        Log.i(TAG, "撤销通知 tag=$tag（来自 ${Throwable().stackTrace.getOrNull(1)?.methodName}）")
        ctx.getSystemService(NotificationManager::class.java)?.cancel(tag, tag.hashCode())
    }

    // ---------------- 铃声 / 震动 ----------------

    fun startRinging(ctx: Context) {
        stopRinging()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri)
            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                r.play()
                ringtone = r
                Log.i(TAG, "开始振铃 uri=$uri looping=${r.isLooping} playing=${r.isPlaying}")
            } else {
                Log.w(TAG, "拿不到铃声对象")
            }
        } catch (e: Exception) {
            Log.w(TAG, "放铃声失败：${e.message}")
        }
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 700, 400, 700, 400, 700)
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))   // 0 = 一直重复
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
                vibrator = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "震动失败：${e.message}")
        }
    }

    fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    /**
     * Android 14+ 全屏意图未必被授权（非通话类 App 默认没有）。
     * 返回 false 时只是没有"直接弹到会话"，横幅 + 铃声 + 震动照常。
     */
    fun canFullScreen(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false
        } else {
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun openIntent(ctx: Context, sessionId: Long?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra("session_id", sessionId)
        }
        return PendingIntent.getActivity(
            ctx,
            (sessionId ?: 0L).toInt(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

package ai.local.voiceagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.local.voiceagent.notify.Notify

/**
 * 来电式通知上的按钮动作。
 *
 *   挂断 —— 停止振铃 + 撤掉通知，什么都不打开（人不在旁边/不想接）
 *   接听 —— 走 MainActivity（见 Notify.callAlert 里的 answerIntent），
 *           进去就自动进通话、Agent 先开口说话
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_HANGUP -> {
                val tag = intent.getStringExtra("tag") ?: return
                Log.i(TAG, "挂断 tag=$tag")
                ai.local.voiceagent.notify.CallAnnouncer.stop()
                Notify.stopRinging()
                Notify.clear(context, tag)
                HubService.stopRing(context)
            }
        }
    }

    companion object {
        private const val TAG = "CallAction"
        const val ACTION_HANGUP = "ai.local.voiceagent.CALL_HANGUP"
    }
}

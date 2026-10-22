package ai.local.voiceagent.ui.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.local.voiceagent.App
import ai.local.voiceagent.MainActivity
import ai.local.voiceagent.notify.CallAnnouncer
import ai.local.voiceagent.notify.Notify

/**
 * 来电弹窗（锁屏也能弹出）。
 *
 * 顺序跟打电话一致：语音先到 → 屏幕亮、弹出这个窗 → 按「接听」才进会话看详情。
 * 锁屏上要靠 showWhenLocked + turnScreenOn（Manifest 里也声明了），
 * 否则系统只会在锁屏后面挂着，人看不到。
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val sid = intent.getLongExtra("session_id", 0L)
        val tag = intent.getStringExtra("tag").orEmpty()
        val agent = intent.getStringExtra("agent").orEmpty().ifBlank { "Agent" }
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "$agent 来电话了" }
        val speak = intent.getStringExtra("speak").orEmpty()

        setContent {
            CallScreen(
                agent = agent,
                title = title,
                detail = speak,
                onAnswer = { answer(sid, speak, tag) },
                onHangup = { hangup(tag) },
            )
        }
    }

    /** 接听：停通报 + 撤通知 + 解锁 + 进那条会话的通话界面 */
    private fun answer(sessionId: Long, speak: String, tag: String) {
        CallAnnouncer.stop()
        if (tag.isNotBlank()) Notify.clear(this, tag)
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } catch (_: Exception) {
        }
        if (sessionId > 0L) {
            // 交给 MainActivity 的统一入口：openSession + pendingCall
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("session_id", sessionId)
                    putExtra("answer", true)
                    putExtra("speak", speak)
                }
            )
        }
        finish()
    }

    private fun hangup(tag: String) {
        CallAnnouncer.stop()
        if (tag.isNotBlank()) Notify.clear(this, tag)
        finish()
    }

    override fun onDestroy() {
        // 弹窗被划掉/返回退出，也应该停通报
        if (isFinishing) CallAnnouncer.stop()
        super.onDestroy()
    }

    companion object {
        fun intent(
            ctx: Context,
            sessionId: Long?,
            tag: String,
            agent: String,
            title: String,
            speak: String,
        ): Intent = Intent(ctx, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("session_id", sessionId ?: 0L)
            putExtra("tag", tag)
            putExtra("agent", agent)
            putExtra("title", title)
            putExtra("speak", speak)
        }
    }
}

@Composable
private fun CallScreen(
    agent: String,
    title: String,
    detail: String,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                "📞 来电",
                color = Color(0xFF8AB4F8),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                agent,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color = Color(0xFFB8B8C0),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "语音正在播报…",
                color = Color(0xFF9AA0A6),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Button(
                    onClick = onHangup,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                ) {
                    Text("挂断", fontSize = 17.sp, color = Color.White)
                }
                Button(
                    onClick = onAnswer,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E8E3E)),
                ) {
                    Text("接听", fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

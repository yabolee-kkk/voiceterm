package ai.local.voiceagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import ai.local.voiceagent.service.HubService
import ai.local.voiceagent.ui.AppNav
import ai.local.voiceagent.ui.SplashScreen
import ai.local.voiceagent.ui.theme.VoiceTheme

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开发便利：安装后可用 adb 一次性把地址/令牌写进设备（令牌不落构建产物）
        //   adb shell am start -n ai.local.voiceagent/.MainActivity -e baseUrl http://192.0.2.10:8097 -e token xxxx
        intent?.getStringExtra("baseUrl")?.takeIf { it.isNotBlank() }?.let { App.prefs.baseUrl = it }
        intent?.getStringExtra("token")?.takeIf { it.isNotBlank() }?.let { App.prefs.token = it }
        intent?.getStringExtra("publicUrl")?.let { App.prefs.publicUrl = it }
        App.prefs.activeBase = null          // 地址可能变了，旧选线结果作废
        // 探线是异步的：要让探线结果落地后能重建订阅，否则启动那次 refresh 用的是旧地址
        // （公网时旧地址=局域网地址，必然失败 → 首页「列 Agent 失败」+ 全部 Agent 显示离线）
        App.repick(thenRestart = true)

        if (App.prefs.configured) App.engine.start()
        startHubService()
        handleOpenSession(intent)

        setContent {
            VoiceTheme {
                // 启动画面：停 1.4 秒进主界面；从通知/接听进来时直接跳过（不耽误事）
                val jump = intent?.hasExtra("session_id") == true || App.openSession.value != null
                var splash by remember { mutableStateOf(!jump) }
                LaunchedEffect(Unit) {
                    if (splash) {
                        kotlinx.coroutines.delay(1400)
                        splash = false
                    }
                }
                if (splash) SplashScreen() else AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenSession(intent)
    }

    override fun onResume() {
        super.onResume()
        App.appVisible = true
        // 从最近任务划掉再来、或被系统回收过：这里兜底把连接和服务重新拉起来
        if (App.prefs.configured) {
            App.engine.start()
            startHubService()
        }
        android.util.Log.i("MainActivity", "onResume appVisible=true")
    }

    override fun onPause() {
        App.appVisible = false
        android.util.Log.i("MainActivity", "onPause appVisible=false")
        super.onPause()
    }

    /**
     * 前台服务：App 一进前台就拉起来，之后锁屏/切后台进程不会被回收，SSE 也不会被掐断。
     * Android 13+ 要用户允许通知（拒绝的话服务仍跑，只是看不见通知）。
     */
    private fun startHubService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, HubService::class.java))
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "前台服务起不来：${e.message}")
        }
    }

    /** 点通知/接听进来：把 session_id 交给导航层；接听还要让 Agent 先开口 */
    private fun handleOpenSession(intent: Intent?) {
        val sid = intent?.getLongExtra("session_id", 0L) ?: 0L
        if (sid <= 0L) return
        val answer = intent?.getBooleanExtra("answer", false) ?: false
        val speak = intent?.getStringExtra("speak").orEmpty()
        android.util.Log.i("MainActivity", "打开会话 session_id=$sid answer=$answer")
        App.openSession.value = sid
        if (answer) App.pendingCall.value = App.PendingCall(sid, speak)
        intent?.removeExtra("session_id")
        intent?.removeExtra("answer")
        intent?.removeExtra("speak")
    }
}

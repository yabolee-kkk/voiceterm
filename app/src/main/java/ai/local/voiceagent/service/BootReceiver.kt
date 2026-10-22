package ai.local.voiceagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import ai.local.voiceagent.App

/**
 * 开机自启：重启手机后把前台服务拉起来，不用人手动开一次 App。
 *
 * 为什么需要：前台服务只解决了"锁屏/切后台"，但换版本（adb install）或重启手机后
 * 进程被彻底杀掉，服务不会自己回来 —— 结果就是"一条通知都没有"。
 *
 * BOOT_COMPLETED 是受保护广播（只有系统能发），且开机时允许起点前台服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!App.prefs.configured) {
            Log.i(TAG, "还没配置地址/令牌，开机不自启")
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, HubService::class.java))
            Log.i(TAG, "开机/换版本后已拉起 HubService")
        } catch (e: Exception) {
            Log.w(TAG, "开机自启失败：${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

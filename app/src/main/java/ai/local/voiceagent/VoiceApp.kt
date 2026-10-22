package ai.local.voiceagent

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network

class VoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
        CrashLog.install(this)
        watchNetwork()
    }

    /**
     * 网络变化就重新选线：从家里出门（Wi-Fi→移动数据）自动切公网，回家自动切回局域网。
     */
    private fun watchNetwork() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    App.repick()
                }

                override fun onLost(network: Network) {
                    App.repick()
                }
            })
        }
    }
}

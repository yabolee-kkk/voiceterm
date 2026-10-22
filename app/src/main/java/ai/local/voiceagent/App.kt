package ai.local.voiceagent

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ai.local.voiceagent.data.AppDatabase
import ai.local.voiceagent.data.HubEngine
import ai.local.voiceagent.net.HubClient
import ai.local.voiceagent.net.Prefs

/**
 * 手工依赖容器（M2 阶段不引 Hilt，少一层构建风险）。
 * 生命周期 = 进程，初始化在 VoiceApp.onCreate。
 */
object App {
    lateinit var prefs: Prefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var client: HubClient
        private set
    lateinit var engine: HubEngine
        private set
    lateinit var appCtx: Context
        private set

    /** 点通知要打开的会话（导航层订阅它，开完置空） */
    val openSession = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** 来电"接听"：要进哪条会话、Agent 第一句说什么 */
    data class PendingCall(val sessionId: Long, val speak: String)
    val pendingCall = kotlinx.coroutines.flow.MutableStateFlow<PendingCall?>(null)

    /** App 是否在前台可见（决定要不要弹通知：后台/锁屏时必须弹） */
    @Volatile
    var appVisible: Boolean = false

    /** 正在探测线路（界面显示「正在选线…」） */
    val picking = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 最近一次选线结果，设置页显示 */
    val routeLabel = kotlinx.coroutines.flow.MutableStateFlow("")

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * 探一次线路并切换：局域网优先、公网兜底。地址变了才重建 SSE 订阅。
     * 调用时机：启动、网络变化（Wi-Fi⇄移动数据）、设置页点「重新选线」。
     */
    private val pickMutex = kotlinx.coroutines.sync.Mutex()

    fun repick(thenRestart: Boolean = true) {
        scope.launch {
          pickMutex.withLock {
            picking.value = true
            try {
                val lan = prefs.baseUrl
                val pub = prefs.publicUrl
                val wifi = ai.local.voiceagent.net.Endpoints.onWifi(appCtx)
                val r = ai.local.voiceagent.net.Endpoints.pick(lan, pub, wifi)
                val before = prefs.activeBase
                prefs.activeBase = r.url
                prefs.activeRoute = r.label
                routeLabel.value = r.label
                if (thenRestart && before != r.url) {
                    engine.restart()
                }
            } finally {
                picking.value = false
            }
          }
        }
    }

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        appCtx = ctx.applicationContext
        prefs = Prefs(ctx.applicationContext)
        db = Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "voice.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
        client = HubClient(prefs)
        engine = HubEngine(client, db, prefs, ctx.applicationContext)
    }
}

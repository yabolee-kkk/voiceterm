package ai.local.voiceagent.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.local.voiceagent.App
import ai.local.voiceagent.data.AppDatabase
import ai.local.voiceagent.data.HubEngine
import ai.local.voiceagent.data.STATUS_RUNNING
import ai.local.voiceagent.data.STATUS_WAITING
import ai.local.voiceagent.data.SessionEntity
import ai.local.voiceagent.net.AgentInfo
import ai.local.voiceagent.net.ConnState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/* ============================ 首页：只有 Agent 列表 ============================ */

class AgentsViewModel(
    private val engine: HubEngine,
    private val db: AppDatabase,
) : ViewModel() {

    val sessions: StateFlow<List<SessionEntity>> = db.sessionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val agents: StateFlow<List<AgentInfo>> = engine.agents
    val conn: StateFlow<Map<String, ConnState>> = engine.conn
    val globalError: StateFlow<String?> = engine.globalError

    init {
        engine.start()
        viewModelScope.launch { engine.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { engine.refresh() }
    }

    fun reconnect() {
        engine.restart()
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer { AgentsViewModel(App.engine, App.db) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onOpenAgent: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: AgentsViewModel = viewModel(factory = AgentsViewModel.factory())
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val err by vm.globalError.collectAsStateWithLifecycle()
    val configured = App.prefs.configured

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("AI Agent", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!configured) Banner("还没配置服务地址和令牌，点右上角设置", error = true)
            err?.let { Banner(it, error = true) }
            if (configured && agents.isNotEmpty() && conn.values.none { it == ConnState.OPEN }) {
                ReconnectBanner(onReconnect = { vm.reconnect() })
            }

            val ids = LinkedHashSet<String>()
            agents.forEach { ids.add(it.agentId) }
            sessions.forEach { ids.add(it.agentId) }
            val agentIds = ids.toList()

            if (agentIds.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (configured) "还没有连上任何 Agent，点右上角刷新" else "先完成设置",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(agentIds, key = { it }) { agentId ->
                        val list = sessions.filter { it.agentId == agentId }
                        AgentRow(
                            agentId = agentId,
                            conn = conn[agentId],
                            sessionCount = list.size,
                            lastAt = list.maxOfOrNull { it.lastAt } ?: 0L,
                            lastText = list.maxByOrNull { it.lastAt }?.lastText.orEmpty(),
                            running = list.any { it.status == STATUS_RUNNING },
                            waiting = list.any { it.status == STATUS_WAITING },
                            onClick = { onOpenAgent(agentId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(
    agentId: String,
    conn: ConnState?,
    sessionCount: Int,
    lastAt: Long,
    lastText: String,
    running: Boolean,
    waiting: Boolean,
    onClick: () -> Unit,
) {
    val (statusText, statusColor) = when {
        conn != ConnState.OPEN -> "离线" to MaterialTheme.colorScheme.onSurfaceVariant
        running -> "运行中" to MaterialTheme.colorScheme.primary
        waiting -> "等你回话" to MaterialTheme.colorScheme.secondary
        else -> "就这样吧" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    agentId.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        agentId,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 11.sp, color = statusColor)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    if (sessionCount == 0) "还没有会话" else "$sessionCount 个会话" +
                        if (lastAt > 0) " · ${timeLabel(lastAt)}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lastText.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        lastText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/* ============================ Agent 详情：新建会话 + 历史会话 ============================ */

class AgentSessionsViewModel(
    private val agentId: String,
    private val engine: HubEngine,
    db: AppDatabase,
) : ViewModel() {

    val sessions: StateFlow<List<SessionEntity>> = db.sessionDao().observeByAgent(agentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conn: StateFlow<Map<String, ConnState>> = engine.conn

    /** 新建会话：不起名字（第一句话就是名字），建完直接进去 */
    fun create(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = engine.newSession(agentId)
            onCreated(id)
        }
    }

    companion object {
        fun factory(agentId: String) = viewModelFactory {
            initializer { AgentSessionsViewModel(agentId, App.engine, App.db) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSessionsScreen(
    agentId: String,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: AgentSessionsViewModel = viewModel(
        key = "agent-$agentId",
        factory = AgentSessionsViewModel.factory(agentId),
    )
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    val configured = App.prefs.configured

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agentId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            if (conn[agentId] == ConnState.OPEN) "已连接" else "离线",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 新建会话：一个大按钮，点了直接进去说话，不问名字
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(enabled = configured) { vm.create { id -> onOpen(id) } },
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "新建会话",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        "还没有会话。点上面「新建会话」，说什么它就是这条会话的名字。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp, start = 20.dp, end = 20.dp),
                    )
                }
            } else {
                Text(
                    "历史会话 ${sessions.size} 条",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(
                            session = s,
                            conn = conn[s.agentId],
                            onClick = { onOpen(s.id) },
                        )
                    }
                }
            }
        }
    }
}

/* ============================ 复用的小组件 ============================ */

@Composable
private fun ReconnectBanner(onReconnect: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReconnect),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "没连上服务（Agent 收不到消息）· 点这里重连",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.weight(1f))
            Text("重连", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Banner(text: String, error: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (error) Color(0xFF3A1418) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(session: SessionEntity, conn: ConnState?, onClick: () -> Unit) {
    val (statusText, statusColor) = when {
        conn != ConnState.OPEN -> "离线" to MaterialTheme.colorScheme.onSurfaceVariant
        session.status == STATUS_RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        session.status == STATUS_WAITING -> "等你回话" to MaterialTheme.colorScheme.secondary
        else -> "空闲" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.title.ifBlank { "新会话" },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusText, fontSize = 11.sp, color = statusColor)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.lastText.ifBlank { "还没有消息" },
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (session.lastAt > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            timeLabel(session.lastAt),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (session.unread > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (session.unread > 9) "9+" else session.unread.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
    }
}

/** 今天显示 HH:mm，更早显示 M-d HH:mm */
private fun timeLabel(at: Long): String {
    if (at <= 0) return ""
    val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val now = java.util.Calendar.getInstance()
    val hm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    val sameDay = c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        c.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) hm else "${c.get(java.util.Calendar.MONTH) + 1}-${c.get(java.util.Calendar.DAY_OF_MONTH)} $hm"
}

package ai.local.voiceagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ai.local.voiceagent.App
import ai.local.voiceagent.ui.detail.ChatScreen
import ai.local.voiceagent.ui.list.AgentSessionsScreen
import ai.local.voiceagent.ui.list.AgentsScreen
import ai.local.voiceagent.ui.settings.SettingsScreen

object Routes {
    const val LIST = "list"              // 首页：Agent 列表
    const val SETTINGS = "settings"
    const val AGENT = "agent/{agentId}"  // 某个 Agent：新建会话 + 历史会话
    const val CHAT = "chat/{sessionId}"
    fun chat(sessionId: Long) = "chat/$sessionId"
    fun agent(agentId: String) = "agent/" + android.net.Uri.encode(agentId)
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val open by App.openSession.collectAsState()
    // 点通知进来 → 直接进对应会话
    LaunchedEffect(open) {
        val id = open ?: return@LaunchedEffect
        App.openSession.value = null
        runCatching { nav.navigate(Routes.chat(id)) { launchSingleTop = true } }
    }
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            AgentsScreen(
                onOpenAgent = { agentId -> nav.navigate(Routes.agent(agentId)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.AGENT,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { entry ->
            val agentId = android.net.Uri.decode(entry.arguments?.getString("agentId") ?: "")
            AgentSessionsScreen(
                agentId = agentId,
                onOpen = { id -> nav.navigate(Routes.chat(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("sessionId") ?: 0L
            ChatScreen(sessionId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

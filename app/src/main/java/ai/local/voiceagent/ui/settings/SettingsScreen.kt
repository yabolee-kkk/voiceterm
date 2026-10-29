package ai.local.voiceagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.local.voiceagent.App
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var base by remember { mutableStateOf(App.prefs.baseUrl) }
    var pub by remember { mutableStateOf(App.prefs.publicUrl) }
    var token by remember { mutableStateOf(App.prefs.token) }
    var device by remember { mutableStateOf(App.prefs.deviceId) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        App.prefs.save(base, token)
        App.prefs.deviceId = device
        App.prefs.publicUrl = pub
        App.prefs.activeBase = null          // 配置变了，重新选线
        App.repick(thenRestart = false)
        App.engine.restart()
        result = "已保存，正在重连…"
        ok = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            OutlinedTextField(
                value = base,
                onValueChange = { base = it },
                label = { Text("服务地址") },
                placeholder = { Text("http://192.168.1.18:8097") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pub,
                onValueChange = { pub = it },
                label = { Text("公网地址（可留空）") },
                placeholder = { Text("https://voice.borong.de5.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "出门时自动改走这条；家里连得上 Wi-Fi 就走上面的局域网地址（更快）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("访问令牌") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "令牌只存在本机；来源 ~/.config/voice-hub/api-token（不要把值贴进 Git 或文档）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = device,
                onValueChange = { device = it },
                label = { Text("设备标识") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            val picking by App.picking.collectAsState()
            val routeNow by App.routeLabel.collectAsState()
            Text(
                if (picking) "正在选线…" else "当前线路：${routeNow.ifBlank { App.prefs.activeRoute.ifBlank { "未探测" } }}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Row {
                Button(onClick = { save() }) { Text("保存并重连") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { App.repick() }) { Text("重新选线") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        val r = App.engine.testConnection()
                        ok = r.isSuccess
                        result = r.getOrElse { "连接失败：${it.message}" }
                    }
                }) { Text("测试连接") }
            }
            Spacer(Modifier.height(14.dp))
            result?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "当前版本 ${ai.local.voiceagent.BuildConfig.VERSION_NAME}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

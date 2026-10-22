package ai.local.voiceagent.ui.card

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.local.voiceagent.ui.markdown.MarkdownText
import ai.local.voiceagent.ui.markdown.inline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * cc-connect 卡片渲染器。元素只有六种（/help、/model 实抓载荷确认）：
 *
 *   {"type":"markdown","content":"..."}
 *   {"type":"divider"}
 *   {"type":"note","text":"小字"}
 *   {"type":"actions","buttons":[{"text","value","btn_type":"primary|default|danger"}],"layout":"row|equal_columns"}
 *   {"type":"list_item","text":"加粗命令 + 说明","btn_text":"▶","btn_value":"act:/new","btn_type":"default"}
 *   {"type":"select","options":[{"text","value"}],"init_value":"","placeholder":"选择模型"}
 *
 * 卡片外层：{"header":{"title":"...","color":"indigo"},"elements":[...]}
 * 按钮 value 带回传时原样送回（act: / cmd: / nav: / perm: 前缀由 cc-connect 解释）
 */
@Composable
fun AgentCard(cardJson: String, onAction: (String) -> Unit) {
    val card = remember(cardJson) { runCatching { JSONObject(cardJson) }.getOrNull() } ?: run {
        Text(cardJson.take(200), fontSize = 12.sp)
        return
    }
    val header = card.optJSONObject("header")
    val elements = card.optJSONArray("elements") ?: JSONArray()

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        header?.optString("title")?.takeIf { it.isNotBlank() }?.let { title ->
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
        }
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            CardElement(el, onAction)
            if (i < elements.length() - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardElement(el: JSONObject, onAction: (String) -> Unit) {
    when (el.optString("type")) {
        "markdown" -> MarkdownText(el.optString("content"))

        "divider" -> HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        "note" -> Text(
            el.optString("text"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        "actions" -> {
            val buttons = el.optJSONArray("buttons") ?: JSONArray()
            val equal = el.optString("layout") == "equal_columns"
            if (equal) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until buttons.length()) {
                        val b = buttons.optJSONObject(i) ?: continue
                        ActionButton(
                            text = b.optString("text"),
                            primary = b.optString("btn_type") == "primary",
                            danger = b.optString("btn_type") == "danger",
                            modifier = Modifier.weight(1f),
                            onClick = { onAction(b.optString("value")) },
                        )
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until buttons.length()) {
                        val b = buttons.optJSONObject(i) ?: continue
                        ActionButton(
                            text = b.optString("text"),
                            primary = b.optString("btn_type") == "primary",
                            danger = b.optString("btn_type") == "danger",
                            onClick = { onAction(b.optString("value")) },
                        )
                    }
                }
            }
        }

        "list_item" -> {
            val raw = el.optString("text")
            val m = Regex("^\\*\\*(.+?)\\*\\*\\s*(.*)$").find(raw)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onAction(el.optString("btn_value")) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m != null) {
                    Text(
                        m.groupValues[1],
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 64.dp, max = 120.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        m.groupValues[2],
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        inline(raw, MaterialTheme.colorScheme.onSurface),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                el.optString("btn_text").takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        "select" -> {
            val options = el.optJSONArray("options") ?: JSONArray()
            var expanded by remember { mutableStateOf(false) }
            val init = el.optString("init_value")
            val initText = (0 until options.length())
                .mapNotNull { options.optJSONObject(it) }
                .firstOrNull { it.optString("value") == init }
                ?.optString("text")
                ?: el.optString("placeholder").ifBlank { "请选择" }

            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(initText, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("▾", fontSize = 12.sp)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    for (i in 0 until options.length()) {
                        val o = options.optJSONObject(i) ?: continue
                        DropdownMenuItem(
                            text = { Text(o.optString("text"), fontSize = 13.sp) },
                            onClick = {
                                expanded = false
                                onAction(o.optString("value"))
                            },
                        )
                    }
                }
            }
        }

        else -> Text(el.toString().take(200), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = when {
        danger -> ButtonDefaults.buttonColors(
            containerColor = Color(0x33FF8A80),
            contentColor = MaterialTheme.colorScheme.error,
        )
        primary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        colors = colors,
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 权限询问：按钮是二维数组 [[{Text,Data}]]（key 首字母大写，与卡片不同）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionButtons(buttonsJson: String, content: String?, onAction: (String) -> Unit) {
    val rows = remember(buttonsJson) { runCatching { JSONArray(buttonsJson) }.getOrNull() } ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x22FFB74D), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        content?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        for (r in 0 until rows.length()) {
            val row = rows.optJSONArray(r) ?: continue
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until row.length()) {
                    val b = row.optJSONObject(i) ?: continue
                    val data = b.optString("Data")
                    ActionButton(
                        text = b.optString("Text"),
                        primary = data.contains("allow"),
                        danger = data.contains("deny"),
                        onClick = { onAction(data) },
                    )
                }
            }
        }
    }
}

/**
 * 附件：image 直接显示，file/audio 显示成一行的信息条。
 * 上游载荷字段不固定，这里按 url / imageUrl / data(base64) / path 逐个尝试。
 */
private fun humanSize(n: Long): String = when {
    n <= 0 -> ""
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format("%.1f MB", n / 1048576.0)
}

@Composable
fun AttachmentView(kind: String, payloadJson: String) {
    val o = remember(payloadJson) { runCatching { JSONObject(payloadJson) }.getOrNull() }

    // 多附件（一次选了好几个）：按列表逐行显示 + 一行发送状态
    val multi = o?.optJSONArray("files")
    if (multi != null && multi.length() > 0) {
        val status = o.optString("status").orEmpty()
        val hint = when (status) {
            "sending" -> "发送中…"
            "sent" -> "已发送"
            "failed" -> "发送失败：" + o.optString("error").orEmpty().take(40)
            else -> ""
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (i in 0 until multi.length()) {
                val f = multi.optJSONObject(i) ?: continue
                val name = f.optString("file_name").ifBlank { f.optString("name") }
                val size = f.optLong("size", 0)
                val mime = f.optString("mime")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mime.startsWith("image/")) "🖼" else "📄", fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name.ifBlank { "[文件]" },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(humanSize(size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (hint.isNotBlank()) {
                Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    when (kind) {
        "image" -> {
            val url = listOf("url", "image_url", "imageUrl", "href").firstNotNullOfOrNull { k ->
                o?.optString(k)?.takeIf { it.startsWith("http") }
            }
            val b64 = listOf("data", "base64", "image_base64").firstNotNullOfOrNull { k ->
                o?.optString(k)?.takeIf { it.length > 100 }
            }
            val localPath = o?.optString("path")?.takeIf { it.isNotBlank() }
            var bmp by remember(payloadJson) { mutableStateOf<Bitmap?>(null) }
            var err by remember(payloadJson) { mutableStateOf<String?>(null) }

            LaunchedEffect(payloadJson) {
                bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            b64 != null -> BitmapFactory.decodeByteArray(
                                Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT), 0,
                                Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT).size,
                            )
                            url != null -> OkHttpClient().newCall(Request.Builder().url(url).build())
                                .execute().use { r -> r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                            localPath != null -> BitmapFactory.decodeFile(localPath)
                            else -> null
                        }
                    }.getOrNull()
                }
                if (bmp == null) err = "图片解析失败"
            }

            var showFull by remember(payloadJson) { mutableStateOf(false) }
            val image = bmp
            when {
                image != null -> {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "点开看大图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .clickable { showFull = true },
                    )
                    if (showFull) {
                        ai.local.voiceagent.ui.ImageViewer(
                            bitmap = image.asImageBitmap(),
                            onClose = { showFull = false },
                        )
                    }
                }
                err != null -> Text(err!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text("[图片加载中…]", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        "file" -> {
            val name = listOf("file_name", "fileName", "name", "path").firstNotNullOfOrNull { k ->
                o?.optString(k)?.takeIf { it.isNotBlank() }
            }
            val size = o?.optLong("size", 0) ?: 0
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            var note by remember(payloadJson) { mutableStateOf<String?>(null) }
            // Agent 发来的文件带着 base64 正文：落到 cache 再交给系统应用（PDF 用 Drive 的 PdfViewer）
            val open = {
                scope.launch {
                    val b64 = listOf("data", "base64", "file_base64").firstNotNullOfOrNull { k ->
                        o?.optString(k)?.takeIf { it.length > 16 }
                    }
                    val localPath = listOf("path", "local_path").firstNotNullOfOrNull { k ->
                        o?.optString(k)?.takeIf { it.isNotBlank() }
                    }
                    val mime = ai.local.voiceagent.ui.mimeOf(name ?: "", o?.optString("mime_type"))
                    val f = withContext(Dispatchers.IO) {
                        runCatching {
                            // 一律先复制进 cache/open/：FileProvider 只认自己声明的目录，
                            // 外面那些 /storage/emulated/0/... 的路径直接给 content:// 会抛异常
                            val bytes = when {
                                b64 != null -> Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
                                localPath != null -> java.io.File(localPath).readBytes()
                                else -> null
                            } ?: return@runCatching null
                            ai.local.voiceagent.ui.saveToCache(ctx, name ?: "file.bin", bytes)
                        }.getOrNull()
                    }
                    if (f == null || !f.exists()) {
                        note = "这个文件只有名字，没有内容（发的时候没带上正文）"
                        return@launch
                    }
                    val ok = ai.local.voiceagent.ui.openWithSystem(ctx, f, mime)
                    if (!ok) note = "手机上没有能打开 ${mime} 的应用"
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .clickable { open() }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📄", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(name ?: "[文件]", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    val status = o?.optString("status").orEmpty()
                    val hint = when (status) {
                        "sending" -> "发送中…"
                        "sent" -> "已发送"
                        "failed" -> "发送失败：" + o?.optString("error").orEmpty().take(40)
                        else -> ""
                    }
                    val sizeText = when {
                        size <= 0 -> ""
                        size < 1024 -> "$size B"
                        size < 1024 * 1024 -> "${size / 1024} KB"
                        else -> String.format("%.1f MB", size / 1048576.0)
                    }
                    val line = listOf(sizeText, hint).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) {
                        Text(line, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    note?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
                    if (note == null) {
                        Text("点一下打开", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        else -> Text(
            if (kind == "audio") "[语音消息]" else "[${kind}]",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

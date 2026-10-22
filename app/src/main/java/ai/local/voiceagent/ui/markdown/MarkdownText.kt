package ai.local.voiceagent.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（M3 卡片 markdown 元素 + Agent 正文都用它）。
 *
 * 支持：围栏代码块 / 行内代码 / 标题 / 无序与有序列表 / 引用 / 分隔线 / 表格行 / 加粗 / 斜体 / 删除线 / 链接。
 * 不支持（按原文显示）：嵌套列表、HTML、脚注、图片。
 * 不引第三方 markdown 库：手机端只需要这几类，多一个依赖多一份构建风险。
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    Column(modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // 围栏代码块
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                i++ // 跳过结束围栏
                CodeBlock(code.toString().trimEnd(), lang)
                continue
            }

            when {
                trimmed.isBlank() -> Spacer(Modifier.height(6.dp))

                trimmed.matches(Regex("^([-*_])\\1{2,}$")) -> HorizontalDivider(
                    Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )

                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    val text = trimmed.drop(level).trim()
                    InlineText(
                        text = inline(text, color),
                        fontSize = when (level) {
                            1 -> 18.sp
                            2 -> 16.sp
                            else -> 15.sp
                        },
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }

                trimmed.startsWith("> ") -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    InlineText(
                        text = inline(trimmed.removePrefix("> "), MaterialTheme.colorScheme.onSurfaceVariant),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                bulletOf(trimmed) != null -> Row(verticalAlignment = Alignment.Top) {
                    Text(bulletOf(trimmed)!!, fontSize = 13.sp, color = color)
                    Spacer(Modifier.width(6.dp))
                    InlineText(text = inline(stripBullet(trimmed), color), fontSize = 14.sp, color = color, modifier = Modifier.weight(1f))
                }

                trimmed.startsWith("|") -> Text(
                    trimmed,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> InlineText(text = inline(trimmed, color), fontSize = 14.sp, color = color)
            }
            i++
        }
    }
}

private fun bulletOf(line: String): String? = when {
    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> "•"
    Regex("^\\d+\\.\\s").containsMatchIn(line) -> "›"
    else -> null
}

private fun stripBullet(line: String): String = when {
    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> line.substring(2)
    else -> line.replaceFirst(Regex("^\\d+\\.\\s+"), "")
}

/**
 * 带可点链接的文本：AnnotatedString 里挂了 "URL" 注解，点中链接就交给系统浏览器。
 * （Compose 1.6 没有 LinkAnnotation，所以用 ClickableText + 字符串注解）
 */
@Composable
private fun InlineText(
    text: AnnotatedString,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.text.ClickableText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(fontSize = fontSize, color = color)
            .let { if (fontWeight != null) it.copy(fontWeight = fontWeight) else it },
        maxLines = maxLines,
        onClick = { offset ->
            val hit = text.getStringAnnotations("URL", offset, offset).firstOrNull()
            if (hit != null) openUrl(ctx, hit.item)
        },
    )
}

@Composable
private fun CodeBlock(code: String, lang: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        if (lang.isNotBlank()) {
            Text(
                lang,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            code,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

private val INLINE = Regex(
    "(\\*\\*[^*]+\\*\\*)|(`[^`]+`)|(~~[^~]+~~)|(\\[[^\\]]+\\]\\([^)]*\\))|(\\*[^*\\n]+\\*)|(_[^_\\n]+_)" +
        // 链接：http(s)://… 与 www.… 与裸域名（带常见后缀），点击可跳转
        "|(https?://[^\\s<>\"'）)】]+)" +
        "|(www\\.[^\\s<>\"'）)】]+)" +
        "|((?:[a-zA-Z0-9][a-zA-Z0-9-]*\\.)+(?:com|cn|net|org|io|dev|ai|app|top|xyz|cc|me|tv|info)(?::\\d+)?(?:/[^\\s<>\"'）)】]*)?)"
)

private val LOOKS_LIKE_DOMAIN = Regex(
    "^(?:[a-zA-Z0-9][a-zA-Z0-9-]*\\.)+(?:com|cn|net|org|io|dev|ai|app|top|xyz|cc|me|tv|info)(?::\\d+)?(?:/.*)?$"
)

/** 链接颜色（跟 iOS/安卓习惯一致的蓝） */
private val LINK_COLOR = Color(0xFF4DA3FF)

/** 去掉链接尾巴上的中文标点、括号等（"看这个 https://x.com/a，" 里的逗号不算） */
private fun cleanUrl(raw: String): String =
    raw.trimEnd('.', ',', '。', '，', ')', '）', ']', '】', '」', '"', '\'', '!', '？', '?', '；', ';', '：')

/** 点链接：交给系统浏览器 / 对应 App */
fun openUrl(ctx: android.content.Context, url: String) {
    val full = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(full))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "打不开这个链接：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 行内格式：加粗 / 行内代码 / 删除线 / 链接 / 斜体 */
fun inline(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }

            token.startsWith("`") -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x22FFFFFF),
                    fontSize = 13.sp,
                )
            ) { append(token.removeSurrounding("`")) }

            token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                append(token.removeSurrounding("~~"))
            }

            token.startsWith("[") -> {
                val label = token.substringAfter("[").substringBefore("]")
                withStyle(SpanStyle(color = Color(0xFF7DD3FC), textDecoration = TextDecoration.Underline)) {
                    append(label)
                }
            }

            // 链接：蓝字下划线 + 挂 URL 注解（点击时按注解取地址）
            token.startsWith("http://") || token.startsWith("https://") ||
                token.startsWith("www.") || LOOKS_LIKE_DOMAIN.matches(token) -> {
                val url = cleanUrl(token)
                val start = length
                withStyle(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)) {
                    append(url)
                }
                addStringAnnotation("URL", url, start, length)
            }

            else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append(token.trim('*', '_'))
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

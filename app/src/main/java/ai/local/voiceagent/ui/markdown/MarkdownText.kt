package ai.local.voiceagent.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 * 支持：围栏代码块（语法高亮 + 一键复制）/ 行内代码 / 标题 / 无序与有序列表 / 引用 / 分隔线 /
 *      表格（按等宽面板对齐显示）/ 加粗 / 斜体 / 删除线 / 链接。
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

            // 表格：标准写法是「| 表头 |」+「|---|---|」；裸的连续 | 行也当表格处理
            if (trimmed.startsWith("|") && looksLikeTable(lines, i)) {
                val rows = ArrayList<List<String>>()
                var aligns: List<Char> = emptyList()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val t = lines[i].trim()
                    if (isTableSeparator(t)) aligns = tableAligns(t) else rows.add(splitCells(t))
                    i++
                }
                TableBlock(rows, aligns, color)
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

// ---------------------------------------------------------------- 表格

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim().trim('|').trim()
    if (t.isEmpty()) return false
    val cells = t.split('|')
    return cells.all { c ->
        val s = c.trim()
        s.isNotEmpty() && s.contains('-') && s.all { it == '-' || it == ':' }
    }
}

private fun looksLikeTable(lines: List<String>, i: Int): Boolean {
    val next = lines.getOrNull(i + 1)?.trim() ?: return false
    if (isTableSeparator(next)) return true
    return next.startsWith("|")
}

private fun splitCells(line: String): List<String> =
    line.trim().trim('|').split('|').map { cleanCell(it) }

/** 单元格里去掉 markdown 装饰符（`反引号`、**加粗**、~~删除线~~），表格里按纯文本对齐才对得齐 */
private fun cleanCell(raw: String): String =
    raw.trim().replace("`", "").replace("**", "").replace("~~", "").replace("__", "")

private fun tableAligns(sep: String): List<Char> = splitCells(sep).map { c ->
    val l = c.startsWith(":")
    val r = c.endsWith(":")
    when {
        l && r -> 'c'
        r -> 'r'
        else -> 'l'
    }
}

/** 中文按 2 个字符宽算，这样等宽表格才对得齐 */
private fun cellWidth(s: String): Int = s.fold(0) { acc, ch -> acc + if (ch.code > 0x2E80) 2 else 1 }

private fun padCell(s: String, width: Int, align: Char): String {
    val gap = (width - cellWidth(s)).coerceAtLeast(0)
    return when (align) {
        'r' -> " ".repeat(gap) + s
        'c' -> {
            val l = gap / 2
            " ".repeat(l) + s + " ".repeat(gap - l)
        }
        else -> s + " ".repeat(gap)
    }
}

/**
 * 表格渲染：算出屏幕能放多少等宽字符 → 列宽自适应 + 单元格换行，整表落在屏幕内不横向溢出。
 *
 * 为什么不做网格线/可排序：手机上列一多就挤成两字一行。
 * 这里的目标只有一个——「一屏能读完、不截断」：
 *   · 第一列（通常是名字）最多占 1/3 宽度，超了就折行；
 *   · 其余列平分剩下的宽度，同样折行；
 *   · 表头下画一条与列宽等长的虚线。
 */
@Composable
private fun TableBlock(rows: List<List<String>>, aligns: List<Char>, color: Color) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    if (cols == 0) return

    val fs = 11.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 等宽字体每字符约 0.6em；扣掉面板左右 padding
        val avail = ((maxWidth.value - 24f) / (fs * 0.6f)).toInt().coerceIn(18, 200)

        // 两列表 + 说明很长时，左右分栏在手机上必然把中文拆得七零八落 →
        // 改成「名字一行 + 说明整宽折行」的堆叠式，宁可多占几行也要读得顺。
        val stacked = cols == 2 && rows.drop(1).any { cellWidth(it.getOrElse(1) { "" }) > avail / 2 }
        val body = if (stacked && rows.size > 1) rows.drop(1) else rows

        val sb = StringBuilder()
        if (stacked) {
            for (r in body) {
                sb.append("· ").append(r.getOrElse(0) { "" }).append('\n')
                wrapCell(r.getOrElse(1) { "" }, (avail - 2).coerceAtLeast(8)).forEach {
                    sb.append("  ").append(it).append('\n')
                }
                sb.append('\n')
            }
        } else {
            val wantFirst = rows.maxOf { cellWidth(it.getOrElse(0) { "" }) }
            val firstW = minOf(wantFirst, maxOf(10, (avail * 55) / 100))
            val restW = if (cols > 1) ((avail - firstW - 2) / (cols - 1)).coerceAtLeast(8) else 0
            val widths = IntArray(cols) { if (it == 0) firstW else restW }

            fun rowsOf(cells: List<String>): List<String> {
                val chunks = (0 until cols).map { c -> wrapCell(cells.getOrElse(c) { "" }, widths[c]) }
                val lines = chunks.maxOf { it.size }
                return (0 until lines).map { ln ->
                    (0 until cols).joinToString("  ") { c ->
                        padCell(chunks[c].getOrElse(ln) { "" }, widths[c], aligns.getOrElse(c) { 'l' })
                    }.trimEnd()
                }
            }

            rowsOf(rows.first()).forEach { sb.append(it).append('\n') }
            sb.append(widths.joinToString("  ") { "-".repeat(it) }).append('\n')
            for (r in body) rowsOf(r).forEach { sb.append(it).append('\n') }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                sb.toString().trimEnd(),
                fontSize = fs.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
    }
}

/** 按等宽宽度折行（中文算 2 格），返回每行文本 */
private fun wrapCell(s: String, width: Int): List<String> {
    if (width <= 2 || s.isEmpty()) return listOf(s)
    if (cellWidth(s) <= width) return listOf(s)
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var w = 0
    for (ch in s) {
        val cw = if (ch.code > 0x2E80) 2 else 1
        // 避头尾：句读符号不要落到下一行行首，宁可这一行多占一格
        val noLineHead = ch in "，。、；：！？）》」』”’,.;:!?)]}"
        if (w + cw > width && sb.isNotEmpty() && !noLineHead) {
            out.add(sb.toString())
            sb.setLength(0)
            w = 0
        }
        sb.append(ch)
        w += cw
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out.ifEmpty { listOf("") }
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

// ---------------------------------------------------------------- 代码块

@Composable
private fun CodeBlock(code: String, lang: String) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                lang.ifBlank { "code" },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 复制按钮：只复制代码本身，不含语言标签
            // 只依赖 core icons（没有扩展图标包），所以图标用文字符号代替
            Row(
                Modifier
                    .clickable { copyToClipboard(ctx, code) }
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⧉", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("复制", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            highlight(code, lang),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .padding(end = 6.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

private fun copyToClipboard(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("代码", text))
        Toast.makeText(ctx, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ---------------------------------------------------------------- 语法高亮

/** 高亮配色（深色底上读得清） */
private val C_KEY = Color(0xFF7AA2F7)
private val C_STR = Color(0xFF9ECE6A)
private val C_CMT = Color(0xFF7C8699)
private val C_NUM = Color(0xFFE0AF68)
private val C_FUN = Color(0xFF7DCFFF)
private val C_ANN = Color(0xFFBB9AF7)

private val GENERIC_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case", "default",
    "try", "catch", "finally", "throw", "throws", "new", "class", "interface", "enum", "struct",
    "fun", "val", "var", "let", "const", "def", "fn", "func", "function", "public", "private",
    "protected", "internal", "static", "final", "abstract", "override", "open", "data", "object",
    "import", "package", "from", "export", "as", "in", "is", "not", "and", "or", "null", "nil",
    "true", "false", "void", "int", "long", "float", "double", "char", "bool", "boolean", "string",
    "String", "Int", "Long", "Double", "Float", "Boolean", "Unit", "self", "this", "super", "lambda",
    "match", "when", "async", "await", "yield", "with", "pass", "raise", "elif", "then", "fi", "esac",
    "use", "impl", "trait", "pub", "mut", "where", "module", "type", "go", "defer", "range",
    "select", "chan", "map", "end", "local", "echo", "source", "sudo", "cd",
    "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE", "JOIN", "GROUP",
    "ORDER", "BY", "LIMIT", "VALUES", "INTO", "SET", "AND", "OR", "NULL", "NOT",
)

private fun lineCommentOf(lang: String): List<String> = when (lang.lowercase()) {
    "python", "py", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml", "ini", "conf",
    "dockerfile", "makefile", "r", "rb", "ruby", "perl" -> listOf("#")
    "sql", "lua" -> listOf("--")
    else -> listOf("//")
}

private fun hasBlockComment(lang: String): Boolean = when (lang.lowercase()) {
    "python", "py", "sh", "bash", "shell", "zsh", "yaml", "yml", "toml", "ini", "conf",
    "dockerfile", "makefile", "sql", "lua" -> false
    else -> true
}

/**
 * 极简词法高亮：字符串 / 注释 / 关键字 / 数字 / 函数名 / 注解 六类上色。
 *
 * 为什么不上完整 grammar：手机端只求「一眼分得清代码结构」，
 * 自己写几十行扫描器、零依赖，不会因为某个库升级而崩。
 */
fun highlight(code: String, lang: String): AnnotatedString = buildAnnotatedString {
    val lc = lineCommentOf(lang)
    val block = hasBlockComment(lang)
    val n = code.length
    var i = 0

    fun emit(s: String, style: SpanStyle?) {
        if (style == null) append(s) else withStyle(style) { append(s) }
    }

    while (i < n) {
        val c = code[i]

        // 行注释
        val lcHit = lc.firstOrNull { code.startsWith(it, i) }
        if (lcHit != null) {
            val j = code.indexOf('\n', i).let { if (it < 0) n else it }
            emit(code.substring(i, j), SpanStyle(color = C_CMT))
            i = j
            continue
        }

        // 块注释
        if (block && code.startsWith("/*", i)) {
            val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
            emit(code.substring(i, end), SpanStyle(color = C_CMT))
            i = end
            continue
        }

        // 三引号
        if (code.startsWith("\"\"\"", i)) {
            val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
            emit(code.substring(i, end), SpanStyle(color = C_STR))
            i = end
            continue
        }

        // 字符串
        if (c == '"' || c == '\'' || c == '`') {
            var j = i + 1
            while (j < n) {
                if (code[j] == '\\') { j += 2; continue }
                if (code[j] == c) { j++; break }
                if (code[j] == '\n' && c != '`') break
                j++
            }
            val end = j.coerceAtMost(n)
            emit(code.substring(i, end), SpanStyle(color = C_STR))
            i = end
            continue
        }

        // @注解
        if (c == '@' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')) {
            var j = i + 1
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
            emit(code.substring(i, j), SpanStyle(color = C_ANN))
            i = j
            continue
        }

        // 数字
        if (c.isDigit() && (i == 0 || (!code[i - 1].isLetterOrDigit() && code[i - 1] != '_'))) {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
            emit(code.substring(i, j), SpanStyle(color = C_NUM))
            i = j
            continue
        }

        // 标识符
        if (c.isLetter() || c == '_') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
            val word = code.substring(i, j)
            val style = when {
                GENERIC_KEYWORDS.contains(word) -> SpanStyle(color = C_KEY, fontWeight = FontWeight.Medium)
                j < n && code[j] == '(' -> SpanStyle(color = C_FUN)
                else -> null
            }
            emit(word, style)
            i = j
            continue
        }

        append(c)
        i++
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

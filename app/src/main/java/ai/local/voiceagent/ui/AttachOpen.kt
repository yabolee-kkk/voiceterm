package ai.local.voiceagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import java.io.File

/**
 * 附件打开：图片走 App 内全屏缩放，PDF/文档这类交给系统应用（content:// 走 FileProvider）。
 */
fun mimeOf(name: String, fallback: String? = null): String {
        val n = name.lowercase()
        val ext = n.substringAfterLast('.', "")
        val m = when (ext) {
            "pdf" -> "application/pdf"
            "txt", "md", "log", "json", "csv" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            else -> ""
        }
        return m.ifBlank { fallback?.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "application/octet-stream" }
    }

/** 把字节写进 cache/open/，文件名尽量保留原来的（便于系统应用显示） */
fun saveToCache(ctx: Context, name: String, bytes: ByteArray): File {
        val dir = File(ctx.cacheDir, "open").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").ifBlank { "file.bin" }
        val f = File(dir, safe)
        f.writeBytes(bytes)
        return f
    }

/** 交给系统打开；没有能处理的应用就返回 false */
fun openWithSystem(ctx: Context, f: File, mime: String): Boolean = runCatching {
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    }.getOrDefault(false)


/**
 * 全屏看图：双指缩放（1~8 倍）、拖动平移、双击放大/还原。
 */
@Composable
fun ImageViewer(bitmap: ImageBitmap, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale > 1.02f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        })
                    }
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offsetX, translationY = offsetY,
                    ),
            )

            Text(
                "✕",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
                    .pointerInput(Unit) { detectTapGestures { onClose() } },
            )

            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("双指缩放 · 双击放大 · 拖动平移 · 点 ✕ 关闭", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                if (scale > 1.05f) {
                    Text("${"%.1f".format(scale)}×", color = Color(0xFF808080), fontSize = 11.sp)
                }
            }
        }
    }
}

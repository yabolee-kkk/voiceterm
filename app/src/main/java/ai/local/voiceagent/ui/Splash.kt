package ai.local.voiceagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.local.voiceagent.R

/**
 * 启动画面：一张"图片"（绿色圆 + 电话话筒图形）+ 名字，停 1.4 秒再进主界面。
 * 图形用 vector（drawable/ic_launcher_foreground），不需要外部图片文件。
 */
@Composable
fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E8E3E)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(132.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "手机智能语音终端",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "AI Agent · 语音 / 文字 / 文件",
                color = Color(0xFF9AA0A6),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "接听即说，说完即达",
                color = Color(0xFF5F6368),
                fontSize = 12.sp,
            )
        }
    }
}

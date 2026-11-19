package tv.xbox.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Tabs = listOf("首页", "搜索", "歌单", "榜单", "更多")

/**
 * 顶栏外壳 —— 照 MuYin 的 MainShell：左边头像 + 应用名，中间五个胶囊页签，
 * 右边时钟。页签在顶栏内左右切换，向下进内容区。
 */
@Composable
fun ShellTopBar(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧头像：点一下回首页（页签之外的第二条回首页路径）
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF3B4A7A), Color(0xFF6E8BFF))))
                .clickable { onSelect(0) }
        )
        Spacer(Modifier.width(10.dp))
        Text("xbox-tv", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(28.dp))
        Tabs.forEachIndexed { i, label ->
            val active = i == selected
            TvSurface(
                // 必须给死宽度：Row 里不写宽度的话，第一个胶囊会把剩余空间全吃掉
                Modifier.height(36.dp).width(104.dp).padding(end = 4.dp),
                RoundedCornerShape(18.dp),
                if (active) Color.White else Color.Transparent,
                1.06f,
                onClick = { onSelect(i) },
            ) {
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (active) Color(0xFF0A0C12) else Color.White.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        var clock by remember { mutableStateOf(nowText()) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                clock = nowText()
            }
        }
        Text(clock, color = Color.White.copy(alpha = 0.86f), fontSize = 17.sp,
            fontWeight = FontWeight.Medium)
    }
}

private fun nowText(): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())

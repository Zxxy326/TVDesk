package tv.xbox.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.catvod.music.LxSource
import com.github.catvod.music.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.xbox.music.LxBridge
import tv.xbox.music.MusicPrefs
import tv.xbox.music.Player

private data class MoreEntry(val title: String, val sub: String)
private val MoreItems = listOf(
    MoreEntry("设置", "播放风格、音质、播放模式、代理"),
    MoreEntry("音源", "LX 脚本的安装、启用与探测"),
    MoreEntry("账号", "网易云 Cookie 导入与登录状态"),
    MoreEntry("下载", "离线缓存与 SMB（开发中）"),
    MoreEntry("关于", "版本与开源许可"),
)

/** 更多页：五个入口，点进去是二级页面；有内部返回栈。 */
@Composable
fun MorePage() {
    var sub by remember { mutableIntStateOf(-1) }
    if (sub < 0) {
        Column(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 100.dp, end = 48.dp)) {
            Text("更多", color = C.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            MoreItems.forEachIndexed { i, e ->
                TvSurface(Modifier.fillMaxWidth().height(72.dp).padding(bottom = 10.dp),
                    RoundedCornerShape(14.dp), C.Surface, 1.02f, onClick = { sub = i }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(e.sub, color = C.Text3, fontSize = 12.sp)
                        }
                        Text("›", color = C.Text3, fontSize = 26.sp)
                    }
                }
            }
        }
    } else {
        when (sub) {
            0 -> SettingsPage { sub = -1 }
            1 -> SourcesPage { sub = -1 }
            2 -> AccountPage { sub = -1 }
            3 -> DownloadPage { sub = -1 }
            4 -> AboutPage { sub = -1 }
            else -> sub = -1
        }
    }
}

/** 二级页通用外壳：返回胶囊 + 标题 */
@Composable
private fun SubShell(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 56.dp, end = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvSurface(Modifier.size(44.dp), CircleShape, C.Surface, 1.08f, onClick = onBack) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(title, color = C.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** 一行可点的选项（右侧显示当前值） */
@Composable
private fun OptionRow(label: String, sub: String = "", value: String, onClick: () -> Unit) {
    TvSurface(Modifier.fillMaxWidth().height(66.dp).padding(bottom = 8.dp),
        RoundedCornerShape(12.dp), C.Surface, 1.02f, onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (sub.isNotEmpty()) Text(sub, color = C.Text3, fontSize = 11.sp)
            }
            Text(value, color = C.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- 设置

@Composable
private fun SettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableIntStateOf(MusicPrefs.style(ctx)) }
    var mode by remember { mutableIntStateOf(MusicPrefs.playMode(ctx)) }
    var quality by remember { mutableStateOf(MusicPrefs.quality(ctx)) }
    val styleNames = listOf("动感涟漪", "简约", "精简时钟")
    val modeNames = listOf("列表循环", "单曲循环", "随机播放")
    val qualities = listOf("128k", "192k", "320k", "flac", "flac24bit")
    SubShell("设置", onBack) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                OptionRow("播放页风格", "切换全屏播放页的视觉样式", styleNames[style], onClick = {
                    style = (style + 1) % 3
                    MusicPrefs.setStyle(ctx, style)
                })
            }
            item {
                OptionRow("播放模式", "列表 / 单曲 / 随机", modeNames[mode], onClick = {
                    mode = (mode + 1) % 3
                    MusicPrefs.setPlayMode(ctx, mode)
                    // 立即生效：Player 从偏好里读不到了，直接喂一次
                    Player.setMode(mode)
                })
            }
            item {
                OptionRow("默认音质", "多源解析期望音质，源不支持会自动降级", quality, onClick = {
                    val i = qualities.indexOf(quality)
                    val next = qualities[(i + 1) % qualities.size]
                    quality = next
                    MusicPrefs.setQuality(ctx, next)
                })
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { Text("说明", color = C.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            item {
                Text("风格切换会立即保存到本机；下次进入播放页保持上次选择。音质受音源与账号权限限制，实际以解析结果为准。",
                    color = C.Text3, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 音源

@Composable
private fun SourcesPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<LxSource>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) { LxBridge.refresh() }
        sources = list
        scanning = false
        message = if (list.isEmpty()) "未发现 LX 音源脚本。用 adb 把 .js 放进音源目录，或到音源市场下载。" else ""
    }
    SubShell("音源", onBack) {
        Text(if (scanning) "正在扫描…" else "已安装 ${sources.size} 个脚本",
            color = C.Text2, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(sources) { s ->
                TvSurface(Modifier.fillMaxWidth().height(86.dp).padding(bottom = 10.dp),
                    RoundedCornerShape(12.dp), C.Surface, 1.02f, onClick = {
                        // 点一下测一下：跑脚本拿平台列表（重活，放后台）
                        scope.launch {
                            message = "正在测试 ${s.displayName()}…"
                            val ok = withContext(Dispatchers.IO) {
                                LxBridge.readyRuntime(s.fileName) != null
                            }
                            message = if (ok) "${s.displayName()}：可用 · ${s.platformSummary()}"
                            else "${s.displayName()}：初始化失败"
                            sources = LxBridge.installed().toList()
                        }
                    }) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.displayName(), color = C.Text, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            StatusChip(s.status)
                        }
                        val meta = s.displayMeta()
                        Text(
                            if (s.platformSummary().isNotEmpty()) s.platformSummary()
                            else if (meta.isNotEmpty()) meta
                            else s.fileName,
                            color = C.Text3, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                TvSurface(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(26.dp),
                    C.Elevated, 1.03f, onClick = {
                        scope.launch {
                            scanning = true
                            val list = withContext(Dispatchers.IO) { LxBridge.refresh() }
                            sources = list
                            scanning = false
                            message = "已重新扫描，发现 ${list.size} 个脚本"
                        }
                    }) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("重新扫描音源目录", color = C.Text, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                if (message.isNotEmpty()) {
                    Text(message, color = C.Text2, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        LxSource.STATUS_OK -> "正常" to Color(0xFF5FD08A)
        LxSource.STATUS_FAIL -> "失败" to Color(0xFFE06C6C)
        else -> "未测试" to C.Text3
    }
    Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

// ---------------------------------------------------------------- 账号

@Composable
private fun AccountPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var logged by remember { mutableStateOf(NeteaseClient.get(ctx).loggedIn()) }
    var accountName by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val name = withContext(Dispatchers.IO) {
            try { NeteaseClient.get(ctx).account()?.nickname ?: "" } catch (t: Throwable) { "" }
        }
        accountName = name
    }
    SubShell("账号", onBack) {
        Column(Modifier.fillMaxWidth()) {
            TvSurface(Modifier.fillMaxWidth().height(78.dp), RoundedCornerShape(14.dp),
                C.Surface, 1.02f, onClick = {}) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF3B4A7A), C.Accent))))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(if (logged) (accountName.ifEmpty { "已登录" }) else "未登录",
                            color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (logged) "网易云 · 每日推荐 / 红心可用" else "导入 Cookie 后可看每日推荐、同步红心",
                            color = C.Text3, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OptionRow("从剪贴板导入 Cookie", "复制网易云网页版 Cookie 后点这里",
                if (logged) "已登录" else "导入", onClick = {
                    val text = clipboard.getText()?.text?.toString().orEmpty()
                    if (text.isBlank()) {
                        toast = "剪贴板是空的，先复制一段 Cookie"
                    } else {
                        val n = NeteaseClient.get(ctx).setCookie(text)
                        logged = NeteaseClient.get(ctx).loggedIn()
                        toast = if (n > 0) "已导入 $n 项 Cookie" else "这段文本里没认出 Cookie"
                    }
                })
            if (logged) {
                OptionRow("退出登录", "清除本机 Cookie 与红心缓存", "退出", onClick = {
                    NeteaseClient.get(ctx).logout()
                    logged = false
                    accountName = ""
                    toast = "已退出登录"
                })
            }
            Spacer(Modifier.height(16.dp))
            Text("每日推荐需要登录；搜索、歌单、榜单免登录可用。扫码登录接口已被官方下架，只能导入 cookie。",
                color = C.Text3, fontSize = 12.sp, lineHeight = 18.sp)
            if (toast.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(toast, color = C.Accent, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 下载 / 关于

@Composable
private fun DownloadPage(onBack: () -> Unit) {
    SubShell("下载", onBack) {
        InfoLines(
            "离线缓存与 SMB 局域网播放正在适配中。",
            "当前版本已经支持：多源在线解析（官方接口 + LX 音源脚本）、音质自动降级、链接缓存。",
            "在线听歌不受影响，联网即可播放。",
        )
    }
}

@Composable
private fun AboutPage(onBack: () -> Unit) {
    SubShell("关于", onBack) {
        InfoLines(
            "xbox-tv 音乐模块 · Compose 版",
            "UI 与交互对照 MuYin（https://github.com/Zxxy326/MuYin）适配。",
            "音源内核 LX / 解析核心沿用开源实现，遵循 GPL 许可。",
            "播放页三种风格：动感涟漪、简约、精简时钟。",
        )
    }
}

@Composable
private fun InfoLines(vararg lines: String) {
    Column(Modifier.fillMaxWidth()) {
        lines.forEach {
            Text(it, color = C.Text2, fontSize = 14.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(10.dp))
        }
    }
}

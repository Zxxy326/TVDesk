package tv.xbox.music.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.catvod.music.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.xbox.music.MusicPrefs
import tv.xbox.music.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 歌词行：时间(ms) + 文本 */
private data class LyricLine(val timeMs: Long, val text: String)

/** 解析 LRC。MuYin 是行级时间轴（没有逐字），这里也只解析整行。 */
private fun parseLrc(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    val out = mutableListOf<LyricLine>()
    for (line in raw.lines()) {
        var text = line.trim()
        val matches = tag.findAll(line).toList()
        if (matches.isEmpty()) continue
        // 一行可能带多个时间戳（如 [00:12.00][00:24.00]歌词），都算同一句
        for (m in matches) {
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> frac.toLong() * 100L
                frac.length == 2 -> frac.toLong() * 10L
                else -> frac.take(3).toLong()
            }
            val start = m.range.last + 1
            text = if (start <= line.length) line.substring(start).trim() else ""
            if (text.isNotEmpty()) out.add(LyricLine(min * 60_000L + sec * 1000L + ms, text))
        }
    }
    return out.sortedBy { it.timeMs }
}

private fun fmtTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${String.format(Locale.CHINA, "%02d", total % 60)}"
}

/**
 * 全屏播放页 —— 对齐 MuYin 的三种 LandscapeLyricEffects：
 * 0 动感涟漪（环 + 中心字 + 当前歌词）、1 简约（旋转封面 + 歌词列表）、
 * 2 精简时钟（模糊封面 + 屏高 26% 大字时间）。
 * 底部控制区三种风格共用：歌名/进度/播放·上一首·下一首·收藏·模式/风格切换/返回。
 */
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by Player.state.collectAsState()
    val song = st.queue.getOrNull(st.index)

    var style by remember { mutableIntStateOf(MusicPrefs.style(ctx)) }
    var lrcRaw by remember { mutableStateOf("") }
    val lines = remember(lrcRaw) { parseLrc(lrcRaw) }
    var clock by remember { mutableStateOf(fmtClock()) }
    val scope = rememberCoroutineScope()

    val cover = rememberBitmap(song?.picUrl)

    // 换歌取歌词：先问 LX 源，取不到走 NeteaseClient（这里用现成的官方接口）
    LaunchedEffect(song?.id) {
        lrcRaw = ""
        val id = song?.id ?: return@LaunchedEffect
        if (id.isEmpty()) return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            try { NeteaseClient.get(ctx).lyric(id) ?: "" } catch (t: Throwable) { "" }
        }
        lrcRaw = text
    }
    // 时钟风格每秒刷新
    LaunchedEffect(style) {
        while (style == 2) {
            clock = fmtClock()
            kotlinx.coroutines.delay(1000L)
        }
    }

    val currentLine = remember(lines, st.positionMs) {
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs <= st.positionMs) idx = i else break
        idx
    }

    Box(
        Modifier.fillMaxSize().background(C.Bg)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Back || e.key == Key.Escape)) {
                    onBack()
                    true
                } else false
            }
    ) {
        // 模糊封面底（三种风格共用）
        cover?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(36.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.55f,
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0x9905060A)))

        when (style) {
            0 -> RippleStyle(lines, currentLine, st.positionMs, lines.getOrNull(currentLine)?.timeMs ?: 0L)
            1 -> SimpleStyle(song, cover, lines, currentLine)
            2 -> ClockStyle(clock, song)
        }

        // 顶部：返回 + 标题 + 风格切换
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSurface(Modifier.size(46.dp), CircleShape, Color.Black.copy(0.35f), 1.08f,
                onClick = onBack) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Text("正在播放", color = Color.White.copy(0.86f), fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val styles = listOf("动感涟漪", "简约", "精简时钟")
            TvSurface(Modifier.width(150.dp).height(44.dp), RoundedCornerShape(22.dp),
                Color.Black.copy(0.4f), 1.05f, onClick = {
                    val next = (style + 1) % 3
                    style = next
                    MusicPrefs.setStyle(ctx, next)
                }) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(styles[style], color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }

        // 底部控制区
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xE605060A)))
                )
                .padding(horizontal = 40.dp, vertical = 22.dp),
        ) {
            Text(song?.name ?: "未在播放", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(
                song?.let { "${it.artist} · ${it.album}" } ?: "",
                color = Color.White.copy(0.6f), fontSize = 12.sp, maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            // 进度条：左右键 ±5s，确定键跳到中点
            ProgressBar(
                position = st.positionMs, duration = st.durationMs,
                onSeek = { Player.seekTo(it) },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val modeLabel = when (st.mode) {
                    Player.MODE_ONE -> "单曲"
                    Player.MODE_SHUFFLE -> "随机"
                    else -> "列表"
                }
                CtrlCircle(modeLabel, small = true) { Player.cycleMode() }
                Spacer(Modifier.width(18.dp))
                CtrlCircle("|<") { Player.previous() }
                Spacer(Modifier.width(14.dp))
                TvSurface(Modifier.size(58.dp), CircleShape, Color.White, 1.08f,
                    onClick = { Player.toggle() }) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            if (st.playing) "❚❚" else "▶",
                            color = Color(0xFF0A0C12), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                CtrlCircle(">|") { Player.next() }
                Spacer(Modifier.width(18.dp))
                CtrlCircle(if (st.favorite) "♥" else "♡") { Player.toggleFavorite() }
                Spacer(Modifier.width(24.dp))
                Text(
                    if (st.resolving) "解析音源中…" else st.sourceName,
                    color = Color.White.copy(0.5f), fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CtrlCircle(label: String, small: Boolean = false, onClick: () -> Unit) {
    val size = if (small) 42.dp else 48.dp
    TvSurface(Modifier.size(size), CircleShape, Color.Black.copy(0.34f), 1.1f,
        onClick = onClick) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(label, color = Color.White,
                fontSize = if (small) 12.sp else 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProgressBar(position: Int, duration: Int, onSeek: (Int) -> Unit) {
    val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(0.1f))
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                    val delta = if (e.key == Key.DirectionRight) 5000 else -5000
                    onSeek((position + delta).coerceIn(0, duration))
                    true
                } else if (e.type == KeyEventType.KeyUp && e.key == Key.DirectionCenter) {
                    onSeek(duration / 2)
                    true
                } else false
            }
            .clickable { if (duration > 0) onSeek((duration * ratio).toInt()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 用 fraction 画已播放段
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(C.Accent, Color.White)),
                size = androidx.compose.ui.geometry.Size(size.width * ratio, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmtTime(position), color = Color.White.copy(0.6f), fontSize = 11.sp)
        Text(fmtTime(duration), color = Color.White.copy(0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun RippleStyle(lines: List<LyricLine>, currentLine: Int, positionMs: Int, lineStartMs: Long) {
    val infinite = rememberInfiniteTransition(label = "ripple")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val lyric = lines.getOrNull(currentLine)?.text.orEmpty()
    // 行内进度：LRC 只有行级时间，用「距下一行」估算，没下一行就按 4 秒匀速
    val nextMs = lines.getOrNull(currentLine + 1)?.timeMs
    val span = (nextMs ?: (lineStartMs + 4000L)) - lineStartMs
    val lineProgress = if (span > 0) ((positionMs - lineStartMs).toFloat() / span).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val band = w * 0.075f
            val maxR = w * 0.40f
            for (cx in listOf(-0.02f * w, 1.02f * w)) {
                for (i in 0 until 5) {
                    val p = (phase + i / 5f) % 1f
                    val radius = maxR * p
                    val fadeIn = (p / 0.22f).coerceAtMost(1f)
                    val fadeOut = (1f - ((p - 0.62f) / 0.38f).coerceIn(0f, 1f))
                    val alpha = 0.55f * fadeIn * fadeIn * fadeOut * fadeOut
                    if (alpha <= 0.01f || radius <= 1f) continue
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                C.Accent.copy(alpha * 0.6f),
                                Color.White.copy(alpha * 0.85f),
                                C.Accent.copy(alpha * 0.5f),
                                Color.Transparent,
                            ),
                            center = Offset(cx, cy), radius = radius + band,
                        ),
                        center = Offset(cx, cy), radius = radius + band / 2f,
                        style = Stroke(width = band, cap = StrokeCap.Round),
                    )
                }
            }
        }
        // 中心：当前歌词首字水印 + 整行歌词（已唱部分渐亮）
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            val mark = lyric.take(1)
            if (mark.isNotEmpty()) {
                Text(mark, color = C.Accent.copy(0.16f), fontSize = 300.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
        if (lyric.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                Text(lyric, color = Color.White.copy(0.35f), fontSize = 30.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                Box(Modifier.fillMaxWidth(lineProgress.coerceIn(0.001f, 1f)),
                    contentAlignment = Alignment.Center) {
                    Text(lyric, color = Color.White, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun SimpleStyle(
    song: NeteaseClient.Song?,
    cover: android.graphics.Bitmap?,
    lines: List<LyricLine>,
    currentLine: Int,
) {
    val infinite = rememberInfiniteTransition(label = "spin")
    val rotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Row(
        Modifier.fillMaxSize().padding(horizontal = 90.dp, vertical = 150.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 旋转封面盘
        Box(Modifier.size(280.dp), Alignment.Center) {
            androidx.compose.foundation.Image(
                bitmap = cover?.asImageBitmap() ?: return@Box,
                contentDescription = null,
                modifier = Modifier.size(280.dp).clip(CircleShape)
                    .rotate(rotation),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.size(70.dp).clip(CircleShape).background(Color(0xAA05060A)))
        }
        Spacer(Modifier.width(70.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(currentLine) {
            if (currentLine >= 0) listState.animateScrollToItem(currentLine, -2)
        }
        LazyColumn(Modifier.weight(1f).height(300.dp), state = listState) {
            itemsIndexed(lines) { i, line ->
                Text(
                    line.text,
                    color = if (i == currentLine) Color.White else Color.White.copy(0.38f),
                    fontSize = if (i == currentLine) 24.sp else 19.sp,
                    fontWeight = if (i == currentLine) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 7.dp), maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ClockStyle(clock: String, song: NeteaseClient.Song?) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clock, color = Color.White, fontSize = 150.sp, fontWeight = FontWeight.Light,
                letterSpacing = (-4).sp)
            Spacer(Modifier.height(12.dp))
            Text(song?.name ?: "", color = Color.White.copy(0.62f), fontSize = 22.sp, maxLines = 1)
        }
    }
}

private fun fmtClock(): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())

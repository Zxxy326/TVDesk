package tv.xbox.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import com.github.catvod.music.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CANVAS_W = 960f

/** 把 1080p 屏幕换成 960dp 基准：MuYin 的所有 dp 都按这个画布写的 */
@Composable
fun rememberCanvasScale(): Float {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return if (w <= 0) 1f else (w / CANVAS_W).coerceIn(0.6f, 1.3f)
}

@Composable
internal fun rememberBitmap(url: String?): Bitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) {
            bmp = null
            return@LaunchedEffect
        }
        bmp = withContext(Dispatchers.IO) {
            try {
                val t: FutureTarget<Bitmap> = Glide.with(ctx).asBitmap().load(url).submit(720, 720)
                t.get()
            } catch (th: Throwable) {
                null
            }
        }
    }
    return bmp
}

/** 首页：Hero + 海报行（照 MuYin HomeTab 的构图） */
@Composable
fun HomeScreen(
    songs: List<NeteaseClient.Song>,
    status: String,
    onOpenPlayer: () -> Unit = {},
) {
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
            Text(status, color = C.Text2, fontSize = 18.sp)
        }
        return
    }
    val hero = songs.first()
    val cover = rememberBitmap(hero.picUrl)
    // 焦点路由：Hero 的播放键 ↔ 海报行（文档 4.2 的 hero:play / rail:i）
    val heroRequester = remember { FocusRequester() }
    val railRequesters = remember(songs.size) { List(songs.size) { FocusRequester() } }
    val railState = rememberLazyListState()
    var zone by remember { mutableStateOf("hero") }
    var railIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { heroRequester.requestFocus() }
    Box(
        Modifier.fillMaxSize().background(C.Bg).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.DirectionDown && zone == "hero" -> {
                    zone = "rail"
                    scope.launch { railState.scrollToItem(railIndex) }
                    railRequesters[railIndex].requestFocus()
                    true
                }
                e.key == Key.DirectionUp && zone == "rail" -> {
                    zone = "hero"
                    heroRequester.requestFocus()
                    true
                }
                e.key == Key.DirectionLeft && zone == "rail" -> {
                    if (railIndex > 0) {
                        railIndex--
                        scope.launch { railState.animateScrollToItem(railIndex) }
                        railRequesters[railIndex].requestFocus()
                    }
                    true
                }
                e.key == Key.DirectionRight && zone == "rail" -> {
                    if (railIndex < songs.lastIndex) {
                        railIndex++
                        scope.launch { railState.animateScrollToItem(railIndex) }
                        railRequesters[railIndex].requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
    ) {
        cover?.let {
            Image(
                bitmap = it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(0.dp)
            )
        }
        Box(Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Color(0xE605060A), Color(0xB805060A), Color(0x2605060A)))
        ))
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0x8C05060A), Color(0x1405060A), Color(0x0D05060A),
                    Color(0x8C05060A), Color(0xF205060A))
            )
        ))
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(112.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(C.Accent))
                Spacer(Modifier.width(8.dp))
                Text("继续聆听", color = Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(hero.name, color = Color.White, maxLines = 1, fontSize = 46.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            Spacer(Modifier.height(4.dp))
            Text("${hero.artist} · ${hero.album}", color = Color.White.copy(alpha = 0.8f),
                maxLines = 1, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            // 五颗动作：播放/暂停 + 上一首/下一首/收藏/播放页（MuYin 的 hero:* 那一排）
            val st by tv.xbox.music.Player.state.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvSurface(Modifier.width(132.dp).height(44.dp).focusRequester(heroRequester)
                    .onFocusChanged { if (it.isFocused) zone = "hero" },
                    RoundedCornerShape(22.dp),
                    Color.Black.copy(alpha = 0.34f), 1.03f,
                    onClick = {
                        tv.xbox.music.Player.playOrToggle(songs)
                    }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (st.playing) "暂停" else if (st.index >= 0) "继续播放" else "播放全部",
                            color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.width(10.dp))
                HeroCircle("|<") { tv.xbox.music.Player.previous() }
                Spacer(Modifier.width(8.dp))
                HeroCircle(">|") { tv.xbox.music.Player.next() }
                Spacer(Modifier.width(8.dp))
                HeroCircle(if (st.favorite) "♥" else "♡") {
                    tv.xbox.music.Player.toggleFavorite()
                }
                Spacer(Modifier.width(18.dp))
                HeroCircle("⤢", onOpenPlayer)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("每日推荐", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("${songs.size} 首", color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(Modifier.fillMaxWidth().height(128.dp), state = railState,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(songs) { i, s ->
                    PosterCard(s, i, railRequesters[i]) {
                        railIndex = i
                        zone = "rail"
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun HeroCircle(label: String, onClick: () -> Unit) {
    TvSurface(Modifier.size(44.dp), RoundedCornerShape(22.dp),
        Color.Black.copy(alpha = 0.32f), 1.10f, onClick = onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PosterCard(
    song: NeteaseClient.Song,
    index: Int,
    requester: FocusRequester,
    onFocused: () -> Unit,
) {
    val bmp = rememberBitmap(song.picUrl)
    TvSurface(Modifier.width(150.dp).height(128.dp).focusRequester(requester)
        .onFocusChanged { if (it.isFocused) onFocused() },
        RoundedCornerShape(12.dp), Color.Transparent, 1.035f) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(alpha = 0.05f))) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(song.name, color = Color.White, maxLines = 1, fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold)
            Text(song.artist, color = Color.White.copy(alpha = 0.62f), maxLines = 1, fontSize = 10.5.sp)
        }
    }
}

package tv.xbox.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import com.github.catvod.music.NeteaseClient

// ---------------------------------------------------------------- 搜索

private val T9 = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "0" to "", "*" to "", "#" to "",
)

/** 搜索页：左查询面板（热搜/历史），右 T9 九宫格（照 MuYin SearchScreen 的两栏） */
@Composable
fun SearchPage(
    hot: List<String>,
    history: List<String>,
    results: List<NeteaseClient.Song>,
    searching: Boolean,
    onSearch: (String) -> Unit,
    onPlay: (List<NeteaseClient.Song>, Int) -> Unit,
    onClearHistory: () -> Unit = {},
) {
    Row(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 96.dp, end = 48.dp)) {
        Column(Modifier.width(460.dp)) {
            Text("搜索", color = C.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(C.Surface).padding(horizontal = 20.dp), Alignment.CenterStart) {
                Text("输入歌名 / 歌手 / 专辑", color = C.Text3, fontSize = 15.sp)
            }
            Spacer(Modifier.height(22.dp))
            SectionTitle("热搜")
            hot.take(6).forEachIndexed { i, word ->
                RowItem(word, "$i") { onSearch(word) }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("历史", color = C.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    TvSurface(Modifier.width(72.dp).height(30.dp), RoundedCornerShape(15.dp),
                        Color.Transparent, 1.02f, onClick = onClearHistory) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("清空", color = C.Text3, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (history.isEmpty()) {
                Text("还没有搜索记录", color = C.Text3, fontSize = 13.sp)
            } else {
                history.take(8).forEachIndexed { i, word ->
                    RowItem(word, "h$i") { onSearch(word) }
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            if (searching || results.isNotEmpty()) {
                Text(if (searching) "搜索中…" else "结果 · ${results.size} 首",
                    color = C.Text2, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                LazyColumn {
                    itemsIndexed(results) { i, s ->
                        TvSurface(Modifier.fillMaxWidth().height(56.dp).padding(bottom = 4.dp),
                            RoundedCornerShape(10.dp), Color.Transparent, 1.02f,
                            onClick = { onPlay(results, i) }) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = C.Text3, fontSize = 14.sp,
                                    modifier = Modifier.width(32.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, color = C.Text, fontSize = 16.sp, maxLines = 1)
                                    Text("${s.artist} · ${s.album}", color = C.Text3,
                                        fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                return@Row
            }
            Text("九宫格输入", color = C.Text2, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            for (row in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0 until 3) {
                        val key = T9[row * 3 + col]
                        Box(Modifier.weight(1f)) {
                            TvSurface(Modifier.fillMaxWidth().height(72.dp), RoundedCornerShape(14.dp),
                                C.Surface, 1.04f, onClick = { }) {
                                Column(Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    Text(key.first, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    if (key.second.isNotEmpty()) {
                                        Text(key.second, color = C.Text3, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.weight(1f))
            TvSurface(Modifier.fillMaxWidth().height(52.dp), RoundedCornerShape(26.dp),
                Color.White, 1.02f, onClick = { onSearch(hot.firstOrNull() ?: "") }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("搜索", color = Color(0xFF0A0C12), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = C.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun RowItem(text: String, key: String, onClick: () -> Unit) {
    TvSurface(Modifier.fillMaxWidth().height(44.dp).padding(bottom = 6.dp),
        RoundedCornerShape(10.dp), Color.Transparent, 1.02f, onClick = onClick) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), Alignment.CenterStart) {
            Text(text, color = C.Text, fontSize = 16.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- 歌单

/** 歌单页：推荐 / 我的 两段横栏（照 PlaylistsScreen 的 LazyColumn + TvRail） */
@Composable
fun PlaylistsPage(
    mine: List<NeteaseClient.Playlist>,
    rec: List<NeteaseClient.Playlist>,
    onOpen: (NeteaseClient.Playlist) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 100.dp, end = 48.dp)) {
        item {
            Text("歌单", color = C.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
        }
        item { PlaylistRail("推荐歌单", rec, onOpen) }
        item { Spacer(Modifier.height(24.dp)) }
        item { PlaylistRail("我的歌单", mine, onOpen) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun PlaylistRail(
    title: String,
    list: List<NeteaseClient.Playlist>,
    onOpen: (NeteaseClient.Playlist) -> Unit,
) {
    SectionTitle("$title · ${list.size}")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        items(list) { p ->
            TvSurface(Modifier.width(160.dp).height(196.dp), RoundedCornerShape(12.dp),
                C.Surface, 1.04f, onClick = { onOpen(p) }) {
                Column(Modifier.fillMaxSize().padding(8.dp)) {
                    val bmp = rememberBitmap(p.coverUrl)
                    Box(Modifier.fillMaxWidth().height(144.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))) {
                        bmp?.let {
                            androidx.compose.foundation.Image(
                                it.asImageBitmap(), null, Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(p.name, color = C.Text, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text("${p.trackCount} 首", color = C.Text3, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** 歌单详情：标题 + 曲目列表（点行即播） */
@Composable
fun PlaylistDetailPage(
    title: String,
    songs: List<NeteaseClient.Song>,
    loading: Boolean,
    onPlay: (List<NeteaseClient.Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 100.dp, end = 48.dp)) {
        Text(title, color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(if (loading) "加载中…" else "${songs.size} 首 · 按 OK 播放",
            color = C.Text3, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        LazyColumn {
            itemsIndexed(songs) { i, s ->
                TvSurface(Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp),
                    RoundedCornerShape(10.dp), Color.Transparent, 1.02f,
                    onClick = { onPlay(songs, i) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.Text3, fontSize = 14.sp, modifier = Modifier.width(34.dp))
                        Text(s.name, color = C.Text, fontSize = 16.sp, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        Text(s.artist, color = C.Text3, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 榜单

/** 网易榜单都是歌单，用歌单 id 取曲目（MuYin 也是这么做的） */
val Charts = listOf(
    "网易热歌榜" to "3778678",
    "网易新歌榜" to "3779629",
    "网易飙升榜" to "19723756",
    "网易原创榜" to "2884035",
    "网易云音乐人" to "3136952023",
)

@Composable
fun ChartsPage(
    songs: List<NeteaseClient.Song>,
    current: Int,
    loading: Boolean = false,
    onSelect: (Int) -> Unit,
    onPlay: (List<NeteaseClient.Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(C.Bg).padding(start = 48.dp, top = 100.dp, end = 48.dp)) {
        Text("榜单", color = C.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Charts.forEachIndexed { i, item ->
                TvSurface(Modifier.weight(1f).height(84.dp), RoundedCornerShape(14.dp),
                    if (i == current) C.Elevated else C.Surface, 1.04f, onClick = { onSelect(i) }) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), Alignment.CenterStart) {
                        Text(item.first, color = C.Text, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (loading) "正在加载 ${Charts[current].first}…" else "${Charts[current].first} · ${songs.size} 首",
            color = C.Text2, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            itemsIndexed(songs.take(30)) { i, s ->
                TvSurface(Modifier.fillMaxWidth().height(52.dp).padding(bottom = 4.dp),
                    RoundedCornerShape(10.dp), Color.Transparent, 1.02f,
                    onClick = { onPlay(songs, i) }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = if (i < 3) C.Accent else C.Text3,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                        Text(s.name, color = C.Text, fontSize = 16.sp, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        Text(s.artist, color = C.Text3, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 更多（实现在 MorePages.kt）

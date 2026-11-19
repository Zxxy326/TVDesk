package tv.xbox.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.github.catvod.music.NeteaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import tv.xbox.music.ui.HomeScreen
import tv.xbox.music.ui.ShellTopBar
import tv.xbox.music.ui.SearchPage
import tv.xbox.music.ui.PlaylistsPage
import tv.xbox.music.ui.PlaylistDetailPage
import tv.xbox.music.ui.ChartsPage
import tv.xbox.music.ui.MorePage
import tv.xbox.music.ui.PlayerScreen
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * 音乐模块外壳（路线 A：MuYin 的 Compose 栈）。
 * 数据走 core 的 NeteaseClient + LX 音源；五个页签 + 全屏播放页 + 更多页二级页面。
 */
class MusicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.attach(this)
        setContent {
            var songs by remember { mutableStateOf<List<NeteaseClient.Song>>(emptyList()) }
            var status by remember { mutableStateOf("正在取每日推荐…") }
            var playlists by remember { mutableStateOf<List<NeteaseClient.Playlist>>(emptyList()) }
            var results by remember { mutableStateOf<List<NeteaseClient.Song>>(emptyList()) }
            var searching by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf(MusicPrefs.history(this@MusicActivity)) }
            var detail by remember { mutableStateOf<NeteaseClient.Playlist?>(null) }
            var detailSongs by remember { mutableStateOf<List<NeteaseClient.Song>>(emptyList()) }
            var detailLoading by remember { mutableStateOf(false) }
            var chartIndex by remember { mutableStateOf(0) }
            var chartSongs by remember { mutableStateOf<List<NeteaseClient.Song>>(emptyList()) }
            var chartLoading by remember { mutableStateOf(false) }
            var tab by remember { mutableStateOf(0) }
            var showPlayer by remember { mutableStateOf(false) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()

            // 榜单加载：按 chart id 取真实曲目。进入榜单页若还是空的，主动加载第 0 个榜
            fun loadChart(i: Int) {
                chartIndex = i
                chartLoading = true
                scope.launch {
                    val list = withContext(Dispatchers.IO) {
                        try {
                            NeteaseClient.get(this@MusicActivity)
                                .playlistSongs(tv.xbox.music.ui.Charts[i].second, 100)
                        } catch (t: Throwable) {
                            emptyList<NeteaseClient.Song>()
                        }
                    }
                    chartSongs = list ?: emptyList()
                    chartLoading = false
                }
            }
            LaunchedEffect(tab) {
                if (tab == 3 && chartSongs.isEmpty() && !chartLoading) loadChart(0)
            }

            LaunchedEffect(Unit) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        NeteaseClient.get(this@MusicActivity).dailySongs() ?: emptyList()
                    } catch (t: Throwable) {
                        emptyList()
                    }
                }
                songs = result
                status = if (result.isEmpty()) "每日推荐取不到（要登录网易云才行）" else ""
                playlists = withContext(Dispatchers.IO) {
                    try {
                        NeteaseClient.get(this@MusicActivity).recommendedPlaylists(12) ?: emptyList()
                    } catch (t: Throwable) {
                        emptyList()
                    }
                }
            }

            // MuYin 的 TvScaleProvider：把「屏幕宽 960dp」当成画布。
            val d = LocalDensity.current
            val screenPx = resources.displayMetrics.widthPixels.toFloat()
            val canvasDensity = (screenPx / 960f).coerceIn(0.6f, 3.2f)
            CompositionLocalProvider(
                LocalDensity provides Density(canvasDensity, d.fontScale)
            ) {
                val origin = androidx.compose.ui.platform.LocalView.current
                fun send(code: Int) {
                    origin.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    origin.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        0 -> HomeScreen(songs, status, onOpenPlayer = { showPlayer = true })
                        1 -> SearchPage(
                            hot = songs.take(6).map { it.name },
                            history = history,
                            results = results,
                            searching = searching,
                            onSearch = { q ->
                                if (q.isNotBlank()) {
                                    history = MusicPrefs.history(this@MusicActivity).let {
                                        MusicPrefs.addHistory(this@MusicActivity, q)
                                        MusicPrefs.history(this@MusicActivity)
                                    }
                                    searching = true
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            try {
                                                NeteaseClient.get(this@MusicActivity).search(q, 30)
                                            } catch (t: Throwable) {
                                                emptyList<NeteaseClient.Song>()
                                            }
                                        }
                                        results = r ?: emptyList()
                                        searching = false
                                    }
                                }
                            },
                            onPlay = { list, i -> Player.playAll(list, i); showPlayer = true },
                            onClearHistory = {
                                MusicPrefs.clearHistory(this@MusicActivity)
                                history = emptyList()
                            },
                        )
                        2 -> {
                            val chosen = detail
                            if (chosen == null) {
                                PlaylistsPage(mine = emptyList(), rec = playlists, onOpen = { p ->
                                    detail = p
                                    detailLoading = true
                                    detailSongs = emptyList()
                                    scope.launch {
                                        val list = withContext(Dispatchers.IO) {
                                            try {
                                                NeteaseClient.get(this@MusicActivity).playlistSongs(p.id, 200)
                                            } catch (t: Throwable) {
                                                emptyList<NeteaseClient.Song>()
                                            }
                                        }
                                        detailSongs = list ?: emptyList()
                                        detailLoading = false
                                    }
                                })
                            } else {
                                PlaylistDetailPage(chosen.name, detailSongs, detailLoading) { list, i ->
                                    Player.playAll(list, i)
                                    showPlayer = true
                                }
                            }
                        }
                        3 -> ChartsPage(
                            songs = chartSongs,
                            current = chartIndex,
                            loading = chartLoading,
                            onSelect = { i -> loadChart(i) },
                            onPlay = { list, i -> Player.playAll(list, i); showPlayer = true },
                        )
                        else -> MorePage()
                    }
                    ShellTopBar(tab) { tab = it }

                    // 全屏播放页盖在最上层；返回回到底下那一页
                    if (showPlayer) {
                        PlayerScreen(onBack = { showPlayer = false })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Player.release()
        super.onDestroy()
    }
}

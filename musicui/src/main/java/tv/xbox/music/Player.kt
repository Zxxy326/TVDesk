package tv.xbox.music

import android.content.Context
import android.media.MediaPlayer
import com.github.catvod.music.NeteaseClient
import com.github.catvod.music.OnlineSourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐播放器：队列 + 播放控制 + 多源取流 + 进度 + 收藏 + 状态持久化。
 *
 * <p>取流走 [OnlineSourceResolver]：官方直链（快）→ 已安装的 LX 音源脚本（几十秒）
 * → 跨源搜索 → 代理兜底。任一步拿到的地址先过 [OnlineSourceResolver.isUsableUrl]
 * 过滤加密/占位流。全部失败才退回网易外链做最后兜底。
 *
 * <p>队列、当前索引、进度、播放模式都存 [MusicPrefs]；启动时恢复但不出声。
 */
object Player {

    /** 播放模式：0 列表循环 / 1 单曲循环 / 2 随机 */
    const val MODE_LOOP = 0
    const val MODE_ONE = 1
    const val MODE_SHUFFLE = 2

    data class State(
        val queue: List<NeteaseClient.Song> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val favorite: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        /** 正在解析播放链接（LX 脚本可能要十几秒），UI 显示转圈 */
        val resolving: Boolean = false,
        /** 本首是谁给的链接，播放页底部小字 */
        val sourceName: String = "",
        val mode: Int = MODE_LOOP,
    )

    private var mp: MediaPlayer? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var appContext: Context? = null
    private var favSet: MutableSet<String> = mutableSetOf()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 用户连点/换歌时，让旧的解析结果作废（token 对不上就不起播） */
    private var resolveToken = 0
    private var progressJob: Job? = null
    private var restorePositionMs = 0
    private var persistedAt = 0L

    val current: NeteaseClient.Song?
        get() = _state.value.queue.getOrNull(_state.value.index)

    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val sp = context.getSharedPreferences("xbox_music_fav", Context.MODE_PRIVATE)
        favSet = sp.getStringSet("ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        LxBridge.attach(context)

        // 恢复上次队列，但不自动出声：电视开机就放歌太吵，用户按播放再续上
        MusicPrefs.playback(context)?.let { pb ->
            val queue = pb.queue.map { snap ->
                NeteaseClient.Song().apply {
                    id = snap.id; name = snap.name; artist = snap.artist
                    album = snap.album; picUrl = snap.picUrl; durationMs = snap.durationMs
                }
            }
            val idx = pb.index.coerceIn(0, queue.lastIndex)
            _state.value = _state.value.copy(
                queue = queue, index = idx, favorite = isFav(queue[idx].id),
                mode = MusicPrefs.playMode(context),
            )
            restorePositionMs = pb.positionMs
        } ?: run {
            _state.value = _state.value.copy(mode = MusicPrefs.playMode(context))
        }
    }

    // ---------------------------------------------------------------- 控制

    fun playAll(songs: List<NeteaseClient.Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        restorePositionMs = 0
        _state.value = _state.value.copy(
            queue = songs, index = startIndex, playing = false,
            favorite = isFav(songs[startIndex].id), positionMs = 0, durationMs = 0,
        )
        start(startIndex)
    }

    /** 首页播放键：没队列就从传入的列表起播，有队列就切换播放/暂停 */
    fun playOrToggle(fallback: List<NeteaseClient.Song>) {
        if (_state.value.queue.isEmpty()) playAll(fallback, 0) else toggle()
    }

    fun toggle() {
        val s = _state.value
        if (s.index < 0) return
        val p = mp
        if (p != null && p.isPlaying) {
            p.pause()
            _state.value = s.copy(playing = false)
            startProgressTicker(false)
            persist()
        } else if (p != null) {
            p.start()
            _state.value = s.copy(playing = true)
            startProgressTicker(true)
        } else {
            start(s.index)
        }
    }

    fun next() = step(1)
    fun previous() = step(-1)

    private fun step(delta: Int) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val i = when (s.mode) {
            MODE_SHUFFLE -> if (s.queue.size <= 1) 0 else
                (s.index + 1 + (0 until s.queue.size - 1).random()) % s.queue.size
            else -> (s.index + delta + s.queue.size) % s.queue.size
        }
        restorePositionMs = 0
        _state.value = s.copy(index = i, favorite = isFav(s.queue[i].id),
            positionMs = 0, durationMs = 0)
        start(i)
    }

    /** 一首播完：单曲循环就从头来，否则下一首（随机/列表都走 step） */
    private fun onCompleted() {
        val s = _state.value
        if (s.mode == MODE_ONE && s.index >= 0) {
            restorePositionMs = 0
            start(s.index)
        } else {
            step(1)
        }
    }

    /** 直接设定模式（设置页用），cycleMode 是播放页按钮循环用 */
    fun setMode(mode: Int) {
        val next = mode.coerceIn(0, 2)
        appContext?.let { MusicPrefs.setPlayMode(it, next) }
        _state.value = _state.value.copy(mode = next)
    }

    fun cycleMode() = setMode((_state.value.mode + 1) % 3)

    fun toggleFavorite() {
        val song = current ?: return
        val turningOn = !favSet.contains(song.id)
        if (turningOn) favSet.add(song.id) else favSet.remove(song.id)
        appContext?.getSharedPreferences("xbox_music_fav", Context.MODE_PRIVATE)
            ?.edit()?.putStringSet("ids", favSet)?.apply()
        _state.value = _state.value.copy(favorite = turningOn)
        // 同步网易红心（登录态下才有意义，失败不影响本地收藏）
        appContext?.let { ctx ->
            scope.launch(Dispatchers.IO) {
                try {
                    NeteaseClient.get(ctx).like(song.id, turningOn)
                    NeteaseClient.get(ctx).markLiked(song.id, turningOn)
                } catch (t: Throwable) {
                    android.util.Log.i("Player", "红心同步跳过：${t.message}")
                }
            }
        }
    }

    fun seekTo(ms: Int) {
        val p = mp ?: return
        try {
            p.seekTo(ms.coerceAtLeast(0))
            _state.value = _state.value.copy(positionMs = ms)
        } catch (t: Throwable) {
        }
    }

    fun isFavorite(id: String) = favSet.contains(id)

    private fun isFav(id: String) = favSet.contains(id)

    // ---------------------------------------------------------------- 取流 + 起播

    private fun start(index: Int) {
        val song = _state.value.queue.getOrNull(index) ?: return
        val ctx = appContext ?: return
        val token = ++resolveToken
        releasePlayer()
        _state.value = _state.value.copy(playing = false, resolving = true, sourceName = "正在取播放链接…")

        scope.launch(Dispatchers.IO) {
            val resolved = resolve(ctx, song)
            val sourceLabel = resolved?.sourceName ?: "网易外链"
            val url = resolved?.url
                ?: "https://music.163.com/song/media/outer/url?id=${song.id}.mp3"
            withContext(Dispatchers.Main) {
                // 期间用户又切了歌/按了别的：旧结果直接丢弃
                if (token != resolveToken || index != _state.value.index) return@withContext
                _state.value = _state.value.copy(sourceName = sourceLabel, resolving = false)
                doPlay(ctx, url)
            }
        }
    }

    /** 调统一解析核心；返回 null 表示没有可用的脚本/官方地址，交给外链兜底 */
    private fun resolve(ctx: Context, song: NeteaseClient.Song): OnlineSourceResolver.Result? = try {
        val req = OnlineSourceResolver.Request().apply {
            songKey = song.id
            onlineId = song.id
            title = song.name
            artist = song.artist
            album = song.album
            platform = "wy"
            quality = MusicPrefs.quality(ctx)
            proxy = MusicPrefs.proxy(ctx)
            musicInfoJson = OnlineSourceResolver.musicInfoJson(
                "wy", song.id, song.name, song.artist, song.album)
        }
        val r = OnlineSourceResolver.resolve(ctx, req, LxBridge)
        if (r.ok && OnlineSourceResolver.isUsableUrl(r.url)) r else null
    } catch (t: Throwable) {
        android.util.Log.w("Player", "多源解析异常，走外链兜底", t)
        null
    }

    private fun doPlay(ctx: Context, url: String) {
        try {
            val p = MediaPlayer()
            p.setDataSource(url)
            p.setOnPreparedListener {
                val seek = restorePositionMs
                if (seek > 0) {
                    try { it.seekTo(seek) } catch (t: Throwable) {}
                    restorePositionMs = 0
                }
                it.start()
                val dur = it.duration
                _state.value = _state.value.copy(
                    playing = true, durationMs = dur,
                    positionMs = if (seek > 0) seek else 0,
                )
                startProgressTicker(true)
            }
            p.setOnCompletionListener { onCompleted() }
            p.setOnErrorListener { _, what, extra ->
                android.util.Log.w("Player", "播放错误 what=$what extra=$extra url=$url")
                _state.value = _state.value.copy(playing = false, sourceName = "播放失败，按下一首试试")
                startProgressTicker(false)
                true
            }
            p.prepareAsync()
            mp = p
        } catch (t: Throwable) {
            android.util.Log.w("Player", "起播失败 $url", t)
            _state.value = _state.value.copy(playing = false, sourceName = "起播失败")
        }
    }

    // ---------------------------------------------------------------- 进度

    private fun startProgressTicker(running: Boolean) {
        progressJob?.cancel()
        if (!running) return
        progressJob = scope.launch {
            while (true) {
                delay(500L)
                val p = mp ?: break
                val pos = try { p.currentPosition } catch (t: Throwable) { break }
                val dur = try { p.duration } catch (t: Throwable) { 0 }
                _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
                // 每 5 秒落一次盘，进度能续上又不至于狂写 SharedPreferences
                val now = System.currentTimeMillis()
                if (now - persistedAt > 5000L) {
                    persistedAt = now
                    persist()
                }
            }
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val s = _state.value
        if (s.queue.isEmpty() || s.index < 0) return
        MusicPrefs.savePlayback(ctx, s.queue, s.index, s.positionMs)
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        try { mp?.release() } catch (t: Throwable) {}
        mp = null
    }

    fun release() {
        persist()
        releasePlayer()
        LxBridge.close()
    }
}

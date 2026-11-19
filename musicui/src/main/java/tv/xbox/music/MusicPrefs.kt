package tv.xbox.music

import android.content.Context
import com.github.catvod.music.NeteaseClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 新版音乐模块的偏好存储。
 *
 * <p>和旧 MusicPage 用的 DeskPrefs 分开：新版是独立进程里的 Compose 页面，
 * 自己管自己的播放风格、音质、代理、搜索历史、播放状态，互不干扰。
 * 唯一共享的是网易 cookie —— 那个由 NeteaseClient 自己落在 core 的文件里。
 */
object MusicPrefs {
    private const val PREF = "xbox_music_v2"
    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 播放页风格
    /** 0 动感涟漪 / 1 简约 / 2 精简时钟（照 MuYin 的 LandscapeLyricEffects） */
    fun style(c: Context): Int = sp(c).getInt("stage_style", 0).coerceIn(0, 2)
    fun setStyle(c: Context, style: Int) = sp(c).edit().putInt("stage_style", style).apply()

    // ---------------------------------------------------------------- 音质 / 代理
    fun quality(c: Context): String = sp(c).getString("quality", "320k") ?: "320k"
    fun setQuality(c: Context, q: String) = sp(c).edit().putString("quality", q).apply()

    fun proxy(c: Context): String = sp(c).getString("proxy", "") ?: ""
    fun setProxy(c: Context, p: String) = sp(c).edit().putString("proxy", p).apply()

    /** 播放模式：0 列表循环 / 1 单曲循环 / 2 随机 */
    fun playMode(c: Context): Int = sp(c).getInt("play_mode", 0).coerceIn(0, 2)
    fun setPlayMode(c: Context, mode: Int) = sp(c).edit().putInt("play_mode", mode).apply()

    // ---------------------------------------------------------------- 搜索历史
    fun history(c: Context): List<String> =
        sp(c).getStringSet("history", emptySet())?.toList()?.take(12) ?: emptyList()

    fun addHistory(c: Context, q: String) {
        if (q.isBlank()) return
        val set = java.util.LinkedHashSet(sp(c).getStringSet("history", emptySet()) ?: emptySet())
        set.remove(q)
        sp(c).edit().putStringSet("history", (listOf(q) + set).take(12).toSet()).apply()
    }

    fun clearHistory(c: Context) = sp(c).edit().remove("history").apply()

    // ---------------------------------------------------------------- 播放状态
    data class Playback(
        val queue: List<SongSnap>,
        val index: Int,
        val positionMs: Int,
    )

    data class SongSnap(
        val id: String, val name: String, val artist: String,
        val album: String, val picUrl: String, val durationMs: Long,
    )

    private fun SongSnap.json(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("artist", artist)
        put("album", album); put("pic", picUrl); put("dur", durationMs)
    }

    private fun songSnap(o: JSONObject) = SongSnap(
        id = o.optString("id"), name = o.optString("name"),
        artist = o.optString("artist"), album = o.optString("album"),
        picUrl = o.optString("pic"), durationMs = o.optLong("dur"),
    )

    /** 存队列 + 位置。切歌、暂停、每隔几秒各写一次；恢复时不自动出声。 */
    fun savePlayback(c: Context, queue: List<NeteaseClient.Song>, index: Int, positionMs: Int) {
        if (queue.isEmpty()) return
        val arr = JSONArray()
        queue.forEach { arr.put(songSnapJson(it)) }
        val root = JSONObject().apply {
            put("queue", arr); put("index", index); put("pos", positionMs)
        }
        sp(c).edit().putString("playback", root.toString()).apply()
    }

    private fun songSnapJson(s: NeteaseClient.Song) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("artist", s.artist)
        put("album", s.album); put("pic", s.picUrl); put("dur", s.durationMs)
    }

    fun playback(c: Context): Playback? = try {
        val text = sp(c).getString("playback", "") ?: ""
        if (text.isEmpty()) null
        else {
            val root = JSONObject(text)
            val arr = root.optJSONArray("queue") ?: JSONArray()
            val queue = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::songSnap) }
            if (queue.isEmpty()) null
            else Playback(queue, root.optInt("index", 0), root.optInt("pos", 0))
        }
    } catch (t: Throwable) {
        null
    }

    fun clearPlayback(c: Context) = sp(c).edit().remove("playback").apply()
}

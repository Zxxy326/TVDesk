package tv.xbox.music

import android.content.Context
import com.github.catvod.music.LxManager
import com.github.catvod.music.LxRuntime
import com.github.catvod.music.LxSource
import com.github.catvod.music.OnlineSourceResolver
import java.io.File

/**
 * 把 core 的 LX 音源体系接成 [OnlineSourceResolver.JsProvider]。
 *
 * <p>旧 MusicPage 里那一大段 jsProvider / runtimeFor / readyRuntime 就是干这个的。
 * 新版是独立模块，照搬成一份无 UI 的桥：扫描音源目录、缓存每个脚本的运行时、
 * 首次取链前把脚本跑起来（墨澜源首跑十几秒），其余交给 [OnlineSourceResolver]。
 *
 * <p>所有扫描和 test 都是阻塞重活，调用方（Player）必须在后台线程调。
 */
object LxBridge : OnlineSourceResolver.JsProvider {

    @Volatile
    private var snapshot: List<LxSource> = emptyList()
    private val runtimes = java.util.concurrent.ConcurrentHashMap<String, LxRuntime>()
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    /** 重新扫描音源目录并贴回上次测试结果。返回当前清单。 */
    fun refresh(): List<LxSource> {
        val ctx = appContext ?: return snapshot
        val list = LxManager.scan(ctx)
        LxManager.applyState(ctx, list)
        snapshot = list
        return list
    }

    fun installed(): List<LxSource> = snapshot

    fun runtimeOf(fileName: String): LxRuntime? {
        val source = snapshot.firstOrNull { it.fileName == fileName } ?: return null
        return runtimes.getOrPut(fileName) { LxRuntime() }
    }

    override fun installedSources(): List<LxSource> = snapshot

    override fun runtimeFor(fileName: String): LxRuntime? = runtimeOf(fileName)

    override fun readyRuntime(fileName: String): LxRuntime? {
        val ctx = appContext ?: return null
        val source = snapshot.firstOrNull { it.fileName == fileName } ?: return null
        val runtime = runtimes.getOrPut(fileName) { LxRuntime() }
        if (runtime.isReady) return runtime
        return synchronized(runtime) {
            if (runtime.isReady) return@synchronized runtime
            try {
                val script = LxManager.readAll(File(LxManager.dir(ctx), fileName))
                val init = runtime.test(script, source)
                if (!init.ok) {
                    android.util.Log.w("LxBridge", "音源 ${source.fileName} 初始化失败：${init.error}")
                    return@synchronized null
                }
                // 把跑出来的平台列表贴回元数据，音源页这样才能显示支持什么
                source.platforms.clear()
                source.platforms.addAll(init.platforms)
                source.hasRequestHandler = init.hasRequestHandler
                source.status = LxSource.STATUS_OK
                LxManager.saveState(ctx, snapshot)
            } catch (t: Throwable) {
                android.util.Log.w("LxBridge", "音源初始化异常", t)
                return@synchronized null
            }
            if (runtime.isReady) runtime else null
        }
    }

    fun close() {
        runtimes.values.forEach { it.close() }
        runtimes.clear()
    }
}

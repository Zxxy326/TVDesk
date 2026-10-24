package com.tvdesk.core.douyin;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在设备本机算抖音网页接口要的 {@code a_bogus}。
 *
 * <h3>为什么必须自己算</h3>
 * 实测：{@code comment/list} 不带签名时服务端给的是
 * HTTP 200 + 长度 0 的空响应，同一串参数补上 {@code a_bogus} 就是 {@code status_code=0} 加 20 条评论。
 * 这一项卡的是签名，不是登录 cookie，所以它是评论/搜索唯一的前置条件。
 *
 * <h3>算法从哪来</h3>
 * assets 里那三个文件的来源与许可写在 {@code core/src/main/assets/douyin/NOTICE.txt}：
 * 算法（utils.js + sm3.js）来自 Apache-2.0 的 {@code ylcangel/douyin_sign}，
 * 浏览器垫片（dy_shim.js）是我们自己写的。上游同目录的 vm_decode.js / env_test.js 没打包——
 * 它们只服务「模拟浏览器环境自检」那条分支，实测关掉后照样能出被接受的签名，省 115KB。
 *
 * <h3>为什么单独一条线程</h3>
 * quickjs 的 ctx 有线程亲和性，所有 evaluate/call 必须在同一条线程上（和
 * {@code LxRuntime} 同样的处理）。{@link #executor} 就是那条线程：
 * 谁调 {@link #aBogus(String)} 谁提交过去并等结果。
 * 注意因此<b>不能从这条线程内部再调 aBogus</b>，那样是自己等自己——{@link #onSignerThread()} 挡的就是这一种。
 *
 * <h3>失败怎么办</h3>
 * 取不到签名一律返回 {@code null}，由调用方按「没签名」处理。
 * 推荐流和相关推荐本来就是免签的，不能因为签名器坏了把能刷的部分一起搭进去。
 */
public class DouyinSigner implements Closeable {

    private static final String TAG = "DouyinSigner";

    /** 加载超时：36KB JS 的解析在中低端盒子上也要不了更久，超了就是环境不对 */
    private static final long INIT_TIMEOUT_MS = 15000;
    /** 单次取签超时。node 上实测 8ms，给到 5 秒是纯保险 */
    private static final long SIGN_TIMEOUT_MS = 5000;

    /** 加载顺序有依赖：垫片先给出 window/navigator，utils.js 才会去读它们 */
    private static final String[] SCRIPTS = {"douyin/dy_shim.js", "douyin/sm3.js", "douyin/utils.js"};

    private static volatile DouyinSigner instance;

    private final Context app;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean broken = new AtomicBoolean(false);

    private QuickJSContext ctx;
    private JSFunction getABogus;
    private Thread signerThread;

    private DouyinSigner(Context context) {
        app = context.getApplicationContext();
    }

    public static DouyinSigner get(Context context) {
        if (instance == null) {
            synchronized (DouyinSigner.class) {
                if (instance == null) {
                    instance = new DouyinSigner(context);
                }
            }
        }
        return instance;
    }

    /**
     * 提前把 ctx 建好。
     *
     * <p>用在「用户还没按到评论就先备好」：首次取签要加载并解析三个脚本，
     * 放在评论按钮那一下就成了按下去要等一下。异步跑，失败也不管——真正要用时还会再试。
     */
    public void prepare() {
        if (broken.get()) {
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureCtx();
                } catch (Throwable th) {
                    broken.set(true);
                    Log.w(TAG, "预热失败", th);
                }
            }
        });
    }

    /**
     * 算一条查询串的签名。
     *
     * @param query 服务端将看到的那份查询串<b>原文</b>：不带前导 {@code ?}，也不做 URL 编码。
     *              {@code DouyinClient.query()} 刻意不编码就是为了这里能对得上
     * @return a_bogus（只含 {@code A-Za-z0-9+/=}）；拿不到返回 {@code null}
     */
    public String aBogus(String query) {
        if (TextUtils.isEmpty(query) || broken.get()) {
            return null;
        }
        if (onSignerThread()) {
            // 已经在 ctx 线程上，直接算：再 submit 一次就是自己等自己
            return signNow(query);
        }
        try {
            Future<String> future = executor.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    try {
                        ensureCtx();
                    } catch (Throwable th) {
                        broken.set(true);
                        Log.w(TAG, "签名环境起不来", th);
                        return null;
                    }
                    return signNow(query);
                }
            });
            // 首次调用要在同一次等待里把环境建起来，所以预算是「加载 + 取签」两段之和
            return future.get(INIT_TIMEOUT_MS + SIGN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable th) {
            Log.w(TAG, "取签失败", th);
            return null;
        }
    }

    /** 签名环境是否已经就绪；UI 用它决定要不要先把「这版还没接」的提示咽回去 */
    public boolean isReady() {
        return !broken.get() && getABogus != null;
    }

    private String signNow(String query) {
        if (getABogus == null) {
            return null;
        }
        try {
            Object value = getABogus.call(query);
            String signed = value == null ? "" : String.valueOf(value);
            return TextUtils.isEmpty(signed) ? null : signed;
        } catch (Throwable th) {
            // 算法内部有异常抛出点（btoa 遇到非 latin-1 字符就抛），查询串里出现中文时可能走到这里
            Log.w(TAG, "a_bogus 计算异常", th);
            return null;
        }
    }

    private boolean onSignerThread() {
        return signerThread != null && signerThread == Thread.currentThread();
    }

    /** 只在 executor 那条线程上调用 */
    private void ensureCtx() {
        if (ctx != null && getABogus != null) {
            return;
        }
        signerThread = Thread.currentThread();
        ctx = QuickJSContext.create();
        ctx.setMaxStackSize(1024 * 1024);
        for (String path : SCRIPTS) {
            String source = readAsset(path);
            if (TextUtils.isEmpty(source)) {
                throw new IllegalStateException("缺少签名脚本：" + path);
            }
            ctx.evaluate(source, path);
        }
        // UA 要和 DouyinClient 发出去的那一份一致，环境值与参数表对不上就是自找风控
        JSObject global = ctx.getGlobalObject();
        JSFunction prepare = global.getJSFunction("dyPrepare");
        if (prepare == null) {
            throw new IllegalStateException("签名脚本没有导出 dyPrepare");
        }
        prepare.call(DouyinClient.userAgent());
        getABogus = global.getJSFunction("dyGetABogus");
        if (getABogus == null) {
            throw new IllegalStateException("签名脚本没有导出 dyGetABogus");
        }
        Log.i(TAG, "a_bogus 环境就绪");
    }

    private String readAsset(String path) {
        InputStream in = null;
        try {
            in = app.getAssets().open(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "读取脚本失败：" + path, e);
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ctx != null) {
                        ctx.destroy();
                    }
                } catch (Throwable ignored) {
                }
                ctx = null;
                getABogus = null;
            }
        });
        executor.shutdown();
    }
}

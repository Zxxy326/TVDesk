package com.github.catvod.music;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LX 规范音源脚本的运行时。
 *
 * <h3>为什么不用 TVBox 现成的 JsSpider</h3>
 * 那是「影视点播源」的约定：脚本导出 {@code __jsEvalReturn}，宿主按 {@code homeContent} /
 * {@code categoryContent} / {@code playerContent} 这些固定函数名去调。LX 音乐源完全不是这套：
 * 它从 {@code globalThis.lx} 里拿 {@code EVENT_NAMES/request/on/send/utils}，
 * 初始化完主动 {@code send('inited', { sources })}，之后靠 {@code on('request', ...)} 收活。
 * 两套协议对不上，所以这里自己建 ctx、自己注入 {@code lx}。
 *
 * <h3>跨语言只走字符串</h3>
 * JS 回调里需要拿到响应对象、错误对象。直接在 Java 里 createNewJSObject 再塞进去也能work，
 * 但那些 JSObject 有线程亲和性，一旦回调线程和 ctx 线程不一致就会踩到 wrapper 的对象表。
 * 所以桥接层统一用「字符串进出」：Java 只回字符串，JS 侧自己 JSON.parse / new Error。
 * 少一次类型转换，也少一类崩溃。
 *
 * <h3>为什么单独开线程</h3>
 * quickjs 的 ctx 不是线程安全的，所有 evaluate/call 必须在同一个线程上。
 * {@link #executor} 就是这个线程；HTTP 回调统一 submit 回来。
 */
public class LxRuntime implements Closeable {

    private static final String TAG = "LxRuntime";

    /** 初始化超时。混淆过的脚本首跑要解字符串表，比明文脚本慢 */
    private static final long INIT_TIMEOUT_MS = 30000;

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private QuickJSContext ctx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    /** 脚本 send('inited', ...) 时的载荷 */
    private volatile String initedJson;
    /** 初始化期间捕获的 JS 异常 */
    private volatile String scriptError;

    /** 脚本是否已经执行过。混淆脚本首跑要解字符串表，十几秒，绝不能每次取链接都重来 */
    private volatile boolean loaded;
    /** 第一次 test() 的结果，后续直接复用 */
    private volatile Result cachedResult;

    private final List<String> logs = new ArrayList<>();

    /** 一次测试的结果 */
    public static class Result {
        public boolean ok;
        public String error = "";
        /** 脚本声明的平台 */
        public final List<LxSource.Platform> platforms = new ArrayList<>();
        /** 脚本自己打出来的日志，出问题时给用户看 */
        public final List<String> logs = new ArrayList<>();
        /**
         * 脚本是否注册了 request 处理器。
         * 这是「能不能取播放链接」的前提——只 send('inited') 不 on('request') 的源
         * 能通过初始化但一取链接就空，得在测试阶段就暴露出来。
         */
        public boolean hasRequestHandler;

        public String summary() {
            if (!ok) {
                return TextUtils.isEmpty(error) ? "初始化失败" : error;
            }
            int quality = 0;
            for (LxSource.Platform p : platforms) {
                quality += p.qualities.size();
            }
            return platforms.size() + " 个平台 / " + quality + " 种音质"
                    + (hasRequestHandler ? "" : "（未注册取链接接口）");
        }
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 加载并执行脚本，等它 send('inited')。
     *
     * <p>阻塞调用，必须在后台线程用。内部已经把 JS 执行放进自己的单线程，
     * 这里的阻塞只是在等那一步完成。
     */
    public Result test(String script, LxSource meta) {
        // 已经跑过就直接给上次的结论。脚本执行成本极高（混淆代码首跑 15~20 秒），
        // 而同一个 runtime 里再跑一遍还会把脚本内部的全局状态初始化两次。
        if (loaded && cachedResult != null) {
            return cachedResult;
        }
        Result result = new Result();
        if (TextUtils.isEmpty(script)) {
            result.error = "脚本内容为空";
            return result;
        }
        Future<?> future = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    createCtx(meta);
                    ctx.evaluate(script, meta == null ? "source.js" : meta.fileName);
                } catch (Throwable th) {
                    scriptError = describe(th);
                    Log.w(TAG, "evaluate failed: " + scriptError);
                }
            }
        });
        try {
            future.get(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            scriptError = "执行超时或异常：" + describe(e);
            Log.w(TAG, "test timeout: " + e);
        }

        result.logs.addAll(logs);
        if (!TextUtils.isEmpty(scriptError)) {
            result.error = scriptError;
            return result;
        }
        if (TextUtils.isEmpty(initedJson)) {
            // 脚本跑完了却没 send('inited')——大概率不是 LX 源，或者依赖了没实现的 API
            result.error = "脚本没有回调初始化结果（不是 LX 规范，或依赖了未实现的能力）";
            return result;
        }
        try {
            JSONObject obj = new JSONObject(initedJson);
            if (!obj.optBoolean("status", false)) {
                result.error = "脚本自报初始化失败（status=false）";
                return result;
            }
            JSONObject sources = obj.optJSONObject("sources");
            if (sources == null) {
                result.error = "脚本没有声明任何平台（sources 为空）";
                return result;
            }
            Iterator<String> keys = sources.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject item = sources.optJSONObject(id);
                if (item == null) {
                    continue;
                }
                LxSource.Platform p = new LxSource.Platform();
                p.id = id;
                String name = item.optString("name", "");
                p.name = TextUtils.isEmpty(name) ? LxSource.platformName(id) : name;
                JSONArray actions = item.optJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        p.actions.add(actions.optString(i));
                    }
                }
                JSONArray qualities = item.optJSONArray("qualitys");
                if (qualities == null) {
                    qualities = item.optJSONArray("qualities");
                }
                if (qualities != null) {
                    for (int i = 0; i < qualities.length(); i++) {
                        p.qualities.add(qualities.optString(i));
                    }
                }
                result.platforms.add(p);
            }
            result.ok = !result.platforms.isEmpty();
            if (!result.ok) {
                result.error = "脚本声明的平台列表为空";
            }
            result.hasRequestHandler = hasRequestHandler();
        } catch (Exception e) {
            result.error = "解析初始化结果失败：" + describe(e);
        }
        if (result.ok) {
            loaded = true;
            cachedResult = result;
        }
        return result;
    }

    /** 脚本有没有 on('request')。要在 ctx 线程外调，所以走 executor */
    private boolean hasRequestHandler() {
        Future<Boolean> future = executor.submit(new java.util.concurrent.Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    Object value = ctx.evaluate(
                            "typeof (globalThis.__lx_handlers && globalThis.__lx_handlers.request) === 'function'");
                    return Boolean.TRUE.equals(value);
                } catch (Throwable th) {
                    Log.w(TAG, "probe handler: " + th);
                    return false;
                }
            }
        });
        try {
            return Boolean.TRUE.equals(future.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    private void createCtx(LxSource meta) {
        ctx = QuickJSContext.create();
        ctx.setMaxStackSize(1024 * 1024);
        ctx.setConsole(new QuickJSContext.Console() {
            @Override
            public void log(String s) {
                addLog(s);
            }

            @Override
            public void info(String s) {
                addLog(s);
            }

            @Override
            public void warn(String s) {
                addLog(s);
            }

            @Override
            public void error(String s) {
                addLog(s);
            }
        });

        JSObject global = ctx.getGlobalObject();

        // 浏览器环境兜底：混淆脚本常拿 typeof window/document 判运行环境
        ctx.evaluate("if (typeof globalThis.window === 'undefined') globalThis.window = globalThis;", "env.js");
        ctx.evaluate("if (typeof globalThis.document === 'undefined') globalThis.document = {};", "env.js");

        installBridge(global);
        ctx.evaluate(buildBootstrap(meta), "lx_bootstrap.js");
    }

    private void addLog(String s) {
        if (TextUtils.isEmpty(s)) {
            return;
        }
        synchronized (logs) {
            if (logs.size() < 200) {
                logs.add(s);
            }
        }
        Log.i(TAG, "js: " + s);
    }

    /** 把 Java 能力挂到 globalThis 上，供 bootstrap 里的 lx 对象调用 */
    private void installBridge(JSObject global) {
        ctx.setProperty(global, "__lx_send", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                String event = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                String payload = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : "";
                if ("inited".equals(event)) {
                    initedJson = payload;
                }
                return null;
            }
        });

        ctx.setProperty(global, "__lx_request", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                String url = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                String options = args.length > 1 && args[1] != null ? String.valueOf(args[1]) : "{}";
                Object holder = args.length > 2 ? args[2] : null;
                request(url, options, holder);
                return null;
            }
        });

        ctx.setProperty(global, "__lx_defer", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                // 关键：把「真正调用 handler」推到 evaluate 返回之后。
                // evaluate 是同步的，而且会跑完整个 microtask 队列；如果 handler 在
                // evaluate 期间就发 HTTP，等回调要提交回 executor 时，executor 正被
                // evaluate 占着——两边互等，直接死锁（实测表现为「HTTP 200 已收到但
                // 回调永远不执行」）。
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 用 JSFunction.call 而不是 ctx.evaluate：
                            // evaluate 会把 microtask 队列一路跑完，async handler 里的
                            // await 就变成了「等 HTTP」，而 HTTP 回调又要排队进这条线程，
                            // 两边互锁。call 只调函数本身，挂起就返回，不会把线程占死。
                            JSObject global = ctx.getGlobalObject();
                            JSFunction run = global.getJSFunction("__lx_run");
                            if (run != null) {
                                run.call();
                            }
                        } catch (Throwable th) {
                            Log.w(TAG, "run handler: " + th);
                        }
                    }
                });
                return null;
            }
        });

        ctx.setProperty(global, "__lx_log", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                addLog(str(args, 0));
                return null;
            }
        });

        ctx.setProperty(global, "__lx_md5", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                String s = args.length > 0 && args[0] != null ? String.valueOf(args[0]) : "";
                return md5(s);
            }
        });

        ctx.setProperty(global, "__lx_random_hex", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                int n = 16;
                try {
                    n = ((Number) args[0]).intValue();
                } catch (Throwable ignored) {
                }
                byte[] buf = new byte[Math.max(0, Math.min(n, 4096))];
                new java.security.SecureRandom().nextBytes(buf);
                return toHex(buf);
            }
        });

        ctx.setProperty(global, "__lx_aes", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                // (hex, mode, key, iv, encrypt)
                String hex = str(args, 0);
                String mode = str(args, 1);
                String key = str(args, 2);
                String iv = str(args, 3);
                boolean encrypt = args.length > 4 && Boolean.TRUE.equals(args[4]);
                return aes(hex, mode, key, iv, encrypt);
            }
        });

        ctx.setProperty(global, "__lx_inflate", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                return zlib(str(args, 0), false);
            }
        });

        ctx.setProperty(global, "__lx_deflate", new JSCallFunction() {
            @Override
            public Object call(Object... args) {
                return zlib(str(args, 0), true);
            }
        });
    }

    private static String str(Object[] args, int index) {
        return args.length > index && args[index] != null ? String.valueOf(args[index]) : "";
    }

    /**
     * lx.request 的实现。
     *
     * <p>回调通过 holder.cb 拿，调用约定是 {@code cb(errString, respJsonString)}，
     * 成功时 errString 为空串。
     */
    private void request(String url, String optionsJson, Object holderObj) {
        if (!(holderObj instanceof JSObject)) {
            return;
        }
        JSObject holder = (JSObject) holderObj;
        final JSFunction cb = holder.getJSFunction("cb");
        if (cb == null) {
            return;
        }
        cb.hold();

        String method = "GET";
        String body = null;
        String contentType = null;
        long timeoutMs = 20000;
        okhttp3.Headers.Builder headerBuilder = new okhttp3.Headers.Builder();
        try {
            JSONObject opt = new JSONObject(TextUtils.isEmpty(optionsJson) ? "{}" : optionsJson);
            String m = opt.optString("method", "GET");
            if (!TextUtils.isEmpty(m)) {
                method = m.toUpperCase();
            }
            if (opt.has("timeout")) {
                timeoutMs = opt.optLong("timeout", 20000);
            }
            JSONObject headers = opt.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    headerBuilder.add(k, headers.optString(k, ""));
                    if ("content-type".equalsIgnoreCase(k)) {
                        contentType = headers.optString(k, "");
                    }
                }
            }
            if (opt.has("body")) {
                Object b = opt.opt("body");
                if (b instanceof JSONObject) {
                    body = b.toString();
                    if (contentType == null) {
                        contentType = "application/json";
                    }
                } else if (b != null && !"null".equals(String.valueOf(b))) {
                    body = String.valueOf(b);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse options: " + e);
        }

        Request.Builder rb = new Request.Builder().url(url).headers(headerBuilder.build());
        if ("POST".equals(method) || "PUT".equals(method)) {
            RequestBody rbBody = RequestBody.create(
                    MediaType.parse(contentType == null ? "application/x-www-form-urlencoded" : contentType),
                    body == null ? "" : body);
            rb.method(method, rbBody);
        } else if ("HEAD".equals(method)) {
            rb.head();
        } else {
            rb.get();
        }

        final OkHttpClient client = timeoutMs == 20000 ? http
                : http.newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();

        // 脚本发出去的每个请求都留痕：音源「能初始化但取不到链接」时，
        // 判断是没发请求、还是发出去了被拒，全靠这行日志
        Log.i(TAG, "HTTP " + method + " " + url
                + (body == null ? "" : " body=" + (body.length() > 300 ? body.substring(0, 300) + "…" : body)));

        try {
            client.newCall(rb.build()).enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) {
                    String err = "";
                    String payload = "{}";
                    try {
                        String text = response.body() == null ? "" : response.body().string();
                        Log.i(TAG, "HTTP <- " + response.code() + " len=" + text.length());
                        JSONObject obj = new JSONObject();
                        obj.put("statusCode", response.code());
                        JSONObject headers = new JSONObject();
                        for (String name : response.headers().names()) {
                            headers.put(name, response.header(name, ""));
                        }
                        obj.put("headers", headers);
                        obj.put("body", text);
                        payload = obj.toString();
                    } catch (Exception e) {
                        err = describe(e);
                    } finally {
                        response.close();
                    }
                    dispatch(cb, err, payload);
                }

                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    Log.w(TAG, "HTTP !! " + e);
                    dispatch(cb, e == null ? "请求失败" : e.toString(), "{}");
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "enqueue 失败: " + th);
            dispatch(cb, "请求构造失败：" + describe(th), "{}");
        }
    }

    private void dispatch(final JSFunction cb, final String err, final String payload) {
        if (destroyed.get()) {
            Log.w(TAG, "回调被丢弃：runtime 已销毁");
            return;
        }
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "dispatch -> js cb");
                        cb.call(err == null ? "" : err, payload);
                        // 脚本的 handler 是 async 的，上面这次 call 只是把 await 的
                        // 续延塞进 microtask 队列；不主动跑一遍队列，续延永远不会执行，
                        // 外面就永远等不到 done 回调。
                        pump();
                    } catch (Throwable th) {
                        Log.w(TAG, "callback: " + th);
                    }
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "回调调度失败: " + th);
        }
    }

    /**
     * 跑一遍 quickjs 的 pending job（microtask）队列。
     *
     * <p>脚本的 handler 是 async 的：Java→JS 的 cb.call 只把 await 的续延
     * 挂进 job 队列，续延本体要等队列被 drain 才会执行。wrapper 没暴露
     * executePendingJob，但 JS_Eval 结束时会顺手 drain 一遍——所以用一次
     * 空 evaluate 当 pump。成本低（一个常量表达式），语义刚好。
     */
    private void pump() {
        try {
            ctx.evaluate("0", "pump.js");
        } catch (Throwable th) {
            Log.w(TAG, "pump: " + th);
        }
    }

    // ------------------------------------------------------------- 工具能力

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return toHex(md.digest(input.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (TextUtils.isEmpty(hex)) {
            return new byte[0];
        }
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /**
     * AES。mode 形如 {@code aes-128-cbc} / {@code aes-256-ecb}，
     * 和 LX 的 utils.crypto.aesEncrypt(buf, mode, key, iv) 对齐。
     */
    private static String aes(String hex, String mode, String key, String iv, boolean encrypt) {
        try {
            String m = TextUtils.isEmpty(mode) ? "aes-128-cbc" : mode.toLowerCase();
            String algorithm;
            String transformation;
            if (m.contains("ecb")) {
                algorithm = m.contains("192") ? "AES" : (m.contains("256") ? "AES" : "AES");
                transformation = "AES/ECB/PKCS5Padding";
            } else {
                algorithm = "AES";
                transformation = "AES/CBC/PKCS5Padding";
            }
            byte[] keyBytes = key.getBytes("UTF-8");
            // LX 传的是字符串 key，按 AES 的要求补齐/截断到 16/24/32
            int want = m.contains("256") ? 32 : (m.contains("192") ? 24 : 16);
            byte[] realKey = new byte[want];
            System.arraycopy(keyBytes, 0, realKey, 0, Math.min(keyBytes.length, want));

            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec spec = new SecretKeySpec(realKey, algorithm);
            if (transformation.contains("CBC")) {
                byte[] ivBytes = TextUtils.isEmpty(iv) ? new byte[16] : iv.getBytes("UTF-8");
                byte[] realIv = new byte[16];
                System.arraycopy(ivBytes, 0, realIv, 0, Math.min(ivBytes.length, 16));
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, spec, new IvParameterSpec(realIv));
            } else {
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, spec);
            }
            byte[] input = fromHex(hex);
            return toHex(cipher.doFinal(input));
        } catch (Exception e) {
            Log.w(TAG, "aes: " + e);
            return "";
        }
    }

    /** zlib 压缩/解压，同样 hex 进出 */
    private static String zlib(String hex, boolean deflate) {
        try {
            byte[] input = fromHex(hex);
            if (deflate) {
                java.util.zip.Deflater d = new java.util.zip.Deflater();
                d.setInput(input);
                d.finish();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                while (!d.finished()) {
                    int n = d.deflate(buf);
                    out.write(buf, 0, n);
                }
                d.end();
                return toHex(out.toByteArray());
            }
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(input);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            inf.end();
            return toHex(out.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "zlib: " + e);
            return "";
        }
    }

    // ------------------------------------------------------------- 取播放链接

    /** 取链接的结果 */
    public static class UrlResult {
        public boolean ok;
        public String url = "";
        public String error = "";

        public String display() {
            return ok ? url : ("失败：" + error);
        }
    }

    private volatile String invokeErr;
    private volatile String invokeData;

    /**
     * 让脚本取一次播放链接。
     *
     * <p>这是整条链路唯一有实际产出的地方：初始化成功只说明脚本加载完了，
     * 只有真的调 handler 走到 {@code lx.request} 发出 HTTP，才能证明桥接层是通的。
     * 阻塞调用，必须放后台线程。
     *
     * @param source         平台 key（tx / wy / kw / kg / mg）
     * @param musicInfoJson  该平台要求的歌曲信息，字段各平台不同
     * @param quality        音质 key（128k / 320k / flac …）
     */
    public UrlResult resolveUrl(String source, String musicInfoJson, String quality) {
        UrlResult result = new UrlResult();
        if (ctx == null) {
            result.error = "运行时未初始化";
            return result;
        }
        final String payloadJson;
        try {
            JSONObject info = new JSONObject();
            info.put("musicInfo", new JSONObject(TextUtils.isEmpty(musicInfoJson) ? "{}" : musicInfoJson));
            info.put("type", TextUtils.isEmpty(quality) ? "128k" : quality);
            JSONObject payload = new JSONObject();
            payload.put("source", source);
            payload.put("action", "musicUrl");
            payload.put("info", info);
            payloadJson = payload.toString();
        } catch (Exception e) {
            result.error = "构造请求失败：" + describe(e);
            return result;
        }

        invokeErr = null;
        invokeData = null;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSObject global = ctx.getGlobalObject();
                    ctx.setProperty(global, "__lx_payload", payloadJson);
                    ctx.setProperty(global, "__lx_done", new JSCallFunction() {
                        @Override
                        public Object call(Object... args) {
                            invokeErr = str(args, 0);
                            invokeData = str(args, 1);
                            Log.i(TAG, "done! err=" + invokeErr
                                    + " data=" + (invokeData == null ? "null"
                                    : invokeData.length() > 800 ? invokeData.substring(0, 800) + "…" : invokeData));
                            latch.countDown();
                            return null;
                        }
                    });
                    ctx.evaluate("__lx_invoke(__lx_payload, __lx_done)", "invoke.js");
                } catch (Throwable th) {
                    invokeErr = describe(th);
                    latch.countDown();
                }
            }
        });

        try {
            // 实测墨澜源取一次链接全程 ~55s：请求体加密 19s + 响应处理 36s，
            // 都是混淆脚本在 quickjs 里跑纯 JS 加密，没法省。45s 会先超时。
            if (!latch.await(90, TimeUnit.SECONDS)) {
                result.error = "取链接超时（90 秒）";
                return result;
            }
        } catch (InterruptedException e) {
            result.error = "被中断";
            return result;
        }

        if (!TextUtils.isEmpty(invokeErr)) {
            result.error = invokeErr;
            return result;
        }
        try {
            // LX 规范的 musicUrl handler 直接返回 url 字符串；有的源会包一层
            // {url, size...} 对象。两种都认。
            Object parsed = new org.json.JSONTokener(invokeData).nextValue();
            String url;
            if (parsed instanceof String) {
                url = (String) parsed;
            } else if (parsed instanceof JSONObject) {
                url = ((JSONObject) parsed).optString("url", "");
            } else {
                url = "";
            }
            if (TextUtils.isEmpty(url) || "null".equals(url)) {
                result.error = "脚本没有返回 url（返回：" + invokeData + "）";
                return result;
            }
            result.ok = true;
            result.url = url;
            return result;
        } catch (Exception e) {
            result.error = "返回内容不是 JSON：" + invokeData;
            return result;
        }
    }

    // ------------------------------------------------------------- bootstrap

    /**
     * 注入的 JS 引导代码。
     *
     * <p>只做三件事：把 {@code globalThis.lx} 按 LX 的接口形状搭出来、把 Java 桥接函数
     * 包成 JS 里好用的形态（ArrayBuffer / Error）、把 send 的载荷转成纯 JSON。
     * 真正的业务逻辑一行都不写在这里——脚本想怎么用就怎么用。
     */
    private String buildBootstrap(LxSource meta) {
        JSONObject info = new JSONObject();
        try {
            if (meta != null) {
                info.put("name", meta.displayName());
                info.put("description", meta.description);
                info.put("version", meta.version);
                info.put("author", meta.author);
                info.put("homepage", meta.homepage);
            }
        } catch (Exception ignored) {
        }
        String infoJson = info.toString();

        return "(function(){\n"
                + "  var g = globalThis;\n"
                + "  if (g.lx) return;\n"
                + "  var handlers = {};\n"
                + "  g.__lx_handlers = handlers;\n"
                + "  function plain(v){ try { return JSON.parse(JSON.stringify(v)); } catch(e){ return null; } }\n"
                + "  function hexToBuf(hex){\n"
                + "    if (!hex) return new ArrayBuffer(0);\n"
                + "    var n = hex.length >> 1, u8 = new Uint8Array(n);\n"
                + "    for (var i = 0; i < n; i++) u8[i] = parseInt(hex.substr(i*2, 2), 16);\n"
                + "    return u8.buffer;\n"
                + "  }\n"
                + "  function bufToHex(b){\n"
                + "    var u8 = (b && b.byteLength !== undefined) ? new Uint8Array(b) : (b || []);\n"
                + "    var s = '';\n"
                + "    for (var i = 0; i < u8.length; i++) s += ('0' + (u8[i] & 0xff).toString(16)).slice(-2);\n"
                + "    return s;\n"
                + "  }\n"
                + "  function bufToString(b, enc){\n"
                + "    if (typeof b === 'string') return b;\n"
                + "    var u8 = (b && b.byteLength !== undefined) ? new Uint8Array(b) : (b || []);\n"
                + "    var s = '';\n"
                + "    for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);\n"
                + "    if (enc === 'hex') { var h=''; for (var j=0;j<u8.length;j++) h += ('0'+(u8[j]&0xff).toString(16)).slice(-2); return h; }\n"
                + "    if (enc === 'base64') { try { return g.btoa(s); } catch(e) { return s; } }\n"
                + "    try { return decodeURIComponent(escape(s)); } catch(e) { return s; }\n"
                + "  }\n"
                + "  function strToBuf(s, enc){\n"
                + "    if (enc === 'hex') return hexToBuf(s);\n"
                + "    if (enc === 'base64') { try { s = g.atob(s); } catch(e) {} }\n"
                + "    var u8 = new Uint8Array(s.length);\n"
                + "    for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i) & 0xff;\n"
                + "    return u8.buffer;\n"
                + "  }\n"
                + "  g.btoa = g.btoa || function(s){ var t='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'; var o=''; for (var i=0;i<s.length;i+=3){ var c1=s.charCodeAt(i), c2=s.charCodeAt(i+1), c3=s.charCodeAt(i+2); var e1=c1>>2, e2=((c1&3)<<4)|(c2>>4), e3=((c2&15)<<2)|(c3>>6), e4=c3&63; if (isNaN(c2)) { e3=e4=64 } else if (isNaN(c3)) { e4=64 } o += t.charAt(e1)+(e2<64?t.charAt(e2):'=')+(e3<64?t.charAt(e3):'=')+(e4<64?t.charAt(e4):'='); } return o; };\n"
                + "  g.atob = g.atob || function(s){ var t='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'; var o=''; s=String(s).replace(/[=]+$/,''); for (var i=0;i<s.length;i+=4){ var n=(t.indexOf(s.charAt(i))<<18)|(t.indexOf(s.charAt(i+1))<<12)|((t.indexOf(s.charAt(i+2))&63)<<6)|(t.indexOf(s.charAt(i+3))&63); o += String.fromCharCode((n>>16)&255); if (i+2<s.length) o += String.fromCharCode((n>>8)&255); if (i+3<s.length) o += String.fromCharCode(n&255); } return o; };\n"
                + "  g.setTimeout = g.setTimeout || function(fn, ms){ return 0; };\n"
                + "  g.clearTimeout = g.clearTimeout || function(){};\n"
                + "  g.Buffer = g.Buffer || {\n"
                + "    from: function(v, enc){ return strToBuf(String(v), enc || 'utf8'); },\n"
                + "    isBuffer: function(v){ return v && v.byteLength !== undefined; },\n"
                + "    alloc: function(n){ return new ArrayBuffer(n); },\n"
                + "    concat: function(list){ var total=0,i; for(i=0;i<list.length;i++) total += list[i].byteLength||0; var out=new Uint8Array(total), off=0; for(i=0;i<list.length;i++){ out.set(new Uint8Array(list[i]), off); off += list[i].byteLength||0; } return out.buffer; }\n"
                + "  };\n"
                + "  g.lx = {\n"
                + "    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },\n"
                + "    env: 'desktop',\n"
                + "    version: '2.0.0',\n"
                + "    currentScriptInfo: " + infoJson + ",\n"
                + "    on: function(name, fn){ handlers[name] = fn; return function(){ delete handlers[name]; }; },\n"
                + "    off: function(name){ delete handlers[name]; },\n"
                + "    send: function(name, data){ __lx_send(name, JSON.stringify(plain(data))); },\n"
                + "    request: function(url, options, callback){\n"
                + "      var opts = {};\n"
                + "      try { opts = plain(options) || {}; } catch(e) {}\n"
                + "      __lx_request(url, JSON.stringify(opts), {\n"
                + "        cb: function(errMsg, respJson){\n"
                + "          if (errMsg) { callback(new Error(errMsg), null); return; }\n"
                + "          var resp = null;\n"
                + "          try { resp = JSON.parse(respJson); } catch(e) { resp = { statusCode: 0, headers: {}, body: '' }; }\n"
                + "          callback(null, resp);\n"
                + "        }\n"
                + "      });\n"
                + "    },\n"
                + "    utils: {\n"
                + "      buffer: {\n"
                + "        from: function(v, enc){ return strToBuf(String(v), enc || 'utf8'); },\n"
                + "        bufToString: function(b, enc){ return bufToString(b, enc || 'utf8'); }\n"
                + "      },\n"
                + "      crypto: {\n"
                + "        md5: function(s){ return __lx_md5(String(s)); },\n"
                + "        randomBytes: function(n){ return hexToBuf(__lx_random_hex(n)); },\n"
                + "        aesEncrypt: function(buf, mode, key, iv){ return hexToBuf(__lx_aes(bufToHex(buf), mode, String(key), iv == null ? '' : String(iv), true)); },\n"
                + "        aesDecrypt: function(buf, mode, key, iv){ return hexToBuf(__lx_aes(bufToHex(buf), mode, String(key), iv == null ? '' : String(iv), false)); },\n"
                + "        rsaEncrypt: function(buf, key){ return buf; }\n"
                + "      },\n"
                + "      zlib: {\n"
                + "        inflate: function(buf){ return hexToBuf(__lx_inflate(bufToHex(buf))); },\n"
                + "        deflate: function(buf){ return hexToBuf(__lx_deflate(bufToHex(buf))); }\n"
                + "      }\n"
                + "    }\n"
                + "  };\n"
                + "  g.__lx_invoke = function(payloadJson, done){\n"
                + "    var h = handlers['request'];\n"
                + "    if (typeof h !== 'function') { done('脚本没有注册 request 处理器', ''); return; }\n"
                + "    var payload;\n"
                + "    try { payload = JSON.parse(payloadJson); } catch(e) { done('payload 解析失败', ''); return; }\n"
                + "    g.__lx_pending = { payload: payload, done: done };\n"
                + "    __lx_defer();\n"
                + "  };\n"
                + "  g.__lx_run = function(){\n"
                + "    var p = g.__lx_pending;\n"
                + "    if (!p) return;\n"
                + "    g.__lx_pending = null;\n"
                + "    var done = p.done, settled = false;\n"
                + "    function finish(err, data){\n"
                + "      if (settled) return; settled = true;\n"
                + "      try { __lx_log('handler finish err=' + (err || '')); } catch(e2) {}\n"
                + "      done(err ? String(err) : '', data == null ? '' : JSON.stringify(data));\n"
                + "    }\n"
                + "    try {\n"
                + "      var ret = handlers['request'](p.payload);\n"
                + "      try { __lx_log('handler returned ' + (ret && ret.then ? 'promise' : typeof ret)); } catch(e3) {}\n"
                + "      if (ret && typeof ret.then === 'function') {\n"
                + "        ret.then(function(v){ finish('', v); }, function(e){ finish(e && e.message ? e.message : String(e), ''); });\n"
                + "      } else { finish('', ret); }\n"
                + "    } catch(e) { finish(e && e.message ? e.message : String(e), ''); }\n"
                + "  };\n"
                + "})();\n";
    }

    // ---------------------------------------------------------------- 清理

    private static String describe(Throwable th) {
        if (th == null) {
            return "";
        }
        String msg = th.getMessage();
        if (TextUtils.isEmpty(msg)) {
            msg = th.getClass().getSimpleName();
        }
        return msg;
    }

    @Override
    public void close() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ctx != null) {
                        ctx.destroy();
                        ctx = null;
                    }
                } catch (Throwable th) {
                    Log.w(TAG, "destroy: " + th);
                }
            }
        });
        executor.shutdown();
    }
}

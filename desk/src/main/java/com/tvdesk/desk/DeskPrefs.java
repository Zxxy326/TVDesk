package com.tvdesk.desk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 桌面自己的设置。
 *
 * <p>用 {@code getSharedPreferences} 而不是 TVBox 那套 Hawk：desk 模块不依赖 TVBox 的
 * 初始化时序（Hawk 要等 App.onCreate 起来），而这几项在页构造时就得读得到。
 */
final class DeskPrefs {

    private static final String PREFS = "desk";

    /** 切到别的版块或退到后台时，音视频是否继续放。默认停 */
    private static final String KEY_KEEP_PLAYING = "keep_playing";
    private static final String KEY_MUSIC_PROXY = "music_proxy";
    private static final String KEY_SHOW_CLOCK = "show_clock";
    private static final String KEY_SHOW_WEATHER = "show_weather";
    private static final String KEY_UPDATE_URL = "update_manifest_url";
    private static final String KEY_HOME_GUARD = "home_guard";

    /** 音乐播放模式：0 顺序 / 1 随机 / 2 单首循环 */
    private static final String KEY_MUSIC_MODE = "music_play_mode";

    /** B 站默认清晰度（qn，80=1080P）。实际给不给得到由服务端决定 */
    private static final String KEY_BILI_QUALITY = "bili_quality";
    /** B 站播放倍速 */
    private static final String KEY_BILI_SPEED = "bili_speed";
    /** 画面比例：0 适应 / 1 铺满 / 2 裁剪 */
    private static final String KEY_BILI_RESIZE = "bili_resize";
    private static final String KEY_BILI_DANMAKU = "bili_danmaku";
    private static final String KEY_BILI_SPONSOR = "bili_sponsor";
    /** 0 播完暂停 / 1 顺序播放 / 2 单个循环 / 3 列表循环 */
    private static final String KEY_BILI_PLAY_MODE = "bili_play_mode";
    /** 旧版的「自动连播」开关，只在没写过 {@link #KEY_BILI_PLAY_MODE} 时用来迁移 */
    private static final String KEY_BILI_AUTO_PLAY = "bili_auto_play";

    private static final String KEY_BILI_DANMAKU_SCALE = "bili_danmaku_scale";
    /** 弹幕显示区域：滚动弹幕的行数，3 少 / 5 适中 / 7 多 / 10 满屏 */
    private static final String KEY_BILI_DANMAKU_AREA = "bili_danmaku_area";
    private static final String KEY_BILI_DANMAKU_SPEED = "bili_danmaku_speed";
    private static final String KEY_BILI_DANMAKU_ALPHA = "bili_danmaku_alpha";

    private DeskPrefs() {
    }

    private static SharedPreferences of(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 主动打开原桌面后的放行截止时间（毫秒时间戳）。
     *
     * <p>用户从应用页里点开固件桌面、或者从「我的应用」进固件桌面去找东西时，
     * 守护不能把这当成「HOME 被抢了」立刻拉回来 —— 那会让人根本用不了原桌面。
     * 有效期内 HOME 键和窗口事件都放行，TVDesk 自己回到前台时立刻作废。
     */
    private static final String KEY_STOCK_ALLOW_UNTIL = "stock_allow_until";

    /** 现在是不是「用户主动去了原桌面」的放行期 */
    static boolean stockAllowed(Context context) {
        return of(context).getLong(KEY_STOCK_ALLOW_UNTIL, 0L) > System.currentTimeMillis();
    }

    /** 记一段放行期（用户刚打开了原桌面） */
    static void allowStockLauncher(Context context, long durationMs) {
        of(context).edit()
                .putLong(KEY_STOCK_ALLOW_UNTIL, System.currentTimeMillis() + Math.max(0L, durationMs))
                .apply();
    }

    /** 回到自己了：放行作废 */
    static void clearStockAllow(Context context) {
        of(context).edit().remove(KEY_STOCK_ALLOW_UNTIL).apply();
    }

    /** 用户点过「忽略此版本」的 versionCode：比它不大于的版本不再弹窗 */
    private static final String KEY_IGNORED_UPDATE = "ignored_update_code";
    /** 上一次静默检查更新的时间（毫秒） */
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check_at";

    static int ignoredUpdateCode(Context context) {
        return of(context).getInt(KEY_IGNORED_UPDATE, 0);
    }

    static void setIgnoredUpdateCode(Context context, int code) {
        of(context).edit().putInt(KEY_IGNORED_UPDATE, code).apply();
    }

    static long lastUpdateCheckAt(Context context) {
        return of(context).getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    static void setLastUpdateCheckAt(Context context, long at) {
        of(context).edit().putLong(KEY_LAST_UPDATE_CHECK, at).apply();
    }

    public static boolean keepPlayingOnLeave(Context context) {
        return of(context).getBoolean(KEY_KEEP_PLAYING, false);
    }

    public static void setKeepPlayingOnLeave(Context context, boolean keep) {
        of(context).edit().putBoolean(KEY_KEEP_PLAYING, keep).apply();
    }

    /**
     * 网易云代理地址。
     *
     * <p>直连不上（地区限制、接口变更）时，用户可以指一个自建代理；
     * 换源链路的最后一步「代理兜底」就走这里。本机端口这类地址不会被写进播放链接缓存，
     * 端口一变旧地址就废了。
     */
    public static String musicProxy(Context context) {
        return of(context).getString(KEY_MUSIC_PROXY, "");
    }

    public static void setMusicProxy(Context context, String proxy) {
        of(context).edit().putString(KEY_MUSIC_PROXY, proxy == null ? "" : proxy.trim()).apply();
    }

    /** 桌面右上角要不要显示时间 */
    public static boolean showClock(Context context) {
        return of(context).getBoolean(KEY_SHOW_CLOCK, true);
    }

    public static void setShowClock(Context context, boolean show) {
        of(context).edit().putBoolean(KEY_SHOW_CLOCK, show).apply();
    }

    /**
     * 无障碍接管 HOME 的开关。
     *
     * <p>默认开：用户装了 TVDesk 又去系统里开了无障碍，通常就是要它接管。
     * 关掉之后服务还在跑，但不再往回拉——想临时用回系统桌面时不用去关无障碍权限。
     */
    public static boolean homeGuard(Context context) {
        return of(context).getBoolean(KEY_HOME_GUARD, true);
    }

    public static void setHomeGuard(Context context, boolean on) {
        of(context).edit().putBoolean(KEY_HOME_GUARD, on).apply();
    }

    /**
     * 在线更新的清单地址。
     *
     * <p>留空表示不检查。填 JSON 或纯文本都行，格式见 {@code UpdateClient}。
     */
    public static String updateManifestUrl(Context context) {
        return of(context).getString(KEY_UPDATE_URL, "");
    }

    public static void setUpdateManifestUrl(Context context, String url) {
        of(context).edit().putString(KEY_UPDATE_URL, url == null ? "" : url.trim()).apply();
    }

    /** 桌面右上角要不要显示天气 */
    public static boolean showWeather(Context context) {
        return of(context).getBoolean(KEY_SHOW_WEATHER, true);
    }

    public static void setShowWeather(Context context, boolean show) {
        of(context).edit().putBoolean(KEY_SHOW_WEATHER, show).apply();
    }

    /** 播放模式存这里而不是只记在页面上：这一版没播，下次进音乐页还是该用上次那个模式 */
    public static int musicPlayMode(Context context) {
        int mode = of(context).getInt(KEY_MUSIC_MODE, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    public static void setMusicPlayMode(Context context, int mode) {
        of(context).edit().putInt(KEY_MUSIC_MODE, mode).apply();
    }

    // ---------------------------------------------------------------- 哔哩哔哩

    public static int biliQuality(Context context) {
        int qn = of(context).getInt(KEY_BILI_QUALITY, 80);
        return qn > 0 ? qn : 80;
    }

    public static void setBiliQuality(Context context, int qn) {
        if (qn > 0) {
            of(context).edit().putInt(KEY_BILI_QUALITY, qn).apply();
        }
    }

    public static float biliSpeed(Context context) {
        float speed = of(context).getFloat(KEY_BILI_SPEED, 1f);
        return speed >= 0.5f && speed <= 2f ? speed : 1f;
    }

    public static void setBiliSpeed(Context context, float speed) {
        if (speed >= 0.5f && speed <= 2f) {
            of(context).edit().putFloat(KEY_BILI_SPEED, speed).apply();
        }
    }

    public static int biliResize(Context context) {
        int mode = of(context).getInt(KEY_BILI_RESIZE, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    public static void setBiliResize(Context context, int mode) {
        if (mode >= 0 && mode <= 2) {
            of(context).edit().putInt(KEY_BILI_RESIZE, mode).apply();
        }
    }

    public static boolean biliDanmaku(Context context) {
        return of(context).getBoolean(KEY_BILI_DANMAKU, true);
    }

    public static void setBiliDanmaku(Context context, boolean on) {
        of(context).edit().putBoolean(KEY_BILI_DANMAKU, on).apply();
    }

    /**
     * 空降助手默认开：它是这次明确要的能力，藏起来默认关掉等于没做。
     * 跳过时会在画面上弹一句「已跳过哪个片段」，不会悄悄改播放位置。
     */
    public static boolean biliSponsorBlock(Context context) {
        return of(context).getBoolean(KEY_BILI_SPONSOR, true);
    }

    public static void setBiliSponsorBlock(Context context, boolean on) {
        of(context).edit().putBoolean(KEY_BILI_SPONSOR, on).apply();
    }

    /**
     * 连播模式。没写过新键时按旧开关迁移：原来开自动连播的人默认「顺序播放」，
     * 关掉的人默认「播完暂停」。
     */
    public static int biliPlayMode(Context context) {
        SharedPreferences prefs = of(context);
        if (prefs.contains(KEY_BILI_PLAY_MODE)) {
            int mode = prefs.getInt(KEY_BILI_PLAY_MODE, 1);
            return mode >= 0 && mode <= 3 ? mode : 1;
        }
        return prefs.getBoolean(KEY_BILI_AUTO_PLAY, true) ? 1 : 0;
    }

    public static void setBiliPlayMode(Context context, int mode) {
        if (mode >= 0 && mode <= 3) {
            of(context).edit().putInt(KEY_BILI_PLAY_MODE, mode).apply();
        }
    }

    public static float biliDanmakuScale(Context context) {
        float scale = of(context).getFloat(KEY_BILI_DANMAKU_SCALE, 0.85f);
        return scale >= 0.5f && scale <= 1.6f ? scale : 0.85f;
    }

    public static void setBiliDanmakuScale(Context context, float scale) {
        if (scale >= 0.5f && scale <= 1.6f) {
            of(context).edit().putFloat(KEY_BILI_DANMAKU_SCALE, scale).apply();
        }
    }

    public static int biliDanmakuArea(Context context) {
        int lines = of(context).getInt(KEY_BILI_DANMAKU_AREA, 5);
        return lines >= 2 && lines <= 12 ? lines : 5;
    }

    public static void setBiliDanmakuArea(Context context, int lines) {
        if (lines >= 2 && lines <= 12) {
            of(context).edit().putInt(KEY_BILI_DANMAKU_AREA, lines).apply();
        }
    }

    public static float biliDanmakuSpeed(Context context) {
        float speed = of(context).getFloat(KEY_BILI_DANMAKU_SPEED, 1.1f);
        return speed >= 0.5f && speed <= 2f ? speed : 1.1f;
    }

    public static void setBiliDanmakuSpeed(Context context, float speed) {
        if (speed >= 0.5f && speed <= 2f) {
            of(context).edit().putFloat(KEY_BILI_DANMAKU_SPEED, speed).apply();
        }
    }

    public static float biliDanmakuAlpha(Context context) {
        float alpha = of(context).getFloat(KEY_BILI_DANMAKU_ALPHA, 0.9f);
        return alpha >= 0.3f && alpha <= 1f ? alpha : 0.9f;
    }

    public static void setBiliDanmakuAlpha(Context context, float alpha) {
        if (alpha >= 0.3f && alpha <= 1f) {
            of(context).edit().putFloat(KEY_BILI_DANMAKU_ALPHA, alpha).apply();
        }
    }
}

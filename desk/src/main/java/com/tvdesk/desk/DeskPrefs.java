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

    public static boolean keepPlayingOnLeave(Context context) {
        return of(context).getBoolean(KEY_KEEP_PLAYING, false);
    }

    public static void setKeepPlayingOnLeave(Context context, boolean keep) {
        of(context).edit().putBoolean(KEY_KEEP_PLAYING, keep).apply();
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

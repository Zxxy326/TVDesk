package com.tvdesk.core.feed;

import android.content.Context;
import android.text.TextUtils;

import com.github.tvbox.osc.util.HistoryHelper;
import com.orhanobut.hawk.Hawk;

/**
 * 随 APK 内嵌的内容源。
 *
 * <p>配置和爬虫 jar 放在 {@code sources/}，地址用 {@code asset://} 前缀
 * （见 {@code ApiConfig} 里对 asset 的分支）。装完就有源可用。
 *
 * <p><b>只有 debug 带源</b>：这两份配置和 jar 是第三方作者的，开发期拿来试功能，
 * 不该跟着发行包发给别人。所以文件放在 {@code app/src/debug/assets/sources/}，
 * 发行版里根本没有这个目录 —— {@link #seedIfNeeded} 探到没有就直接跳过，
 * 桌面回到「未配置内容源」的状态，由用户在信号源里自己填。
 *
 * <p>只在第一次启动时写一次 History/Hawk：用户之后清空或换源都算他自己的选择，
 * 下次启动不会再被塞回来。升级上来的老用户不会被改掉当前源，但内置源会补进历史，
 * 在「信号源」里能直接切。
 */
public final class BuiltinSources {

    public static final String WANGERXIAO = "asset://sources/wangerxiao.json";
    public static final String CAINISI = "asset://sources/cainisi.json";

    private static final String SEED_KEY = "tvdesk_builtin_sources_seeded_v1";
    private static final String ASSET_DIR = "sources";

    private BuiltinSources() {
    }

    /** 内置源在界面上的名字；不是内置源返回空串 */
    public static String displayName(String url) {
        if (WANGERXIAO.equals(url)) {
            return "内置 · 王二小";
        }
        if (CAINISI.equals(url)) {
            return "内置 · 菜妮丝";
        }
        return "";
    }

    public static void seedIfNeeded(Context context) {
        try {
            if (Hawk.get(SEED_KEY, false)) {
                return;
            }
            if (!bundled(context)) {
                // 发行版：包里没带源，落个 marker 免得每次启动再探一遍
                Hawk.put(SEED_KEY, true);
                return;
            }
            // 菜妮丝先写、王二小后写，历史是「后写排前面」，于是默认源在最上面
            HistoryHelper.setApiHistory(CAINISI);
            HistoryHelper.setApiHistory(WANGERXIAO);
            if (TextUtils.isEmpty(SourceConfig.current())) {
                SourceConfig.apply(WANGERXIAO);
            }
            Hawk.put(SEED_KEY, true);
        } catch (Throwable th) {
            android.util.Log.w("BuiltinSources", "内置源写入失败", th);
        }
    }

    /** assets 里到底有没有这一批源。发行版没有，别拿不存在的地址去写配置 */
    private static boolean bundled(Context context) {
        try {
            String[] names = context.getAssets().list(ASSET_DIR);
            if (names == null) {
                return false;
            }
            for (String name : names) {
                if (name.endsWith(".json")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}

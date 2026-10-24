package com.tvdesk.core.bridge;

import com.google.gson.JsonObject;

/**
 * 播放信息桥。
 *
 * core 的本地 HTTP 服务需要把「当前正在播什么」暴露给远端控制页，
 * 但播放详情只有 UI 层（DetailActivity / PlayFragment）知道。
 * 所以这里留一个 Provider，由 app 注册实现。
 */
public final class MediaBridge {

    public interface Provider {
        /** 在主线程调用 */
        JsonObject getMediaInfo();
    }

    private static Provider provider;

    private MediaBridge() {
    }

    public static void install(Provider p) {
        provider = p;
    }

    public static JsonObject get() {
        if (provider == null) return new JsonObject();
        try {
            JsonObject obj = provider.getMediaInfo();
            return obj == null ? new JsonObject() : obj;
        } catch (Throwable th) {
            return new JsonObject();
        }
    }
}

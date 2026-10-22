package com.tvdesk.core.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

/**
 * core → app 的导航桥。
 *
 * core 层不允许直接引用 Activity / BroadcastReceiver 类（否则形成 core → app 反向依赖），
 * 因此把「发广播触发搜索 / 推送」这类动作抽象成桥，由 app 在启动时注入具体实现。
 *
 * 注意：广播 action 字符串必须与 app 侧 Receiver 里声明的保持一致，
 * 所以这里把常量收敛到一处，app 的 Receiver 直接引用。
 */
public final class NavBridge {

    public static final String ACTION_SEARCH = "android.content.movie.search.Action";
    public static final String ACTION_PUSH = "android.content.movie.push.Action";

    private static Class<?> searchReceiver;
    private static Class<?> pushReceiver;

    private NavBridge() {
    }

    /** app 启动时调用，注入具体的 Receiver 实现 */
    public static void install(Class<?> search, Class<?> push) {
        searchReceiver = search;
        pushReceiver = push;
    }

    public static void search(Context ctx, String title) {
        if (ctx == null || searchReceiver == null) return;
        Intent intent = new Intent();
        intent.setAction(ACTION_SEARCH);
        intent.setPackage(ctx.getPackageName());
        intent.setComponent(new ComponentName(ctx, searchReceiver));
        intent.putExtra("title", title);
        ctx.sendBroadcast(intent);
    }

    public static void push(Context ctx, String url) {
        if (ctx == null || pushReceiver == null || TextUtils.isEmpty(url)) return;
        Intent intent = new Intent();
        intent.setAction(ACTION_PUSH);
        intent.setPackage(ctx.getPackageName());
        intent.setComponent(new ComponentName(ctx, pushReceiver));
        intent.putExtra("url", url);
        ctx.sendBroadcast(intent);
    }
}

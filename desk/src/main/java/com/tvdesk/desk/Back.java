package com.tvdesk.desk;

import android.util.Log;

/**
 * 返回键的层级记账。每个「收下返回键」的分支都从这里走一次，
 * 于是 logcat 里能看到一条完整的退层轨迹：
 *
 * <pre>
 * BackKey: 返回｜哔哩哔哩·收起胶囊菜单
 * BackKey: 返回｜宿主·退出内容页「稿件详情」
 * BackKey: 返回｜宿主·退出模块「哔哩哔哩」
 * </pre>
 *
 * <p>为什么要打这条日志：返回键的问题从来不是「按了没反应」，而是
 * 「按下去退了几层、焦点落回哪儿」。电视上没有鼠标可以点一下试试，
 * 出一次问题就要接遥控器复现。有这一行，换一台电视、改一个页面之后
 * 用 {@code adb logcat -s BackKey} 一按就知道层级对不对。
 *
 * <p>层级顺序的唯一口径在 {@link ModulePage} 的类注释里，这里的名字照那套写。
 */
public final class Back {

    static final String TAG = "BackKey";

    private Back() {
    }

    /** 这一层收下返回键（收起浮层、退出子视图）。返回 true 交给调用方直接 return */
    static boolean consume(String layer) {
        Log.i(TAG, "返回｜" + layer);
        return true;
    }

    /** 这一层没有可退的东西，交回上一层 */
    static boolean pass(String layer) {
        Log.d(TAG, "返回｜" + layer + " → 交回上层");
        return false;
    }
}

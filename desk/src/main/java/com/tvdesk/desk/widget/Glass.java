package com.tvdesk.desk.widget;

import android.view.View;

/**
 * 玻璃底板的统一入口。
 *
 * <p>移植自 Aerial TV 的 LiquidGlass：面板显示它背后那块磨砂背景 + 受光的边。
 * 桌面那几颗胶囊在 {@code DeskActivity.applyDeskGlass()} 里换过，模块页（音乐 / 抖音 /
 * B 站 / 应用 / 信号源）的顶栏和播放条也走这里，免得逐页各写一遍半径。
 *
 * <p>用法就一句：在页面 setup 完成、控件都拿到之后调 {@code Glass.pill(view)}。
 * 背景图先由 {@link FrostedBackdrop#capture(View)} 拍一次（各页拍自己的背景层更准）。
 */
public final class Glass {

    private Glass() {
    }

    /** 胶囊（圆角 = radius_control，全圆） */
    public static void pill(View view) {
        apply(view, com.tvdesk.desk.R.dimen.radius_control, true);
    }

    /** 面板（圆角 = radius_panel） */
    public static void panel(View view) {
        apply(view, com.tvdesk.desk.R.dimen.radius_panel, true);
    }

    /** 底部悬浮条：上圆下也圆，和胶囊同半径 */
    public static void bar(View view) {
        apply(view, com.tvdesk.desk.R.dimen.radius_control, true);
    }

    private static void apply(View view, int radiusRes, boolean roundBottom) {
        if (view == null) {
            return;
        }
        try {
            float radius = view.getResources().getDimension(radiusRes);
            view.setBackground(new GlassDrawable(radius, roundBottom));
        } catch (Throwable ignored) {
            // 换不上就保留原背景，别因此崩页面
        }
    }

    /** 拍一张磨砂背景（页面背景层铺好之后调） */
    public static void capture(View background) {
        FrostedBackdrop.capture(background);
    }
}

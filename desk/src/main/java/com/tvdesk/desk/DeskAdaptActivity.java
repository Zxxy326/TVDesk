package com.tvdesk.desk;

import android.content.res.Resources;
import android.os.Looper;
import android.util.Log;

import androidx.fragment.app.FragmentActivity;

import me.jessyan.autosize.AutoSizeCompat;

/**
 * 桌面系页面的公共基类：把「密度适配」变成继承就自动拿到的东西。
 *
 * <h3>为什么必须有个基类</h3>
 * 桌面的 dp 尺寸都按 1280×720dp 的设计稿写（设计画布由 AndroidManifest 的
 * {@code design_width_in_dp=1280} 声明一处）。要把 1280dp 铺满 1920px，就得把
 * Resources 上的 density 从设备真实的 2.0 改成 1.5。
 *
 * <p>而**不做这件事不会有任何报错**——只是所有 dp 都放大 1/3。上一轮实测过：
 * 海报卡从设计的 240×360px 撑到 320×480px，卡片底跑到 1371（屏高 1080），
 * 整行海报被屏幕底部切掉 60%（实测）。
 * 一个「静默失败、症状又离得很远」的坑，靠每个新页面各自记得写一遍 getResources() 是防不住的，
 * 所以放在基类里。
 *
 * <h3>为什么是 getResources() 而不是 onCreate</h3>
 * 布局是在 {@code setContentView} 里 inflate 的，inflate 过程中会反复回调
 * {@code getResources()} 并同步读 dp（LayoutParams、dimen 资源、以及各页面的 dp() 换算）。
 * 密度必须在**第一次读 dp 之前**就是对的，晚一步算出来的尺寸就固化了——写进 View 的
 * 像素值不会因为后面密度变了而重算。
 *
 * <h3>为什么只在主线程转换</h3>
 * AutoSize 改的是 Resources 上的全局 density，从子线程改会和主线程的布局打架。
 * 这跟 TVBox 原版 {@code BaseActivity} 的做法一致。
 *
 * <h3>为什么是 FragmentActivity</h3>
 * 播放页要复用 TVBox 的 {@code PlayFragment}（它继承 {@code androidx.fragment.app.Fragment}），
 * 承载它的窗口必须是 FragmentActivity。用 Fragment 而不是把播放器重写一遍，是因为
 * 解析、代理、字幕、弹幕、进度记忆都在那个 Fragment 里，重写等于再造一个播放器。
 */
public abstract class DeskAdaptActivity extends FragmentActivity {

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfGlobal(super.getResources());
            logAdaptOnce();
        }
        return super.getResources();
    }

    /** 日志每个进程只报一次，避免每帧滚动都刷屏 */
    private boolean adaptLogged;

    /**
     * 密度适配只在每个进程里报一次。
     *
     * <p>这是换一台电视时最快的判断手段：屏幕上尺寸不对，先看这一行。
     * {@code dp 画布} 不是 1280×720 就说明适配没生效，不用再去量海报。
     */
    private void logAdaptOnce() {
        if (adaptLogged) {
            return;
        }
        adaptLogged = true;
        Resources res = super.getResources();
        String probe = adaptProbe(res);
        Log.i(getClass().getSimpleName(), "密度适配：density=" + res.getDisplayMetrics().density
                + " 屏宽=" + res.getDisplayMetrics().widthPixels + "px"
                + " dp 画布=" + Math.round(res.getDisplayMetrics().widthPixels / res.getDisplayMetrics().density)
                + "x" + Math.round(res.getDisplayMetrics().heightPixels / res.getDisplayMetrics().density) + "dp"
                + (probe == null ? "" : " | " + probe));
    }

    /**
     * 子类给一个「本页的关键尺寸」字符串，用来做跨电视的尺寸比对。
     *
     * <p>光看 density 只能发现「适配没生效」，看不出「生效了但布局还是不对」。
     * 各页面挑一个能代表该页设计稿的尺寸报出来，换电视时一比就知道差在哪。
     *
     * @param res 已经适配过的 Resources。<b>必须用它</b>去读尺寸，不要去调
     *            {@code getResources()}——本方法是在那个方法内部被调用的，
     *            绕回去会再触发一次适配（虽然靠 adaptLogged 挡住了死循环，但没必要）。
     * @return 形如 {@code "poster_width 解析为 240px"}；返回 null 表示不报
     */
    protected String adaptProbe(Resources res) {
        return null;
    }
}

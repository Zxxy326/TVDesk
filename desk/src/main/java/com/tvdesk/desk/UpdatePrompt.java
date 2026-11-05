package com.tvdesk.desk;

import com.tvdesk.core.ui.DeskToast;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.tvdesk.desk.update.UpdateClient;

import java.io.File;

/**
 * 后台静默检查更新 + 有新版时弹窗。
 *
 * <h3>为什么要在后台查</h3>
 * 电视上没有「关于 → 检查更新」这种肌肉记忆，用户不会主动去设置里点。所以每次回到桌面
 * 时顺手查一次（{@link #CHECK_INTERVAL_MS} 内只查一次），有新版再弹；查不到、连不上、
 * 没新版都<b>什么都不做</b> —— 静默的意义就在这。
 *
 * <h3>国内网络</h3>
 * 清单和安装包都走 {@link UpdateClient} 里的镜像列表，没挂代理的电视也查得到、下得下来。
 *
 * <h3>忽略</h3>
 * 「忽略此版本」记的是 versionCode：下次出更高的版本还会弹，同一个版本不再骚扰。
 */
final class UpdatePrompt {

    private static final String TAG = "DeskUpdatePrompt";

    /** 两次自动检查之间至少隔这么久 */
    private static final long CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    /** 正在查：别因为 Activity 反复 resume 起一堆线程 */
    private static boolean checking;

    private UpdatePrompt() {
    }

    /**
     * 回到桌面时调一次：到点了就后台查一眼，有新版弹窗。
     *
     * <p>所有异常都吃掉 —— 更新功能出问题不该影响桌面本身。
     */
    static void checkInBackground(final Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - DeskPrefs.lastUpdateCheckAt(activity) < CHECK_INTERVAL_MS) {
            return;
        }
        if (checking) {
            return;
        }
        checking = true;
        // 先记时间：查不到也算「查过了」，否则每次开电视都要等一轮超时
        DeskPrefs.setLastUpdateCheckAt(activity, now);
        final android.content.Context app = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateClient.Info info = UpdateClient.checkDefault(app);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        if (activity.isFinishing()) {
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                                && activity.isDestroyed()) {
                            return;
                        }
                        if (info == null || !info.newer) {
                            return;
                        }
                        if (info.versionCode <= DeskPrefs.ignoredUpdateCode(activity)) {
                            return;
                        }
                        Log.i(TAG, "发现新版本 " + info.versionName + "（" + info.versionCode + "）");
                        show(activity, info);
                    }
                });
            }
        }, "desk-update-check").start();
    }

    /** 手动检查（设置页）也走这里 */
    static void show(final Activity activity, final UpdateClient.Info info) {
        if (activity == null || activity.isFinishing() || info == null) {
            return;
        }
        String current = UpdateClient.currentVersionName(activity);
        // 弹窗要传进自己的监听器里，先拿个盒子装着（匿名类里不能引用正在初始化的变量）
        final UpdateDialog[] box = new UpdateDialog[1];
        final UpdateDialog dialog = new UpdateDialog(activity, info.versionName, current, info.notes,
                new UpdateDialog.Listener() {
                    @Override
                    public void onDownload() {
                        download(activity, info, box[0]);
                    }

                    @Override
                    public void onIgnore() {
                        DeskPrefs.setIgnoredUpdateCode(activity, info.versionCode);
                        toast(activity, "好的，这个版本不再提醒");
                    }

                    @Override
                    public void onLater() {
                        // 什么都不记：下次回桌面还会再问一次
                    }
                });
        box[0] = dialog;
        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attrs = window.getAttributes();
                attrs.dimAmount = 0.62f;
                window.setAttributes(attrs);
                window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
            }
        } catch (Throwable th) {
            Log.w(TAG, "弹窗失败：" + th.getMessage());
        }
    }

    private static void download(final Activity activity, final UpdateClient.Info info,
                                 final UpdateDialog dialog) {
        dialog.setDownloading(true);
        final android.content.Context app = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File apk = UpdateClient.download(app, info, new UpdateClient.Progress() {
                    @Override
                    public void onProgress(final int percent) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                dialog.setProgress(percent);
                            }
                        });
                    }
                });
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (apk == null) {
                            dialog.showError(UpdateClient.lastError());
                            return;
                        }
                        if (UpdateClient.install(activity, apk)) {
                            // 关掉自己，别挡着系统的安装界面
                            dialog.closeAfterInstall();
                        } else {
                            dialog.showError("安装包下好了，但打不开安装界面");
                        }
                    }
                });
            }
        }, "desk-update-download").start();
    }

    private static void toast(Activity activity, String text) {
        try {
            DeskToast.makeText(activity, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}

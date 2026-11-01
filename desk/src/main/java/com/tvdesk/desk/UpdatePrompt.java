package com.tvdesk.desk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.tvdesk.desk.update.UpdateClient;

import java.io.File;

/**
 * 后台静默检查更新 + 有新版时弹窗。
 *
 * <h3>为什么要在后台查</h3>
 * 电视上没有「关于手机 → 检查更新」这种肌肉记忆，用户不会主动去设置里点。
 * 所以每次回到桌面时顺手查一次（{@link #CHECK_INTERVAL_MS} 内只查一次），
 * 有新版再弹；查不到、连不上、没新版都<b>什么都不做</b> —— 静默的意义就在这。
 *
 * <h3>国内网络</h3>
 * 清单和安装包都走 {@link UpdateClient} 里的镜像列表（jsDelivr / gitmirror / 加速站），
 * 没挂代理的电视也能查得到、下得下来。
 *
 * <h3>忽略</h3>
 * 「忽略此版本」记的是 versionCode：下次出更高的版本还会弹，同一个版本不再骚扰。
 * 用户想手动看，走设置里的「检查更新」。
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
                            // 用户说过「这个版本别再问」
                            return;
                        }
                        Log.i(TAG, "发现新版本 " + info.versionName + "（" + info.versionCode + "）");
                        show(activity, info);
                    }
                });
            }
        }, "desk-update-check").start();
    }

    /** 手动检查（设置页）也可以复用这个弹窗 */
    static void show(final Activity activity, final UpdateClient.Info info) {
        String mine = UpdateClient.currentVersionName(activity);
        String notes = info.notes == null || info.notes.trim().isEmpty()
                ? "（这次没写更新说明）" : info.notes.trim();
        try {
            new AlertDialog.Builder(activity, R.style.DeskDialogTheme)
                    .setTitle("发现新版本 " + info.versionName)
                    .setMessage("当前版本 " + mine + "\n\n" + notes)
                    .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            download(activity, info);
                        }
                    })
                    .setNeutralButton("忽略此版本", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            DeskPrefs.setIgnoredUpdateCode(activity, info.versionCode);
                            toast(activity, "好的，这个版本不再提醒");
                        }
                    })
                    .setNegativeButton("以后再说", null)
                    .show();
        } catch (Throwable th) {
            Log.w(TAG, "弹窗失败：" + th.getMessage());
        }
    }

    private static void download(final Activity activity, final UpdateClient.Info info) {
        final AlertDialog progress;
        try {
            progress = new AlertDialog.Builder(activity, R.style.DeskDialogTheme)
                    .setTitle("正在下载 " + info.versionName)
                    .setMessage("0%")
                    .setCancelable(false)
                    .show();
        } catch (Throwable th) {
            return;
        }
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
                                try {
                                    progress.setMessage(percent + "%");
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    }
                });
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            progress.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (apk == null) {
                            toast(activity, "下载失败：" + UpdateClient.lastError());
                            return;
                        }
                        if (!UpdateClient.install(activity, apk)) {
                            toast(activity, "安装包下好了，但打不开安装界面");
                        }
                    }
                });
            }
        }, "desk-update-download").start();
    }

    private static void toast(Activity activity, String text) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}

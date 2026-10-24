package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * 弹幕显示设置。
 *
 * <p>从主设置里的「弹幕显示」进。单独一张而不是继续往主弹窗里塞行：
 * 电视一屏就那么高，主弹窗已经有七八行，再加四行会把「完成」顶出屏幕。
 *
 * <p>和主设置一样，每行确认键循环取值、改完立刻回调；弹幕正在播时能直接看到
 * 字号和区域变化，不用退出去试。
 */
public final class BiliDanmakuDialog extends Dialog {

    public interface Listener {
        void onScale(float scale);

        void onArea(int lines);

        void onSpeed(float speed);

        void onAlpha(float alpha);
    }

    private static final float[] SCALES = {0.7f, 0.85f, 1.0f, 1.2f};
    private static final String[] SCALE_NAMES = {"小", "标准", "大", "特大"};
    private static final int[] AREAS = {3, 5, 7, 10};
    private static final String[] AREA_NAMES = {"少（3 行）", "适中（5 行）", "多（7 行）", "满屏（10 行）"};
    private static final float[] SPEEDS = {0.8f, 1.1f, 1.4f, 1.8f};
    private static final String[] SPEED_NAMES = {"慢", "标准", "快", "很快"};
    private static final float[] ALPHAS = {0.6f, 0.75f, 0.9f, 1f};
    private static final String[] ALPHA_NAMES = {"半透明", "偏淡", "标准", "不透明"};

    private final Listener listener;
    private int scaleIndex;
    private int areaIndex;
    private int speedIndex;
    private int alphaIndex;

    private LinearLayout rows;
    private SettingsRow scaleRow;
    private SettingsRow areaRow;
    private SettingsRow speedRow;
    private SettingsRow alphaRow;

    public BiliDanmakuDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
        Context context = activity;
        scaleIndex = indexOf(SCALES, DeskPrefs.biliDanmakuScale(context));
        areaIndex = indexOf(AREAS, DeskPrefs.biliDanmakuArea(context));
        speedIndex = indexOf(SPEEDS, DeskPrefs.biliDanmakuSpeed(context));
        alphaIndex = indexOf(ALPHAS, DeskPrefs.biliDanmakuAlpha(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_bili_danmaku);
        sizeWindow();
        rows = findViewById(R.id.bili_danmaku_rows);
        buildRows();
        findViewById(R.id.bili_danmaku_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        wireFocus();
        final View first = rows.getChildCount() > 0 ? rows.getChildAt(0) : null;
        (first != null ? first : findViewById(R.id.bili_danmaku_done)).post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    return;
                }
                View target = first != null ? first : findViewById(R.id.bili_danmaku_done);
                if (target != null) {
                    target.requestFocus();
                }
            }
        });
    }

    private void sizeWindow() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.6f;
        window.setAttributes(lp);
    }

    private void buildRows() {
        rows.removeAllViews();
        Context context = getContext();
        scaleRow = SettingsRow.add(rows, context, "弹幕字号", "同一屏能容下的字数随之变化",
                SCALE_NAMES[scaleIndex], SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        scaleIndex = (scaleIndex + 1) % SCALES.length;
                        scaleRow.setValue(SCALE_NAMES[scaleIndex]);
                        if (listener != null) {
                            listener.onScale(SCALES[scaleIndex]);
                        }
                    }
                });
        areaRow = SettingsRow.add(rows, context, "显示区域", "行数越少越不挡画面",
                AREA_NAMES[areaIndex], SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        areaIndex = (areaIndex + 1) % AREAS.length;
                        areaRow.setValue(AREA_NAMES[areaIndex]);
                        if (listener != null) {
                            listener.onArea(AREAS[areaIndex]);
                        }
                    }
                });
        speedRow = SettingsRow.add(rows, context, "滚动速度", "标准跟着视频节奏，调快适合弹幕密集的视频",
                SPEED_NAMES[speedIndex], SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        speedIndex = (speedIndex + 1) % SPEEDS.length;
                        speedRow.setValue(SPEED_NAMES[speedIndex]);
                        if (listener != null) {
                            listener.onSpeed(SPEEDS[speedIndex]);
                        }
                    }
                });
        alphaRow = SettingsRow.add(rows, context, "不透明度", "调淡一点，弹幕就不会压在画面上",
                ALPHA_NAMES[alphaIndex], SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        alphaIndex = (alphaIndex + 1) % ALPHAS.length;
                        alphaRow.setValue(ALPHA_NAMES[alphaIndex]);
                        if (listener != null) {
                            listener.onAlpha(ALPHAS[alphaIndex]);
                        }
                    }
                });
    }

    private void wireFocus() {
        if (rows.getChildCount() == 0) {
            return;
        }
        View last = rows.getChildAt(rows.getChildCount() - 1);
        last.setNextFocusDownId(R.id.bili_danmaku_done);
        findViewById(R.id.bili_danmaku_done).setNextFocusUpId(last.getId());
    }

    private static int indexOf(float[] values, float target) {
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float d = Math.abs(values[i] - target);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    private static int indexOf(int[] values, int target) {
        int best = 0;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int d = Math.abs(values[i] - target);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    /** 主设置那行要显示「字号 标准 · 区域 适中」这种摘要，名字表放这里统一 */
    public static String scaleName(Context context) {
        return SCALE_NAMES[indexOf(SCALES, DeskPrefs.biliDanmakuScale(context))];
    }

    public static String areaName(Context context) {
        return AREA_NAMES[indexOf(AREAS, DeskPrefs.biliDanmakuArea(context))];
    }
}

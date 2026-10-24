package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.tvdesk.core.bili.model.BiliStream;

import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩设置。
 *
 * <p>两处入口共用：B 站首页胶囊菜单里的「播放设置」，和播放器控制条上的「设置」。
 * 两边能改的是同一份 {@link DeskPrefs}，所以这里不做两套界面。
 *
 * <h3>为什么每行点一下就地变</h3>
 * 电视遥控器没有「下拉框」这种便宜的交互。一行代表一项，确认键循环取值，
 * 值就写在右边——不用弹二级菜单，也不用记当前选到哪。开关类同理。
 * 每一项改完立刻回调，没有「取消后恢复」的问题：设置本来就该是即时的。
 *
 * <h3>播放器和首页的差别</h3>
 * 播放器能拿到这次实际可用的清晰度列表，所以「清晰度」那一行是服务端真给了什么就列什么；
 * 首页只知道默认值，用 1080P/720P/480P/360P 这一组通用档位。
 * 直播没有清晰度、弹幕和空降，这几行直接不出现。
 */
public final class BiliSettingsDialog extends Dialog {

    public interface Listener {
        void onQuality(int qn);

        void onSpeed(float speed);

        /** 0 适应 / 1 铺满 / 2 裁剪 */
        void onResize(int mode);

        void onDanmaku(boolean on);

        void onSponsorBlock(boolean on);

        /** 0 播完暂停 / 1 顺序播放 / 2 单个循环 / 3 列表循环 */
        void onPlayMode(int mode);

        void onDanmakuScale(float scale);

        void onDanmakuArea(int lines);

        void onDanmakuSpeed(float speed);

        void onDanmakuAlpha(float alpha);
    }

    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String[] RESIZE_NAMES = {"适应画面", "拉伸铺满", "裁剪填满"};
    private static final int[] DEFAULT_QNS = {80, 64, 32, 16};

    private final List<BiliStream.Quality> qualities = new ArrayList<>();
    private final Listener listener;
    private final boolean live;
    // Dialog 带主题时 getContext() 包了一层 ContextThemeWrapper，不是 Activity；
    // 开二级弹窗要真正的 Activity 窗口，所以把构造时那颗存下来
    private final Activity activity;

    private int qualityIndex;
    private int speedIndex;
    private int resizeMode;
    private boolean danmakuOn;
    private boolean sponsorOn;
    private int playMode;

    private LinearLayout rows;
    private SettingsRow qualityRow;
    private SettingsRow speedRow;
    private SettingsRow resizeRow;
    private SettingsRow danmakuRow;
    private SettingsRow danmakuDetailRow;
    private SettingsRow sponsorRow;
    private SettingsRow playModeRow;

    private BiliDanmakuDialog danmakuDialog;

    public BiliSettingsDialog(Activity activity, List<BiliStream.Quality> sourceQualities,
                              int currentQn, boolean live, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.activity = activity;
        this.listener = listener;
        this.live = live;
        buildQualities(sourceQualities, currentQn);
        qualityIndex = indexOfQn(currentQn);
        speedIndex = indexOfSpeed(DeskPrefs.biliSpeed(activity));
        resizeMode = DeskPrefs.biliResize(activity);
        danmakuOn = DeskPrefs.biliDanmaku(activity);
        sponsorOn = DeskPrefs.biliSponsorBlock(activity);
        playMode = DeskPrefs.biliPlayMode(activity);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_bili_settings);
        sizeWindow();

        rows = findViewById(R.id.bili_settings_rows);

        buildRows();
        wireDone();
        wireFocus();

        final View first = rows.getChildCount() > 0 ? rows.getChildAt(0) : null;
        (first != null ? first : findViewById(R.id.bili_settings_done)).post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    return;
                }
                View target = first != null ? first : findViewById(R.id.bili_settings_done);
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

    /** 服务端列表可能是升序、也可能带 0；统一去重后按清晰度从高到低排 */
    private void buildQualities(List<BiliStream.Quality> source, int currentQn) {
        if (source != null) {
            for (BiliStream.Quality item : source) {
                if (item == null || item.qn <= 0) {
                    continue;
                }
                boolean duplicate = false;
                for (BiliStream.Quality already : qualities) {
                    if (already.qn == item.qn) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    BiliStream.Quality copy = new BiliStream.Quality();
                    copy.qn = item.qn;
                    copy.desc = TextUtils.isEmpty(item.desc)
                            ? BiliStream.nameOf(item.qn) : item.desc.trim();
                    qualities.add(copy);
                }
            }
        }
        if (qualities.isEmpty()) {
            for (int qn : DEFAULT_QNS) {
                BiliStream.Quality item = new BiliStream.Quality();
                item.qn = qn;
                item.desc = BiliStream.nameOf(qn);
                qualities.add(item);
            }
        }
        // 存档里的清晰度可能不在这一份列表里（换过登录状态、首页用默认档位），
        // 补进去，否则弹窗会把别档的名字显示成当前档
        boolean hasCurrent = false;
        for (BiliStream.Quality item : qualities) {
            if (item.qn == currentQn) {
                hasCurrent = true;
                break;
            }
        }
        if (!hasCurrent && currentQn > 0) {
            BiliStream.Quality item = new BiliStream.Quality();
            item.qn = currentQn;
            item.desc = BiliStream.nameOf(currentQn);
            qualities.add(item);
        }
        java.util.Collections.sort(qualities, new java.util.Comparator<BiliStream.Quality>() {
            @Override
            public int compare(BiliStream.Quality a, BiliStream.Quality b) {
                return Integer.compare(b.qn, a.qn);
            }
        });
    }

    private int indexOfQn(int qn) {
        for (int i = 0; i < qualities.size(); i++) {
            if (qualities.get(i).qn == qn) {
                return i;
            }
        }
        return 0;
    }

    private int indexOfSpeed(float speed) {
        int best = 1;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float d = Math.abs(SPEEDS[i] - speed);
            if (d < distance) {
                distance = d;
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 行

    private void buildRows() {
        rows.removeAllViews();
        Context context = getContext();
        if (!live) {
            qualityRow = SettingsRow.add(rows, context, "清晰度",
                    "只列这次取得到的档位，换档从现在的位置接着播", qualityLabel(),
                    SettingsRow.TEXT, new Runnable() {
                        @Override
                        public void run() {
                            qualityIndex = (qualityIndex + 1) % qualities.size();
                            qualityRow.setValue(qualityLabel());
                            if (listener != null) {
                                listener.onQuality(qualities.get(qualityIndex).qn);
                            }
                        }
                    });
        }

        speedRow = SettingsRow.add(rows, context, "播放倍速", "1x 是原速，确认键循环到下一档",
                speedLabel(), SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        speedIndex = (speedIndex + 1) % SPEEDS.length;
                        speedRow.setValue(speedLabel());
                        if (listener != null) {
                            listener.onSpeed(SPEEDS[speedIndex]);
                        }
                    }
                });

        if (!live) {
            playModeRow = SettingsRow.add(rows, context, "连播模式",
                    "非合集的视频播完按推荐列表往下接", BiliPlayerPage.MODE_NAMES[playMode],
                    SettingsRow.TEXT, new Runnable() {
                        @Override
                        public void run() {
                            playMode = (playMode + 1) % BiliPlayerPage.MODE_NAMES.length;
                            playModeRow.setValue(BiliPlayerPage.MODE_NAMES[playMode]);
                            if (listener != null) {
                                listener.onPlayMode(playMode);
                            }
                        }
                    });
        }

        resizeRow = SettingsRow.add(rows, context, "画面比例", "适应画面不裁边，铺满会拉伸",
                RESIZE_NAMES[resizeMode], SettingsRow.TEXT, new Runnable() {
                    @Override
                    public void run() {
                        resizeMode = (resizeMode + 1) % RESIZE_NAMES.length;
                        resizeRow.setValue(RESIZE_NAMES[resizeMode]);
                        if (listener != null) {
                            listener.onResize(resizeMode);
                        }
                    }
                });

        if (!live) {
            danmakuRow = SettingsRow.add(rows, context, "弹幕", "盖在画面上，跟着进度一起走",
                    "", SettingsRow.TOGGLE, new Runnable() {
                        @Override
                        public void run() {
                            danmakuOn = !danmakuOn;
                            danmakuRow.setOn(danmakuOn);
                            if (listener != null) {
                                listener.onDanmaku(danmakuOn);
                            }
                        }
                    });
            danmakuRow.setOn(danmakuOn);

            danmakuDetailRow = SettingsRow.add(rows, context, "弹幕显示", danmakuDetailLabel(),
                    null, SettingsRow.ENTRY, new Runnable() {
                        @Override
                        public void run() {
                            openDanmakuDialog();
                        }
                    });

            sponsorRow = SettingsRow.add(rows, context, "空降助手",
                    "跳过观众标出的恰饭、推广、三连提醒和片头片尾", "", SettingsRow.TOGGLE,
                    new Runnable() {
                        @Override
                        public void run() {
                            sponsorOn = !sponsorOn;
                            sponsorRow.setOn(sponsorOn);
                            if (listener != null) {
                                listener.onSponsorBlock(sponsorOn);
                            }
                        }
                    });
            sponsorRow.setOn(sponsorOn);
        }
    }

    /** 字号和区域是高频项，直接显示在主行上；速度和透明度进二级弹窗改 */
    private String danmakuDetailLabel() {
        return "字号 " + BiliDanmakuDialog.scaleName(getContext())
                + " · 区域 " + BiliDanmakuDialog.areaName(getContext());
    }

    private void openDanmakuDialog() {
        if (danmakuDialog != null) {
            return;
        }
        danmakuDialog = new BiliDanmakuDialog(activity,
                new BiliDanmakuDialog.Listener() {
                    @Override
                    public void onScale(float scale) {
                        if (listener != null) {
                            listener.onDanmakuScale(scale);
                        }
                    }

                    @Override
                    public void onArea(int lines) {
                        if (listener != null) {
                            listener.onDanmakuArea(lines);
                        }
                    }

                    @Override
                    public void onSpeed(float speed) {
                        if (listener != null) {
                            listener.onDanmakuSpeed(speed);
                        }
                    }

                    @Override
                    public void onAlpha(float alpha) {
                        if (listener != null) {
                            listener.onDanmakuAlpha(alpha);
                        }
                    }
                });
        danmakuDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                danmakuDialog = null;
                if (danmakuDetailRow != null) {
                    danmakuDetailRow.setNote(danmakuDetailLabel());
                    if (isShowing()) {
                        danmakuDetailRow.view.requestFocus();
                    }
                }
            }
        });
        danmakuDialog.show();
    }

    @Override
    public void dismiss() {
        BiliDanmakuDialog inner = danmakuDialog;
        danmakuDialog = null;
        if (inner != null && inner.isShowing()) {
            inner.dismiss();
        }
        super.dismiss();
    }

    private void wireDone() {
        findViewById(R.id.bili_settings_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private void wireFocus() {
        if (rows.getChildCount() == 0) {
            return;
        }
        View last = rows.getChildAt(rows.getChildCount() - 1);
        last.setNextFocusDownId(R.id.bili_settings_done);
        findViewById(R.id.bili_settings_done).setNextFocusUpId(last.getId());
    }

    private String qualityLabel() {
        if (qualities.isEmpty()) {
            return BiliStream.nameOf(80);
        }
        BiliStream.Quality item = qualities.get(qualityIndex % qualities.size());
        return TextUtils.isEmpty(item.desc) ? BiliStream.nameOf(item.qn) : item.desc;
    }

    private String speedLabel() {
        float speed = SPEEDS[speedIndex];
        return (speed == Math.round(speed) ? String.valueOf((int) speed) : String.valueOf(speed))
                + "x";
    }
}

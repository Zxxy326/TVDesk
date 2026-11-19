package com.tvdesk.desk;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tvdesk.desk.widget.GlassDrawable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 首页「菜单键」快捷面板 —— 照 Aerial TV 的 Control Center 做。
 *
 * <h3>参数来源</h3>
 * 不是照着截图估的，是从 Aerial 的源码里读出来的（本仓库 {@code _aerial/} 下）：
 * <ul>
 *   <li>面板：宽 300dp、圆角 24dp、内边距 16dp，距顶 24dp、距右 32dp
 *       （{@code ControlCenter.kt} 的 {@code Column(Modifier.width(300.dp).liquidGlass(24.dp))}）；</li>
 *   <li>玻璃：磨砂底 + 0.48 亮度 + 14 偏移的压暗矩阵，描边 1.2dp 四段渐变
 *       （{@code LiquidGlass.kt} 的 PanelTint / glassEdge）；</li>
 *   <li>遮罩：纯黑 28%（{@code background(Color.Black.copy(alpha = 0.28f))}）；</li>
 *   <li>入场：淡入 200ms + 从右上角缩放（0.9 → 1，280ms，pivot 在右上）；
 *       退场 160ms 缩到 0.95；</li>
 *   <li>圆按钮：64dp 宽（三颗两端对齐）、48dp 圆 + 20dp 图标 + 11sp 标签，
 *       聚焦底色 #F5F5F7、图标转 #1D1D1F，缩放 1.1；</li>
 *   <li>滑杆：高 40dp、圆角 14dp、左右内边距 14dp，18dp 图标 + 6dp 轨道 + 数值（最小宽 28dp），
 *       三段间距 12dp，轨道底为前景色 20% 透明度，聚焦底色同上，缩放 1.03。</li>
 * </ul>
 *
 * <p>和 Aerial 的差别只有内容：信号源 / 网络 / 设置 三个按钮对应我们自己的模块，
 * 音量、亮度走系统接口，最下面一行是桌面守护状态和版本号。
 *
 * <p><b>尺寸不是直接抄数字</b>：Aerial 的坐标空间是 960×540dp，我们的是 1280×720dp
 * （{@code design_width_in_dp}）。同一个 300dp 在两边占的屏宽比例差 4/3，所以源码里的数字
 * 原样留在常量里，取值一律过 {@link #sdp}／{@link #sp} 换算。换算之后，面板占屏宽的 31%、
 * 圆角 24dp→32dp、安全边距 24/32dp→32/43dp，和 Aerial 在同一台电视上看起来是同一个比例。
 */
public final class ControlCenterPanel extends FrameLayout {

    public interface Listener {
        void onOpenSource();

        void onOpenNetwork();

        /**
         * 快捷面板里改了「显示时钟 / 显示天气」这类设置。
         *
         * <p>这些开关平时是桌面在 onCreate/onResume 里读的，面板就在桌面上面，
         * 不给个回调的话用户得退出去再进来才看得到变化。
         */
        void onQuickSettingsChanged();

        /**
         * 面板收起了，而且没有可以还回去的焦点。
         *
         * <p>打开面板时如果桌面在「沉浸轮播」里，整页本来就没有焦点可记；
         * 收起后什么都不聚焦，遥控器要按一下才「活过来」——表现为「按了没反应」。
         */
        void onClosedWithoutFocus();
    }

    // Aerial 的 Palette（tvOS 深色）
    private static final int LABEL = 0xFFFFFFFF;
    private static final int SECONDARY = 0x99EBEBF5;
    private static final int TERTIARY = 0x4DEBEBF5;
    private static final int FILL = 0x2E787880;
    private static final int FOCUS = 0xFFF5F5F7;
    private static final int ON_FOCUS = 0xFF1D1D1F;
    private static final int GREEN = 0xFF30D158;
    private static final int ORANGE = 0xFFFF9F0A;

    /**
     * Aerial 的坐标是 {@code 960×540dp}，我们是 {@code 1280×720dp}（见 AndroidManifest 的
     * {@code design_width_in_dp}）。同一个 300dp，在它的画布上占 31%，在我们的画布上只占 23%
     * ——所以「照抄 aerial 的尺寸」必须把这 4/3 补上，下面所有数字都保持 Aerial 源码里的原值，
     * 换算交给 {@link #sdp}。这样以后对着 Aerial 再看一眼源码，还能一行行对得上。
     */
    private static final float SCALE = 4f / 3f;

    /**
     * 字号再收 2/3。
     *
     * <p>几何按 4/3 放大之后，字号照同样比例走会显得比 Aerial 大——Aerial 的主面板是
     * 一块「信息密度很低」的卡片，字一大会立刻变成土味弹窗。面板尺寸不动，只把字收小，
     * 视觉重心才回到 Aerial 那个样子。
     */
    private static final float FONT_SHRINK = 2f / 3f;

    /** 面板 300dp 宽、圆角 24dp、内边距 16dp（Aerial 的 {@code Column(width(300.dp).liquidGlass(24.dp).padding(16.dp))}） */
    private static final int WIDTH_DP = 300;
    private static final int RADIUS_DP = 24;
    private static final int PAD_DP = 16;
    /** 面板距顶 24dp、距右 32dp（Aerial 的 Modifier.padding(top = 24.dp, end = 32.dp)） */
    private static final int SAFE_TOP_DP = 24;
    private static final int SAFE_END_DP = 32;
    /** 圆按钮直径 48dp、按钮位 64dp、滑杆高 40dp / 圆角 14dp / 横向内边距 14dp */
    private static final int BUTTON_WIDTH_DP = 64;
    private static final int CIRCLE_DP = 48;
    private static final int BUTTON_ICON_DP = 20;
    private static final int ROW_HEIGHT_DP = 40;
    private static final int ROW_RADIUS_DP = 14;
    private static final int ROW_PAD_DP = 14;
    private static final int ROW_ICON_DP = 18;
    private static final int TRACK_DP = 6;
    /** 数值最小宽 28dp（Aerial 的 widthIn(min = 28.dp)） */
    private static final int VALUE_MIN_DP = 28;

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View scrim;
    private LinearLayout panel;
    private TextView clockView;
    private TextView dateView;
    private TextView netLabel;
    private ImageView netIcon;
    private TextView statusText;
    private TextView versionText;
    private View statusDot;
    private RoundButton sourceButton;
    private AudioSlider volumeRow;
    private BrightnessSlider brightnessRow;
    /** 设置用：从底部升起的开关面板（不跳出去，Aerial 的 sheet 路数） */
    private LinearLayout sheet;
    private boolean sheetShowing;
    private View lastFocusInPanel;
    private final java.util.List<ToggleRow> sheetRows = new java.util.ArrayList<>();

    private boolean showing;
    private View lastFocus;

    public ControlCenterPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setVisibility(GONE);
        setClickable(true);
        build();
    }

    /** 原样的 dp */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Aerial 的 dp（按 4/3 换算到我们的画布） */
    private int sdp(float aerialDp) {
        return dp(aerialDp * SCALE);
    }

    /** Aerial 的字号（sp 同样按 4/3 换算；两边画布只差缩放，字重／行高不用动） */
    private void sp(TextView view, float aerialSp) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, aerialSp * SCALE * FONT_SHRINK);
    }

    // ---------------------------------------------------------------- 结构

    private void build() {
        // 焦点行会按 1.1 / 1.03 放大，放大后的边缘只要超出父容器就会被裁掉一块
        // （Aerial 也在淡入层里垫了一圈 16dp 的 Box，注释写的就是这件事）。
        // 这里把「不许裁」明写出来：面板本身和每一层容器都不裁子控件。
        setClipChildren(false);
        setClipToPadding(false);

        // 遮罩：纯黑 28%（Aerial 的 dismissOnTap 那一层）
        scrim = new View(getContext());
        scrim.setBackgroundColor(0x47000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sheetShowing) {
                    closeSettingsSheet();
                } else {
                    hide();
                }
            }
        });
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        panel.setBackground(new GlassDrawable(sdp(RADIUS_DP)));
        panel.setPadding(sdp(PAD_DP), sdp(PAD_DP), sdp(PAD_DP), sdp(PAD_DP));
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                sdp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP | Gravity.END;
        panelParams.topMargin = sdp(SAFE_TOP_DP);
        panelParams.rightMargin = sdp(SAFE_END_DP);
        addView(panel, panelParams);

        buildHeader();
        panel.addView(space(12));
        buildRoundButtons();
        panel.addView(space(12));
        buildSliders();
        panel.addView(space(4));
        buildFooter();
        buildSettingsSheet();
    }

    /** Aerial 的 Spacer(Modifier.height(…)） */
    private View space(float aerialDp) {
        View view = new View(getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(1, sdp(aerialDp)));
        return view;
    }

    private void buildHeader() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout clockBox = new LinearLayout(getContext());
        clockBox.setOrientation(LinearLayout.VERTICAL);
        row.addView(clockBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clockView = new TextView(getContext());
        // Aerial：Type.LargeTitle(26sp) × 1.05，SmallClock 的粗度
        sp(clockView, 27.3f);
        clockView.setTextColor(LABEL);
        clockView.setTypeface(clockView.getTypeface(), android.graphics.Typeface.BOLD);
        clockView.setIncludeFontPadding(false);
        clockBox.addView(clockView);

        dateView = new TextView(getContext());
        // Type.Callout 13sp
        sp(dateView, 13f);
        dateView.setTextColor(SECONDARY);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = sdp(2);
        clockBox.addView(dateView, dateParams);

        LinearLayout netBox = new LinearLayout(getContext());
        netBox.setOrientation(LinearLayout.HORIZONTAL);
        netBox.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(netBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        netIcon = new ImageView(getContext());
        netIcon.setImageResource(R.drawable.ic_wifi);
        netIcon.setColorFilter(SECONDARY);
        netBox.addView(netIcon, new LinearLayout.LayoutParams(sdp(14), sdp(14)));

        netLabel = new TextView(getContext());
        // Type.Caption 11sp
        sp(netLabel, 11f);
        netLabel.setTextColor(SECONDARY);
        netLabel.setSingleLine(true);
        LinearLayout.LayoutParams netParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        netParams.leftMargin = sdp(6);
        netBox.addView(netLabel, netParams);
    }

    private void buildRoundButtons() {
        // Aerial：Row(fillMaxWidth, SpaceBetween) + 每颗按钮 width(64.dp)（ControlCenter.kt 的
        // RoundButton）。三颗固定宽 + 两段弹性空隙，才是真正的两端对齐；三个等分格子
        // 里各自居中，间距会跟着面板宽度变，看起来比 Aerial 松。
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 聚焦时按钮会放大 1.1 倍：这一行的高度正好等于按钮高度，不关裁剪的话
        // 放大的部分会被这一行切掉，焦点圈上下变成平的
        row.setClipChildren(false);
        row.setClipToPadding(false);
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sourceButton = new RoundButton(getContext(), R.drawable.ic_src_live, "信号源", new Runnable() {
            @Override
            public void run() {
                hide();
                listener.onOpenSource();
            }
        });
        row.addView(sourceButton, buttonParams());
        row.addView(gap(), new LinearLayout.LayoutParams(0, 1, 1f));

        RoundButton netButton = new RoundButton(getContext(), R.drawable.ic_wifi, "网络", new Runnable() {
            @Override
            public void run() {
                hide();
                listener.onOpenNetwork();
            }
        });
        row.addView(netButton, buttonParams());
        row.addView(gap(), new LinearLayout.LayoutParams(0, 1, 1f));

        RoundButton settingsButton = new RoundButton(getContext(), R.drawable.ic_src_settings, "设置", new Runnable() {
            @Override
            public void run() {
                // 不跳出去：跳软件设置模块会把面板整个顶掉，回来还得重新找位置。
                // 这里就地升起一块底部面板，改完按返回就回到面板。
                openSettingsSheet();
            }
        });
        row.addView(settingsButton, buttonParams());
    }

    /** 圆按钮固定 64dp 宽（Aerial 的 Column(Modifier.width(64.dp))） */
    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(sdp(BUTTON_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View gap() {
        View view = new View(getContext());
        view.setFocusable(false);
        return view;
    }

    private void buildSliders() {
        volumeRow = new AudioSlider(getContext());
        panel.addView(volumeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sdp(ROW_HEIGHT_DP)));
        panel.addView(space(6));

        brightnessRow = new BrightnessSlider(getContext());
        panel.addView(brightnessRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sdp(ROW_HEIGHT_DP)));
    }

    private void buildFooter() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = sdp(14);
        rowParams.rightMargin = sdp(14);
        panel.addView(row, rowParams);

        statusDot = new View(getContext());
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GREEN);
        statusDot.setBackground(dot);
        row.addView(statusDot, new LinearLayout.LayoutParams(sdp(7), sdp(7)));

        statusText = new TextView(getContext());
        sp(statusText, 11f);
        statusText.setTextColor(SECONDARY);
        statusText.setSingleLine(true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = sdp(8);
        row.addView(statusText, statusParams);

        versionText = new TextView(getContext());
        sp(versionText, 11f);
        versionText.setTextColor(TERTIARY);
        row.addView(versionText);
    }

    // ---------------------------------------------------------------- 设置（面板向下扩展）

    /**
     * 「设置」按下去之后在面板内部向下长出来的一块。
     *
     * <p>第一版做成「从屏幕底部升起一张独立卡」，实机看着像两个不相干的东西叠在一起，
     * 而且面板还得跟着淡出。现在改成<b>面板自己变高</b>：同一块玻璃、同一个右上角，
     * 开关行顺着面板往下长，随时能抬头看见音量和亮度——它们本来就是一体的。
     *
     * <p>高度是用 ValueAnimator 一段段改 LayoutParams 做出来的：LinearLayout 不会自己
     * 给高度做动画，不加这一段就是「啪」地跳出来。
     */
    private void buildSettingsSheet() {
        sheet = new LinearLayout(getContext());
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setVisibility(GONE);
        LinearLayout.LayoutParams sheetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetParams.topMargin = sdp(4);
        panel.addView(sheet, sheetParams);

        // 一条 1px 的分隔：上面是音量/亮度，下面是开关，不画线会黏成一片
        View divider = new View(getContext());
        divider.setBackgroundColor(0x1AFFFFFF);
        sheet.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        sheet.addView(space(6));

        addSheetRow(new ToggleRow(getContext(), "桌面守护") {
            @Override
            String stateText() {
                return GuardSetup.isGuardReady(getContext())
                        ? (DeskPrefs.homeGuard(getContext()) ? "开" : "关")
                        : "未就绪";
            }

            @Override
            void toggle() {
                if (!GuardSetup.isGuardReady(getContext())) {
                    // 服务没跑起来时写这个开关只会让人以为「开了但没用」，
                    // 这里明说一句，别偷偷改一个不生效的值
                    com.tvdesk.core.ui.DeskToast.show(getContext(),
                            "守护没在运行 · 到系统无障碍里打开「xbox-tv 桌面守护」");
                    return;
                }
                DeskPrefs.setHomeGuard(getContext(), !DeskPrefs.homeGuard(getContext()));
            }
        });

        addSheetRow(new ToggleRow(getContext(), "离页后续播") {
            @Override
            String stateText() {
                return DeskPrefs.keepPlayingOnLeave(getContext()) ? "开" : "关";
            }

            @Override
            void toggle() {
                DeskPrefs.setKeepPlayingOnLeave(getContext(),
                        !DeskPrefs.keepPlayingOnLeave(getContext()));
            }
        });

        addSheetRow(new ToggleRow(getContext(), "显示时钟") {
            @Override
            String stateText() {
                return DeskPrefs.showClock(getContext()) ? "开" : "关";
            }

            @Override
            void toggle() {
                DeskPrefs.setShowClock(getContext(), !DeskPrefs.showClock(getContext()));
                if (listener != null) {
                    listener.onQuickSettingsChanged();
                }
            }
        });

        addSheetRow(new ToggleRow(getContext(), "显示天气") {
            @Override
            String stateText() {
                return DeskPrefs.showWeather(getContext()) ? "开" : "关";
            }

            @Override
            void toggle() {
                DeskPrefs.setShowWeather(getContext(), !DeskPrefs.showWeather(getContext()));
                if (listener != null) {
                    listener.onQuickSettingsChanged();
                }
            }
        });

        addSheetRow(new ToggleRow(getContext(), "保持 ADB 调试") {
            @Override
            String stateText() {
                return DeskPrefs.keepAdb(getContext()) ? "开" : "关";
            }

            @Override
            void toggle() {
                DeskPrefs.setKeepAdb(getContext(), !DeskPrefs.keepAdb(getContext()));
                GuardSetup.keepAdbIfWanted(getContext());
            }
        });
    }

    private void addSheetRow(ToggleRow row) {
        // 行高 42dp：面板展开后总高度已经贴着 720dp 画布的下沿，再多 4dp 就把
        // 最后一行推出屏幕（这里按实机截图量过，不是拍的）
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sdp(42));
        lp.topMargin = sdp(2);
        sheet.addView(row, lp);
        sheetRows.add(row);
    }

    /** 一行开关：左边名字，右边状态。聚焦时整行变白（和滑杆同一套观感） */
    private abstract class ToggleRow extends LinearLayout {
        private final TextView valueView;
        private final TextView labelView;

        ToggleRow(Context context, String text) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setPadding(sdp(ROW_PAD_DP), 0, sdp(ROW_PAD_DP), 0);
            setBackground(rounded(FILL));

            labelView = new TextView(getContext());
            sp(labelView, 14f);
            labelView.setText(text);
            labelView.setTextColor(LABEL);
            addView(labelView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            valueView = new TextView(getContext());
            sp(valueView, 14f);
            valueView.setTextColor(SECONDARY);
            valueView.setGravity(Gravity.END);
            addView(valueView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle();
                    refresh();
                }
            });
            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    setBackground(rounded(hasFocus ? FOCUS : FILL));
                    int fg = hasFocus ? ON_FOCUS : LABEL;
                    labelView.setTextColor(fg);
                    valueView.setTextColor(hasFocus ? ON_FOCUS : SECONDARY);
                }
            });
            refresh();
        }

        final void refresh() {
            String text = stateText();
            valueView.setText(text);
            // 颜色跟「这一行」的焦点走：valueView 自己不参与焦点，按它的 isFocused()
            // 判会永远停在浅灰，白底上几乎看不见字
            valueView.setTextColor(isFocused() ? ON_FOCUS
                    : ("未就绪".equals(text) ? TERTIARY : SECONDARY));
        }

        abstract String stateText();

        abstract void toggle();
    }

    private void openSettingsSheet() {
        if (sheetShowing) {
            return;
        }
        sheetShowing = true;
        lastFocusInPanel = findFocus();
        for (ToggleRow row : sheetRows) {
            row.refresh();
        }
        // 先量一遍真实高度，再把它从 0 拉到这个高度：面板是一层 LinearLayout，
        // 只有改 LayoutParams.height 才会真的「长出来」
        int width = panel.getWidth() - sdp(PAD_DP) * 2;
        if (width <= 0) {
            width = sdp(WIDTH_DP) - sdp(PAD_DP) * 2;
        }
        sheet.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int target = Math.max(sdp(46), sheet.getMeasuredHeight());
        sheet.setVisibility(VISIBLE);
        ValueAnimator animator = ValueAnimator.ofInt(0, target);
        animator.setDuration(260);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = (Integer) animation.getAnimatedValue();
                sheet.setLayoutParams(lp);
            }
        });
        animator.start();
        if (!sheetRows.isEmpty()) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sheetShowing && !sheetRows.isEmpty()) {
                        sheetRows.get(0).requestFocus();
                    }
                }
            }, 60);
        }
    }

    private void closeSettingsSheet() {
        if (!sheetShowing) {
            return;
        }
        sheetShowing = false;
        final int from = sheet.getHeight();
        ValueAnimator animator = ValueAnimator.ofInt(from, 0);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                lp.height = (Integer) animation.getAnimatedValue();
                sheet.setLayoutParams(lp);
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!sheetShowing) {
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    sheet.setLayoutParams(lp);
                    sheet.setVisibility(GONE);
                }
            }
        });
        animator.start();
        // 焦点还回「设置」按钮，不然遥控器会停在一个已经收起来的行上
        if (lastFocusInPanel != null && lastFocusInPanel.isShown()) {
            lastFocusInPanel.requestFocus();
        } else if (sourceButton != null) {
            sourceButton.requestFocus();
        }
    }

    public boolean isShowing() {
        return showing;
    }

    public void show() {
        if (showing) {
            return;
        }
        showing = true;
        // 上一次收起来时如果设置面板还开着，残留的可见状态会把它一起带出来
        sheetShowing = false;
        if (sheet != null) {
            sheet.setVisibility(GONE);
        }
        lastFocus = getRootView().findFocus();
        refreshContent();
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();

        panel.setPivotX(sdp(WIDTH_DP));
        panel.setPivotY(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new DecelerateInterpolator()).start();
        // 入场动画跑完再抢焦点，否则焦点环会跟着缩放一起抖
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (showing && sourceButton != null) {
                    sourceButton.requestFocus();
                }
            }
        }, 60);
    }

    public void hide() {
        if (!showing) {
            return;
        }
        showing = false;
        // 设置面板跟着一起收，不留一块没人管的浮层
        sheetShowing = false;
        if (sheet != null) {
            sheet.setVisibility(GONE);
        }
        panel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
        scrim.animate().alpha(0f).setDuration(180).start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 180ms 内又按了菜单键：这次收起作废，别把刚打开的面板又藏回去
                if (showing) {
                    return;
                }
                setVisibility(GONE);
                if (lastFocus != null && lastFocus.isShown()) {
                    lastFocus.requestFocus();
                } else if (listener != null) {
                    listener.onClosedWithoutFocus();
                }
            }
        }, 180);
    }

    /** 面板开着时先吃 BACK / MENU；其它键交给焦点系统 */
    public boolean handleKey(KeyEvent event) {
        if (!showing || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        int code = event.getKeyCode();
        if (sheetShowing) {
            // 设置面板是第二层：返回先收它，再返回才收整个面板
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                closeSettingsSheet();
                return true;
            }
            View focused = findFocus();
            if (focused != null && !sheetRows.isEmpty()
                    && code == KeyEvent.KEYCODE_DPAD_DOWN
                    && isInside(focused, sheetRows.get(sheetRows.size() - 1))) {
                // 最后一行再往下就出面板了：吃掉，焦点留在原地
                return true;
            }
            return false;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
            hide();
            return true;
        }
        // 面板只占屏幕右上角，几何寻焦很容易一步跨到背后的海报墙上（焦点掉出面板，
        // 遥控器就「走丢」了）。头尾两处把越界的方向键吃掉：最下面的滑杆再往下、
        // 第一排按钮再往上，都留在原地。
        View focus = findFocus();
        if (focus != null) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && brightnessRow != null
                    && isInside(focus, brightnessRow)) {
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP && sourceButton != null
                    && isInside(focus, sourceButton)) {
                return true;
            }
        }
        return false;
    }

    /** child 是不是 view 本身或它的子控件 */
    private boolean isInside(View child, View view) {
        View cur = child;
        while (cur != null) {
            if (cur == view) {
                return true;
            }
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- 内容

    private void refreshContent() {
        long now = System.currentTimeMillis();
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(now)));
        dateView.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date(now)));
        netLabel.setText(networkName());

        boolean guardOn = DeskPrefs.homeGuard(getContext()) && GuardSetup.isGuardReady(getContext());
        statusText.setText(guardOn ? "桌面守护已开启" : "桌面守护未开启");
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(guardOn ? GREEN : ORANGE);
        statusDot.setBackground(dot);

        versionText.setText("xbox-tv " + versionName());

        if (volumeRow != null) {
            volumeRow.refresh();
        }
        if (brightnessRow != null) {
            brightnessRow.refresh();
        }
    }

    private String networkName() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "";
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) {
                return "未连接";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "有线";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "Wi-Fi";
            }
            return "已连接";
        } catch (Throwable th) {
            return "";
        }
    }

    private String versionName() {
        try {
            return getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable th) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 圆按钮

    private final class RoundButton extends LinearLayout {
        private final ImageView icon;
        private final TextView label;
        private final View circle;

        RoundButton(Context context, int iconRes, String text, final Runnable action) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);

            FrameLayout circleBox = new FrameLayout(getContext());
            addView(circleBox, new LinearLayout.LayoutParams(sdp(CIRCLE_DP), sdp(CIRCLE_DP)));

            circle = new View(getContext());
            circle.setBackground(circle(FILL));
            circleBox.addView(circle, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            icon = new ImageView(getContext());
            icon.setImageResource(iconRes);
            icon.setColorFilter(LABEL);
            FrameLayout.LayoutParams iconParams =
                    new FrameLayout.LayoutParams(sdp(BUTTON_ICON_DP), sdp(BUTTON_ICON_DP));
            iconParams.gravity = Gravity.CENTER;
            circleBox.addView(icon, iconParams);

            label = new TextView(getContext());
            label.setText(text);
            sp(label, 11f);
            label.setTextColor(SECONDARY);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = sdp(6);
            addView(label, textParams);

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.run();
                }
            });
            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    circle.setBackground(circle(hasFocus ? FOCUS : FILL));
                    icon.setColorFilter(hasFocus ? ON_FOCUS : LABEL);
                    label.setTextColor(hasFocus ? LABEL : SECONDARY);
                    animate().scaleX(hasFocus ? 1.1f : 1f).scaleY(hasFocus ? 1.1f : 1f)
                            .setDuration(140)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            });
        }
    }

    private GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    // ---------------------------------------------------------------- 滑杆

    /** 滑杆的公共骨架：图标 + 轨道 + 数值，左右键步进 */
    private abstract class Slider extends LinearLayout {
        private final ImageView icon;
        private final Track track;
        private final TextView value;
        private boolean focused;

        Slider(Context context, int iconRes) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setPadding(sdp(ROW_PAD_DP), 0, sdp(ROW_PAD_DP), 0);
            setBackground(rounded(focused ? FOCUS : FILL));

            icon = new ImageView(getContext());
            icon.setImageResource(iconRes);
            icon.setColorFilter(LABEL);
            addView(icon, new LinearLayout.LayoutParams(sdp(ROW_ICON_DP), sdp(ROW_ICON_DP)));

            track = new Track(getContext());
            LinearLayout.LayoutParams trackParams =
                    new LinearLayout.LayoutParams(0, sdp(TRACK_DP), 1f);
            // Aerial 的 Row(horizontalArrangement = spacedBy(12.dp))：图标／轨道／数值三段的间距
            // 都是 12dp，轨道吃掉剩下的宽度
            trackParams.leftMargin = sdp(12);
            addView(track, trackParams);

            value = new TextView(getContext());
            sp(value, 15f);
            value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
            value.setTextColor(LABEL);
            value.setGravity(Gravity.END);
            value.setSingleLine(true);
            // Aerial 的 modifier.widthIn(min = 28.dp)：只给下限，两位数（100）不被截
            value.setMinWidth(sdp(VALUE_MIN_DP));
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.leftMargin = sdp(12);
            addView(value, valueParams);

            setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    focused = hasFocus;
                    setBackground(rounded(hasFocus ? FOCUS : FILL));
                    int fg = hasFocus ? ON_FOCUS : LABEL;
                    icon.setColorFilter(fg);
                    value.setTextColor(fg);
                    track.setForeground(fg);
                    animate().scaleX(hasFocus ? 1.03f : 1f).scaleY(hasFocus ? 1.03f : 1f)
                            .setDuration(140)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            });
        }

        void setLevel(float fraction, String text) {
            track.setFraction(fraction);
            value.setText(text);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                step(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                step(1);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        abstract void step(int delta);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(sdp(ROW_RADIUS_DP));
        shape.setColor(color);
        return shape;
    }

    /** 6dp 高的轨道：底是前景色 20%，前面是实心 */
    private final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;
        private int foreground = LABEL;
        private ValueAnimator animator;

        Track(Context context) {
            super(context);
        }

        /**
         * Aerial 的进度是 spring(dampingRatio = 1, stiffness = 700) 跟过去的：
         * 按一下右键，填充条滑一小段，而不是「啪」地跳一格。电视上按十下就是十次硬跳，
         * 看着像界面没跟上手。这里用 140ms 的减速插值近似那条临界阻尼曲线。
         */
        void setFraction(float value) {
            float target = Math.max(0f, Math.min(1f, value));
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (Math.abs(target - fraction) < 0.004f) {
                fraction = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(fraction, target);
            animator.setDuration(140);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    fraction = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        }

        void setForeground(int color) {
            foreground = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, getWidth(), h);
            paint.setColor(foreground);
            paint.setAlpha(51); // 20%
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setAlpha(255);
            rect.right = getWidth() * fraction;
            if (rect.right > 0) {
                canvas.drawRoundRect(rect, r, r, paint);
            }
        }
    }

    /** 音量：走 AudioManager；点一下静音 */
    private final class AudioSlider extends Slider {
        private final AudioManager audio;

        AudioSlider(Context context) {
            super(context, R.drawable.ic_speaker);
            audio = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (audio == null) {
                        return;
                    }
                    try {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    } catch (Throwable ignored) {
                    }
                    refresh();
                }
            });
            refresh();
        }

        void refresh() {
            if (audio == null) {
                setLevel(0f, "-");
                return;
            }
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && audio.isStreamMute(AudioManager.STREAM_MUSIC);
            setLevel(muted ? 0f : now / (float) max, muted ? "静音" : String.valueOf(now));
        }

        @Override
        void step(int delta) {
            if (audio == null) {
                return;
            }
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } catch (Throwable ignored) {
            }
            refresh();
        }
    }

    /**
     * 亮度：这台小米电视的背光有两套键，AOSP 的那套还是死的。
     *
     * <p>实测（2026-11-12，小米电视）：
     * <ul>
     *   <li>{@code screen_brightness} 的值是 <b>0</b>，屏幕照样满亮——面板背光不归 AOSP 管，
     *       只读它的话滑杆整根空着、右边写个 0，用户以为坏了；</li>
     *   <li>真正生效的是厂家键 {@code backLightForNormal}（0–100）；</li>
     *   <li>而且这个键<b>已经搬到 secure 表</b>了：往 system 表写会抛
     *       {@code IllegalArgumentException: You cannot keep your settings in the secure settings.}
     *       （SettingsProvider 对 MOVED_TO_SECURE 的键就是这么拒的）。</li>
     * </ul>
     *
     * <p>所以读的顺序是 secure 厂家键 → system 厂家键 → {@code screen_brightness}，
     * 写就写「读到的那一套」；万一某个固件又在另一套上生效，写失败时换一套再试一次。
     * 两个都写是不行的：两套都生效的机器会把亮度压两次。
     */
    private final class BrightnessSlider extends Slider {
        /** 小米（mitv）的面板背光，0–100 */
        private static final String KEY_VENDOR_BACKLIGHT = "backLightForNormal";
        private static final int VENDOR_MAX = 100;
        /** 读到的是哪一套 */
        private static final int SRC_SECURE_VENDOR = 0;
        private static final int SRC_SYSTEM_VENDOR = 1;
        private static final int SRC_AOSP = 2;

        private boolean usable = true;
        private int source = SRC_SECURE_VENDOR;

        BrightnessSlider(Context context) {
            super(context, R.drawable.ic_weather_sun);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 不进 AOSP 的「显示设置」：国产电视上那是另一套页面（小米上出来的是
                    // 一个叫「图片」的公版界面）。点这一行是去厂家的画面设置里细调，
                    // 找不到就明说一句，别跳到一个用户没见过的界面
                    if (!VendorSettings.openPicture(getContext())) {
                        com.tvdesk.core.ui.DeskToast.show(getContext(),
                                "没找到画面设置 · 请用遥控器进电视设置");
                    }
                }
            });
            refresh();
        }

        void refresh() {
            float fraction = readFraction();
            if (fraction < 0) {
                usable = false;
                setLevel(0f, "--");
                return;
            }
            usable = true;
            setLevel(fraction, String.valueOf(Math.round(fraction * 100f)));
        }

        /** @return 0–1；读不到返回 -1 */
        private float readFraction() {
            int secure = readSecureBacklight();
            if (secure >= 0) {
                source = SRC_SECURE_VENDOR;
                return Math.min(1f, secure / (float) VENDOR_MAX);
            }
            int system = readSystemBacklight();
            if (system >= 0) {
                source = SRC_SYSTEM_VENDOR;
                return Math.min(1f, system / (float) VENDOR_MAX);
            }
            int aosp = readAospBrightness();
            if (aosp > 0) {
                // 0 表示「这条键在这台机器上没用」，不是「亮度 0」
                source = SRC_AOSP;
                return Math.min(1f, aosp / 255f);
            }
            return -1f;
        }

        private int readSecureBacklight() {
            try {
                return Settings.Secure.getInt(getContext().getContentResolver(),
                        KEY_VENDOR_BACKLIGHT);
            } catch (Throwable th) {
                return -1;
            }
        }

        private int readSystemBacklight() {
            try {
                return Settings.System.getInt(getContext().getContentResolver(),
                        KEY_VENDOR_BACKLIGHT);
            } catch (Throwable th) {
                return -1;
            }
        }

        private int readAospBrightness() {
            try {
                return Settings.System.getInt(getContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS);
            } catch (Throwable th) {
                return -1;
            }
        }

        @Override
        void step(int delta) {
            if (!usable) {
                return;
            }
            float fraction = readFraction();
            if (fraction < 0) {
                return;
            }
            // Aerial 的亮度一步 5%
            float next = Math.max(0f, Math.min(1f, fraction + (delta > 0 ? 0.05f : -0.05f)));
            int vendorValue = Math.round(next * VENDOR_MAX);
            int aospValue = Math.round(next * 255f);
            boolean ok;
            switch (source) {
                case SRC_SECURE_VENDOR:
                    ok = putSecure(KEY_VENDOR_BACKLIGHT, vendorValue) || putSystem(KEY_VENDOR_BACKLIGHT, vendorValue);
                    break;
                case SRC_SYSTEM_VENDOR:
                    ok = putSystem(KEY_VENDOR_BACKLIGHT, vendorValue) || putSecure(KEY_VENDOR_BACKLIGHT, vendorValue);
                    break;
                default:
                    ok = putSystem(Settings.System.SCREEN_BRIGHTNESS, aospValue);
                    break;
            }
            // 这台机器上「写成功但屏幕没变」和「压根没写进去」是两回事，留一行日志分辨
            android.util.Log.i("ControlCenter", "亮度 " + (ok ? "写入" : "写入失败")
                    + " src=" + source + " -> " + vendorValue + "%");
            refresh();
        }

        private boolean putSecure(String key, int value) {
            try {
                return Settings.Secure.putInt(getContext().getContentResolver(), key, value);
            } catch (Throwable th) {
                return false;
            }
        }

        private boolean putSystem(String key, int value) {
            try {
                return Settings.System.putInt(getContext().getContentResolver(), key, value);
            } catch (Throwable th) {
                return false;
            }
        }
    }
}

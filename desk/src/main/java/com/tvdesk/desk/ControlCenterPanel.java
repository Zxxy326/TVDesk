package com.tvdesk.desk;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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
 *   <li>圆按钮：48dp 圆 + 20dp 图标 + 11sp 标签，聚焦底色 #F5F5F7、图标转 #1D1D1F，
 *       缩放 1.1；</li>
 *   <li>滑杆：高 40dp、圆角 14dp、左右内边距 14dp，18dp 图标 + 6dp 轨道，
 *       轨道底为前景色 20% 透明度，聚焦底色同上，缩放 1.03。</li>
 * </ul>
 *
 * <p>和 Aerial 的差别只有内容：信号源 / 网络 / 设置 三个按钮对应我们自己的模块，
 * 音量、亮度走系统接口，最下面一行是桌面守护状态和版本号。
 */
public final class ControlCenterPanel extends FrameLayout {

    public interface Listener {
        void onOpenSource();

        void onOpenNetwork();

        void onOpenSettings();
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

    private static final int WIDTH_DP = 300;
    private static final int RADIUS_DP = 24;
    private static final int PAD_DP = 16;

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

    private boolean showing;
    private View lastFocus;

    public ControlCenterPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setVisibility(GONE);
        setClickable(true);
        build();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 结构

    private void build() {
        // 遮罩：纯黑 28%（Aerial 的 dismissOnTap 那一层）
        scrim = new View(getContext());
        scrim.setBackgroundColor(0x47000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new GlassDrawable(dp(RADIUS_DP)));
        panel.setPadding(dp(PAD_DP), dp(PAD_DP), dp(PAD_DP), dp(PAD_DP));
        panel.setClickable(true);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP | Gravity.END;
        panelParams.topMargin = dp(24);
        panelParams.rightMargin = dp(32);
        addView(panel, panelParams);

        buildHeader();
        panel.addView(space(12));
        buildRoundButtons();
        panel.addView(space(12));
        buildSliders();
        panel.addView(space(8));
        buildFooter();
    }

    private View space(int dp) {
        View view = new View(getContext());
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
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
        clockView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 27.3f);
        clockView.setTextColor(LABEL);
        clockView.setTypeface(clockView.getTypeface(), android.graphics.Typeface.BOLD);
        clockView.setIncludeFontPadding(false);
        clockBox.addView(clockView);

        dateView = new TextView(getContext());
        dateView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        dateView.setTextColor(SECONDARY);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(2);
        clockBox.addView(dateView, dateParams);

        LinearLayout netBox = new LinearLayout(getContext());
        netBox.setOrientation(LinearLayout.HORIZONTAL);
        netBox.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(netBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        netIcon = new ImageView(getContext());
        netIcon.setImageResource(R.drawable.ic_wifi);
        netIcon.setColorFilter(SECONDARY);
        netBox.addView(netIcon, new LinearLayout.LayoutParams(dp(14), dp(14)));

        netLabel = new TextView(getContext());
        netLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        netLabel.setTextColor(SECONDARY);
        netLabel.setSingleLine(true);
        LinearLayout.LayoutParams netParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        netParams.leftMargin = dp(6);
        netBox.addView(netLabel, netParams);
    }

    private void buildRoundButtons() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sourceButton = new RoundButton(getContext(), R.drawable.ic_src_live, "信号源", new Runnable() {
            @Override
            public void run() {
                hide();
                listener.onOpenSource();
            }
        });
        row.addView(sourceButton, weightParams());

        RoundButton netButton = new RoundButton(getContext(), R.drawable.ic_wifi, "网络", new Runnable() {
            @Override
            public void run() {
                hide();
                listener.onOpenNetwork();
            }
        });
        row.addView(netButton, weightParams());

        RoundButton settingsButton = new RoundButton(getContext(), R.drawable.ic_src_settings, "设置", new Runnable() {
            @Override
            public void run() {
                hide();
                listener.onOpenSettings();
            }
        });
        row.addView(settingsButton, weightParams());
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void buildSliders() {
        volumeRow = new AudioSlider(getContext());
        panel.addView(volumeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        panel.addView(space(6));

        brightnessRow = new BrightnessSlider(getContext());
        panel.addView(brightnessRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
    }

    private void buildFooter() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = dp(14);
        rowParams.rightMargin = dp(14);
        panel.addView(row, rowParams);

        statusDot = new View(getContext());
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GREEN);
        statusDot.setBackground(dot);
        row.addView(statusDot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        statusText = new TextView(getContext());
        statusText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        statusText.setTextColor(SECONDARY);
        statusText.setSingleLine(true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = dp(8);
        row.addView(statusText, statusParams);

        versionText = new TextView(getContext());
        versionText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        versionText.setTextColor(TERTIARY);
        row.addView(versionText);
    }

    // ---------------------------------------------------------------- 显示

    public boolean isShowing() {
        return showing;
    }

    public void show() {
        if (showing) {
            return;
        }
        showing = true;
        lastFocus = getRootView().findFocus();
        refreshContent();
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();

        panel.setPivotX(dp(WIDTH_DP));
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

        versionText.setText("TVDesk " + versionName());

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
            addView(circleBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

            circle = new View(getContext());
            circle.setBackground(circle(FILL));
            circleBox.addView(circle, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            icon = new ImageView(getContext());
            icon.setImageResource(iconRes);
            icon.setColorFilter(LABEL);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(20), dp(20));
            iconParams.gravity = Gravity.CENTER;
            circleBox.addView(icon, iconParams);

            label = new TextView(getContext());
            label.setText(text);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(SECONDARY);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = dp(6);
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
            setPadding(dp(14), 0, dp(14), 0);
            setBackground(rounded(focused ? FOCUS : FILL));

            icon = new ImageView(getContext());
            icon.setImageResource(iconRes);
            icon.setColorFilter(LABEL);
            addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));

            track = new Track(getContext());
            LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(0, dp(6), 1f);
            trackParams.leftMargin = dp(12);
            addView(track, trackParams);

            value = new TextView(getContext());
            value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
            value.setTextColor(LABEL);
            value.setGravity(Gravity.END);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    dp(34), ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.leftMargin = dp(12);
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
        shape.setCornerRadius(dp(14));
        shape.setColor(color);
        return shape;
    }

    /** 6dp 高的轨道：底是前景色 20%，前面是实心 */
    private final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;
        private int foreground = LABEL;

        Track(Context context) {
            super(context);
        }

        void setFraction(float value) {
            fraction = Math.max(0f, Math.min(1f, value));
            invalidate();
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
     * 亮度：直接写 Settings.System。
     *
     * <p>这台电视上应用拿得到 WRITE_SECURE_SETTINGS（桌面守护要用），所以系统设置也写得动。
     * 写不动就退化成「去系统设置里调」，而不是留一根拖不动的滑杆。
     */
    private final class BrightnessSlider extends Slider {
        private boolean usable = true;

        BrightnessSlider(Context context) {
            super(context, R.drawable.ic_weather_sun);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(intent);
                    } catch (Throwable ignored) {
                    }
                }
            });
            refresh();
        }

        void refresh() {
            int value = readBrightness();
            if (value < 0) {
                usable = false;
                setLevel(0f, "--");
                return;
            }
            usable = true;
            setLevel(value / 255f, String.valueOf(Math.round(value * 100f / 255f)));
        }

        private int readBrightness() {
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
            int current = readBrightness();
            if (current < 0) {
                return;
            }
            // Aerial 的亮度一步 5%
            int next = Math.max(0, Math.min(255, current + (delta > 0 ? 13 : -13)));
            try {
                Settings.System.putInt(getContext().getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, next);
            } catch (Throwable ignored) {
            }
            refresh();
        }
    }
}

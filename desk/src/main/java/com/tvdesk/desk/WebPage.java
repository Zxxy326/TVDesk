package com.tvdesk.desk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 网页配置中心（网盘源用来登录、排序线路的那一类页面）。
 *
 * <h3>为什么必须有这一页</h3>
 * 网盘类片源（夸克/UC/百度/115…）的账号不在我们这边：它们把配置做成一个
 * <b>本机网页</b>（例如 {@code http://<电视IP>:19988/config}），由爬虫自己在手机上打开
 * 登录后回写。桌面上那张「网页版配置中心」卡片就是它的入口。以前点它走的是「详情 → 播放」
 * 那条路，而爬虫在这种条目上根本没有视频可解析，于是要么报「没有可播放的线路」，
 * 要么干脆把应用重启一次——用户看到的就是「点一下就退回电视首页，网盘永远配不上」。
 *
 * <h3>为什么是自己画一个指针</h3>
 * 电视只有方向键，网页要的是「移到那儿点一下」。WebView 本身不接受方向键，
 * 所以这里在页面上浮一个小圆点当鼠标：方向键移动、确定＝在圆点位置按一下。
 * 这是电视浏览器唯一站得住的做法（TVBox 正片里的网页解析也是这一套）。
 *
 * <h3>返回层级</h3>
 * 本页是压在模块页上的内容页（L5）：BACK 直接 {@link Back#pass(String)} 交回宿主，
 * 由宿主 pop 掉本页；页内没有可收的浮层，不占层级。
 */
public class WebPage extends FrameLayout implements ModulePage {

    private static final int STEP_X_DP = 46;
    private static final int STEP_Y_DP = 34;
    /** 圆点离上下边这么近就滚一屏：网页通常比一屏长，光靠移指针到不了底下 */
    /** 边缘触发翻页的宽度。太大就成了「指针一到中间就滚」，按键全都够不着 */
    private static final int EDGE_DP = 28;

    private final String url;

    private WebView web;
    private View cursor;
    private TextView status;
    private FrameLayout stage;

    private float pointerX;
    private float pointerY;
    private boolean released;
    /** 上一次翻页的时刻：边沿反复触发不能叠着划，不然页面会被甩飞 */
    private long lastSwipeAt;

    public WebPage(Context context, String url, String title) {
        super(context);
        this.url = url == null ? "" : url.trim();

        setFocusable(true);
        setFocusableInTouchMode(true);

        View base = new View(context);
        base.setBackgroundResource(R.color.desk_bg);
        addView(base, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(title), new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(context);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(stage, stageLp);

        web = new WebView(context);
        // 焦点一进 WebView 就被它吃掉，方向键再也回不到页面——这是 TV 上挂 WebView 的经典坑
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        stage.addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        cursor = buildCursor(context);
        stage.addView(cursor, new LayoutParams(dp(18), dp(18)));

        configureWeb();
        web.loadUrl(url);

        // 圆点先摆屏幕正中：多数配置页的主按钮都在这一带，第一下就能点到
        stage.post(new Runnable() {
            @Override
            public void run() {
                pointerX = stage.getWidth() / 2f;
                pointerY = stage.getHeight() / 3f;
                placeCursor();
            }
        });
    }

    private View buildHeader(String title) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(38), dp(18), dp(38), dp(10));

        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(getContext());
        name.setText(TextUtils.isEmpty(title) ? "配置中心" : title);
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setTextSize(20);
        name.getPaint().setFakeBoldText(true);
        line.addView(name);

        TextView hint = new TextView(getContext());
        hint.setText("方向键移动光标 · 确定＝点击 · 菜单＝刷新 · 返回退出");
        hint.setTextColor(getResources().getColor(R.color.text_tertiary));
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.leftMargin = dp(16);
        line.addView(hint, hintLp);
        bar.addView(line);

        status = new TextView(getContext());
        status.setText(url);
        status.setTextColor(getResources().getColor(R.color.text_tertiary));
        status.setTextSize(11);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        bar.addView(status, statusLp);
        return bar;
    }

    private View buildCursor(Context context) {
        View view = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0x33FFFFFF);
        dot.setStroke(dp(2), Color.WHITE);
        view.setBackground(dot);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(dp(8));
        }
        return view;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWeb() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 配置页要从本机接口读写账号，混合内容必须放行，否则 https 页面里的
        // http 请求会被静默掐掉，表现是「点登录没反应」
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(130);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(web, true);
        }

        web.setBackgroundColor(Color.TRANSPARENT);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String target) {
                // 站内跳转全留在这一页；配置页里的链接都是本机/同域的
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String loaded) {
                if (!released) {
                    status.setText(loaded);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 指针

    private void placeCursor() {
        cursor.setTranslationX(pointerX - cursor.getWidth() / 2f);
        cursor.setTranslationY(pointerY - cursor.getHeight() / 2f);
    }

    private void movePointer(int dxDp, int dyDp) {
        if (stage == null || web == null) {
            return;
        }
        int sw = stage.getWidth();
        int sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        float nextX = pointerX + dp(dxDp);
        float nextY = pointerY + dp(dyDp);
        // 顶到底就滚一屏。滚动走「滚轮事件」而不是 WebView.scrollBy：这个配置页的内容在一个
        // overflow:auto 的 div 里，页面本身的滚动偏移永远是 0，scrollBy 一点动静都没有。
        //
        // 翻页之后<b>不要把指针挪回边缘</b>：以前这里会把指针重置到边缘，
        // 于是「光标忽然跳到顶部/底部」，用户看着像失灵，而且指针一直贴着边缘，
        // 再按方向键又立刻翻一页——配置项反而一个都点不着。
        // 现在翻完页指针留在原处，它还指着原来那个元素，继续按方向键就能一路走到底。
        int edge = dp(EDGE_DP);
        if (nextY > sh - edge) {
            swipePage(true);
        } else if (nextY < edge) {
            swipePage(false);
        }
        pointerX = Math.max(1f, Math.min(sw - 1f, nextX));
        pointerY = Math.max(1f, Math.min(sh - 1f, nextY));
        placeCursor();
    }

    /**
     * 翻一屏：在页面上做一次「手指往上/往下划」。
     *
     * <p>先用滚轮试过，配置页的内容不在主文档里（外层 100vh、内层容器自己滚），
     * WebView 的 scrollBy 和滚轮都推不动它；触摸拖动是唯一对所有布局都成立的方式。
     * 拖动起点取屏幕 72% 处、终点 30% 处，跨度够大，浏览器判定为滚动而不是点击。
     */
    private void swipePage(final boolean down) {
        if (web == null || released || stage == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastSwipeAt < 320L) {
            return;
        }
        lastSwipeAt = now;
        final int sh = stage.getHeight();
        if (sh <= 0) {
            return;
        }
        final float x = Math.max(dp(24), Math.min(stage.getWidth() - dp(24), pointerX));
        final float from = down ? sh * 0.72f : sh * 0.30f;
        final float to = down ? sh * 0.30f : sh * 0.72f;
        final long start = SystemClock.uptimeMillis();
        dispatchTouch(start, start, MotionEvent.ACTION_DOWN, x, from);
        final int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final int index = i;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (released || web == null) {
                        return;
                    }
                    float y = from + (to - from) * index / steps;
                    dispatchTouch(start, start + index * 16L, MotionEvent.ACTION_MOVE, x, y);
                    if (index == steps) {
                        dispatchTouch(start, start + steps * 16L + 16L,
                                MotionEvent.ACTION_UP, x, to);
                    }
                }
            }, index * 16L);
        }
    }

    private void dispatchTouch(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        if (event == null) {
            return;
        }
        web.dispatchTouchEvent(event);
        event.recycle();
    }

    /** 在指针位置按一下：WebView 只认触摸事件，这里手工造一对按下/抬起 */
    private void clickAtPointer() {
        if (web == null || released) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                pointerX, pointerY, 0);
        web.dispatchTouchEvent(down);
        down.recycle();
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (released || web == null) {
                    return;
                }
                long up = SystemClock.uptimeMillis();
                MotionEvent event = MotionEvent.obtain(now, up, MotionEvent.ACTION_UP,
                        pointerX, pointerY, 0);
                web.dispatchTouchEvent(event);
                event.recycle();
            }
        }, 60);
    }

    // ---------------------------------------------------------------- 按键

    @Override
    public boolean handleKeyDown(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                movePointer(-STEP_X_DP, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                movePointer(STEP_X_DP, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                movePointer(0, -STEP_Y_DP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                movePointer(0, STEP_Y_DP);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                clickAtPointer();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (web != null) {
                    web.reload();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // L5 内容页：本页没有可收的浮层，直接交回宿主 pop
                return Back.pass("配置中心·退出网页");
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        requestFocus();
        if (web != null) {
            web.onResume();
        }
        // 圆点尺寸要等一帧才量得出来
        post(new Runnable() {
            @Override
            public void run() {
                placeCursor();
            }
        });
    }

    @Override
    public void onLeave() {
        if (web != null) {
            web.onPause();
        }
    }

    @Override
    public void onRelease() {
        released = true;
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            // 正在加载时直接 destroy 会有日志噪音甚至崩，先 load 一个空白页再拆
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

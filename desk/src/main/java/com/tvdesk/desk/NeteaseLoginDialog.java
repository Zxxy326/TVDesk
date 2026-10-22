package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 「网易云账号」弹窗。和 {@link SourceDialog} 一样是<b>纯 UI</b>：不写 cookie、不改登录态，
 * 只把用户的选择回传给 {@link MusicPage}，由它去调 {@code NeteaseClient}。
 *
 * <h3>为什么是扫码 + 粘 cookie 两条路</h3>
 * 无头的登录接口全都走不通（2026-10 实测）：扫码的 {@code /api/w/login/qr/unikey} 已被下掉，
 * 手机号登录的发码那一步被风控拦在 {@code encrypt-pages} 人机校验后面。剩下的路只有两条：
 * <ul>
 *   <li><b>WebView 里扫码</b>——把网易云自己的登录页显示出来，用户用手机 App 扫。
 *       电视上这是唯一舒服的方式，遥控器不可能敲手机号密码。</li>
 *   <li><b>粘贴 cookie</b>——给已经在电脑上登过的用户留的后路。WebView 万一渲染不出二维码
 *       （老 WebView / 页面改版），也不至于完全没法登录。</li>
 * </ul>
 *
 * <h3>WebView 不参与焦点</h3>
 * 它只是给手机扫的，不是给遥控器点的。一旦可聚焦，上下键会掉进网页里出不来——
 * 这是 TV 上挂 WebView 最常见的坑。所以 XML 里直接把 focusable 关掉，
 * 焦点只在「输入框 ↔ 按钮」之间走（{@link #wireFocus}）。
 */
public final class NeteaseLoginDialog extends Dialog {

    /** 网易云网页版登录页。扫码是它的默认 tab */
    private static final String LOGIN_URL = "https://music.163.com/#/login";

    /** 读 cookie 时要认的域。MUSIC_U 就挂在这个域上 */
    private static final String COOKIE_URL = "https://music.163.com";

    /** 桌面 UA：让网页版按 PC 排版渲染，才会出二维码 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";

    public interface Listener {
        /**
         * 点了「已完成登录」。
         *
         * @param manualCookie 用户粘进输入框的内容，没粘就是空串
         * @param webCookie    从 WebView 的 CookieManager 里读到的内容，读不到就是空串
         */
        void onSubmit(String manualCookie, String webCookie);

        /** 点了「退出登录」 */
        void onLogout();
    }

    /** 已登录时的展示文案（昵称），空串表示未登录 */
    private final String accountName;
    private final Listener listener;

    /**
     * 扫完码之后多久看一眼 cookie 罐。
     *
     * <p>这个轮询是必须的：用户扫完码，手机那边显示「登录成功」，弹窗这边却什么都不动，
     * 看着就像卡死了。而且我们不能指望网页自己跳转——网页里的二维码状态是它自己的
     * 轮询，一旦被风控拦掉，页面就永远停在「请扫码」，但 cookie 未必没写进去。
     * 直接盯 cookie 罐最可靠。
     */
    private static final long LOGIN_POLL_MS = 1500;

    private WebView web;
    private EditText input;
    private TextView statusView;
    private android.os.Handler ui;
    private boolean actionSent;

    /**
     * 弹窗刚打开时 WebView 里已有的 MUSIC_U。
     *
     * <p>只认「值变了」的 MUSIC_U，不认「存在」的：登出以后 WebView 的罐子里还留着
     * 上一个账号的 token，如果见到 MUSIC_U 就自动提交，用户点开弹窗想退出登录，
     * 反而会被旧 token 立刻重新登进去，那个「退出登录」按钮永远按不到。
     */
    private String initialMusicU = "";

    private final Runnable pollLogin = new Runnable() {
        @Override
        public void run() {
            if (!isShowing() || actionSent) {
                return;
            }
            String cookie = readWebCookie();
            String musicU = musicU(cookie);
            if (!musicU.isEmpty() && !musicU.equals(initialMusicU)) {
                statusView.setText("扫码成功，正在保存登录…");
                hideIme();
                actionSent = true;
                if (listener != null) {
                    listener.onSubmit("", cookie);
                }
                dismiss();
                return;
            }
            if (ui != null) {
                ui.postDelayed(this, LOGIN_POLL_MS);
            }
        }
    };

    public NeteaseLoginDialog(Activity activity, String accountName, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.accountName = accountName == null ? "" : accountName;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_netease_login);
        sizeWindow();

        input = findViewById(R.id.ncm_dialog_input);
        web = findViewById(R.id.ncm_dialog_web);

        TextView status = findViewById(R.id.ncm_dialog_status);
        statusView = status;
        status.setText(TextUtils.isEmpty(accountName)
                ? "还没登录。用手机网易云 App 扫下面的二维码；也可以直接粘一段 cookie。"
                : "已登录：" + accountName);

        View logout = findViewById(R.id.ncm_btn_logout);
        logout.setVisibility(TextUtils.isEmpty(accountName) ? View.GONE : View.VISIBLE);

        buildWeb();
        wireButtons();
        wireFocus();

        ui = new android.os.Handler(android.os.Looper.getMainLooper());
        initialMusicU = musicU(readWebCookie());
        ui.postDelayed(pollLogin, LOGIN_POLL_MS);

        // 初始焦点给「已完成登录」：扫码这条路不需要碰遥控器，
        // 用户扫完直接按确定就行。给输入框的话会平白弹起输入法挡住二维码。
        findViewById(R.id.dialog_btn_apply).post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    findViewById(R.id.dialog_btn_apply).requestFocus();
                }
            }
        });
    }

    /**
     * 宽高交给内容定（根布局是固定宽 + wrap 高），只压深一点遮罩——
     * 音乐页的文字还在后面，不压暗会两层糊在一起。
     */
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

    // ---------------------------------------------------------------- WebView

    private void buildWeb() {
        if (web == null) {
            return;
        }
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        // 网易云的登录态要走 localStorage/sessionStorage，关掉 DOM storage 会一直停在加载中
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(DESKTOP_UA);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        // 二维码本身就是图片，别开省流把它拦掉
        settings.setBlockNetworkImage(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 网页版会从 music.163.com 往别的域发请求，混合内容拦了会白屏
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 这个弹窗 minSdk 是 19，两个 setter 都是 21 才有的，老机器上直接调会 NoSuchMethodError
            cookies.setAcceptThirdPartyCookies(web, true);
        }

        // 不设 client 的话，页面里任何一次跳转都会往外抛 Intent 去开浏览器
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        if (TextUtils.isEmpty(accountName)) {
            // App 这边是登出态，网页那边可能还留着上次的会话。不清掉的话，网页会直接
            // 打开「已登录」的首页，二维码根本不出来——想换个账号就卡死在这个页面上。
            resetWebSession();
        } else {
            web.loadUrl(LOGIN_URL);
        }
    }

    /** 清空网页侧的 cookie 再打开登录页，保证登出态下一定能看到二维码 */
    private void resetWebSession() {
        CookieManager cookies = CookieManager.getInstance();
        final Runnable load = new Runnable() {
            @Override
            public void run() {
                WebView view = web;
                if (view != null) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            WebView v = web;
                            if (v != null) {
                                v.loadUrl(LOGIN_URL);
                            }
                        }
                    });
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.removeAllCookies(new android.webkit.ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    load.run();
                }
            });
        } else {
            //noinspection deprecation
            cookies.removeAllCookie();
            load.run();
        }
        cookies.flush();
    }

    /**
     * 从 WebView 的 cookie 罐里把这一串读出来。读不到返回空串。
     *
     * <p>这里不调 {@code flush()}：内存里的罐子收到 Set-Cookie 就更新了，
     * 而 flush 是同步写盘，轮询每 1.5 秒写一次纯属给主线程添堵。
     */
    private String readWebCookie() {
        try {
            String raw = CookieManager.getInstance().getCookie(COOKIE_URL);
            return raw == null ? "" : raw;
        } catch (Throwable th) {
            android.util.Log.w("NeteaseLogin", "读 cookie 失败", th);
            return "";
        }
    }

    /**
     * 这串 cookie 里有没有真正的登录态。
     *
     * <p>只看「非空」不够：没登录时网易云也会发几个匿名 cookie（{@code _ntes_nuid}、
     * {@code NMTID} 之类），按非空判断会把「没登」当成「登了」，存进去之后需要登录的接口
     * 照样全部失败。{@code MUSIC_U} 才是账号态的唯一标志。
     */
    private static boolean hasLoginCookie(String raw) {
        return !musicU(raw).isEmpty();
    }

    /** 取出 MUSIC_U 的值，没有就是空串 */
    private static String musicU(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        for (String part : raw.split("[;\\n]")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0 || eq >= item.length() - 1) {
                continue;
            }
            if ("MUSIC_U".equals(item.substring(0, eq).trim())) {
                return item.substring(eq + 1).trim();
            }
        }
        return "";
    }

    /**
     * 松开 WebView。
     *
     * <p>WebView 是这套控件里最重的一个（自带渲染进程），弹窗关掉就没人再看它了，
     * 留着只是白占内存。loadUrl("about:blank") 是为了先掐掉还在跑的请求——
     * 直接 destroy 一个正在加载的 WebView 会有日志噪音甚至崩。
     */
    private void destroyWeb() {
        if (web == null) {
            return;
        }
        try {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.setWebViewClient(null);
            web.removeAllViews();
            web.destroy();
        } catch (Throwable ignored) {
        }
        web = null;
    }

    @Override
    public void dismiss() {
        if (ui != null) {
            ui.removeCallbacks(pollLogin);
        }
        super.dismiss();
        destroyWeb();
    }

    // ---------------------------------------------------------------- 焦点与按键

    /**
     * 焦点通路。
     *
     * <p>输入框是 EditText，方向键会被它当成光标移动吃掉，焦点压根走不到按钮上
     * （{@code nextFocusDownId} 根本没人问，详见 {@link SourceDialog} 里的同一段说明）。
     * 所以上下键在这里手动接住。
     */
    private void wireFocus() {
        View apply = findViewById(R.id.dialog_btn_apply);
        View cancel = findViewById(R.id.dialog_btn_cancel);
        View logout = findViewById(R.id.ncm_btn_logout);

        if (input != null) {
            // 输入框「下」直接到「已完成登录」：这是这个弹窗唯一的主操作
            input.setNextFocusDownId(R.id.dialog_btn_apply);
            input.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            findViewById(R.id.dialog_btn_apply).requestFocus();
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        // 上面没有别的可聚焦控件（WebView 已经关掉焦点了），吃掉别让焦点跑出弹窗
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            submit();
                        }
                        return true;
                    }
                    return false;
                }
            });
            input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == EditorInfo.IME_ACTION_DONE
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_UP)) {
                        submit();
                        return true;
                    }
                    return false;
                }
            });
        }

        // 按钮「上」统一回输入框：这是沿着布局顺序往上走的那一格。
        // 不写的话几何搜索可能落到 WebView 所在的容器上（那一格不可聚焦，就会跳到弹窗外）
        List<View> buttons = new ArrayList<>();
        buttons.add(apply);
        buttons.add(cancel);
        buttons.add(logout);
        for (View button : buttons) {
            button.setNextFocusUpId(R.id.ncm_dialog_input);
        }
    }

    private void wireButtons() {
        findViewById(R.id.dialog_btn_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        findViewById(R.id.dialog_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.ncm_btn_logout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionSent) {
                    return;
                }
                actionSent = true;
                hideIme();
                if (listener != null) {
                    listener.onLogout();
                }
                dismiss();
            }
        });
    }

    private void submit() {
        // 「确定」可能从 IME 和按键监听两条路同时进来，只发一次
        if (actionSent) {
            return;
        }
        String manual = input == null || input.getText() == null
                ? "" : input.getText().toString().trim();
        String webCookie = readWebCookie();
        boolean manualOk = hasLoginCookie(manual);
        boolean webOk = hasLoginCookie(webCookie);
        if (!manualOk && !webOk) {
            // 两条路都没拿到账号态：不是「清空账号」（那有单独的按钮），只是还没登完，留在弹窗里继续
            Toast.makeText(getContext(), R.string.ncm_toast_no_login, Toast.LENGTH_SHORT).show();
            return;
        }
        hideIme();
        actionSent = true;
        if (listener != null) {
            // 粘的那串只在真含 MUSIC_U 时才交出去，匿名 cookie 存了也没用
            listener.onSubmit(manualOk ? manual : "", webCookie);
        }
        dismiss();
    }

    private void hideIme() {
        if (input == null) {
            return;
        }
        Context context = getContext();
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }
}
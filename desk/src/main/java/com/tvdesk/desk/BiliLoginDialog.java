package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.tvdesk.core.bili.BiliClient;

/**
 * B 站登录弹窗：本机画二维码 + 轮询扫码状态，或者手动粘一段 cookie。
 *
 * <h3>为什么不用 WebView</h3>
 * 网易云那个弹窗只能上 WebView，因为它那套扫码接口 2026-10 实测已经下掉了，
 * 只能用官方网页。B 站的 {@code passport-login/web/qrcode} 还好用，
 * 那就自己画：少一个 WebView（在电视上又慢又吃内存，还经常白屏），
 * 而且二维码尺寸可控——WebView 里那张小二维码对着电视屏幕根本扫不上。
 *
 * <h3>两条出路</h3>
 * 扫码为主；下面那个输入框是给「手机上已经登过 / 二维码刷不出来」留的后路。
 * 弹窗本身不改任何登录态：只有轮询确认成功、或用户按「用这段 cookie」才写 cookie。
 *
 * <h3>轮询</h3>
 * B 站的二维码 180 秒过期，所以 2 秒一次问状态，过期就停轮询、提示按「换一张」。
 * 每次换码 {@code generation} 加一，旧请求回来直接丢掉——不然用户连按两次
 * 「换一张」，前一张的轮询会把后一张的状态覆盖掉。
 */
public final class BiliLoginDialog extends Dialog {

    /** 轮询间隔。B 站自己的网页也是 2 秒，再快就是给风控送人头 */
    private static final long POLL_MS = 2000L;

    private final Listener listener;
    private android.os.Handler ui;
    private TextView statusView;
    private ImageView qrView;
    private ProgressBar loadingView;
    private EditText cookieInput;

    /** 轮询要用的 key；二维码本身是本地画的，只有 URL 来自 generate 接口 */
    private String qrKey = "";
    private volatile int generation;
    private volatile boolean polling;
    private boolean finished;

    public interface Listener {
        /** 登录态已确认（cookie 由 {@link BiliClient} 自己落盘了） */
        void onLoggedIn();
    }

    public BiliLoginDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_bili_login);
        sizeWindow();

        ui = new android.os.Handler(android.os.Looper.getMainLooper());
        statusView = findViewById(R.id.bili_login_status);
        qrView = findViewById(R.id.bili_login_qr);
        loadingView = findViewById(R.id.bili_login_loading);
        cookieInput = findViewById(R.id.bili_login_input);

        findViewById(R.id.bili_login_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestQrcode();
            }
        });
        findViewById(R.id.dialog_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.bili_login_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyManualCookie();
            }
        });

        requestQrcode();

        // 初始焦点给「换一张」：扫码不用碰遥控器，粘 cookie 的人自己会挪过去。
        // 给输入框会当场弹起输入法，把刚画好的二维码挡住。
        findViewById(R.id.bili_login_refresh).post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    findViewById(R.id.bili_login_refresh).requestFocus();
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

    // ---------------------------------------------------------------- 二维码

    private void requestQrcode() {
        final int mine = ++generation;
        polling = false;
        qrKey = "";
        loadingView.setVisibility(View.VISIBLE);
        qrView.setVisibility(View.GONE);
        statusView.setText("正在生成二维码…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliClient.Qr qr = null;
                String error = null;
                try {
                    qr = BiliClient.get(getContext()).qrcodeGenerate();
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "取不到二维码" : e.getMessage();
                }
                final BiliClient.Qr result = qr;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finished || mine != generation) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        if (result == null || TextUtils.isEmpty(result.url)) {
                            statusView.setText((failure == null ? "取不到二维码" : failure)
                                    + " · 按「换一张」重试");
                            return;
                        }
                        qrKey = result.key;
                        qrView.setImageBitmap(QrCode.of(result.url, 512));
                        qrView.setVisibility(View.VISIBLE);
                        statusView.setText("未扫码 · 用手机 B 站 App 扫这里");
                        polling = true;
                        ui.postDelayed(pollTask, POLL_MS);
                    }
                });
            }
        }).start();
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollOnce(generation);
        }
    };

    private void pollOnce(final int mine) {
        if (finished || !polling || mine != generation || TextUtils.isEmpty(qrKey)) {
            return;
        }
        final String key = qrKey;
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliClient.QrResult result = null;
                String error = null;
                try {
                    result = BiliClient.get(getContext()).qrcodePoll(key);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "问不到扫码状态" : e.getMessage();
                }
                final BiliClient.QrResult value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finished || mine != generation) {
                            return;
                        }
                        if (value == null) {
                            statusView.setText(failure + " · 按「换一张」重试");
                            return;
                        }
                        if (value.success) {
                            finishLoggedIn();
                            return;
                        }
                        if (value.code == 86038) {
                            // 过期：码已经不认了，继续轮询只是白跑
                            polling = false;
                            statusView.setText("二维码过期了 · 按「换一张」");
                            return;
                        }
                        statusView.setText(value.code == 86090
                                ? "已扫码 · 请在手机上点确认"
                                : "未扫码 · 用手机 B 站 App 扫这里");
                        ui.postDelayed(pollTask, POLL_MS);
                    }
                });
            }
        }).start();
    }

    private void finishLoggedIn() {
        if (finished) {
            return;
        }
        finished = true;
        polling = false;
        ui.removeCallbacks(pollTask);
        // 确认登录态再关：poll 成功只代表 cookie 到手，nav 能读出昵称才算真登上了
        new Thread(new Runnable() {
            @Override
            public void run() {
                String nickname = "";
                try {
                    com.tvdesk.core.bili.model.BiliAccount info =
                            BiliClient.get(getContext()).nav();
                    if (info != null && info.loggedIn) {
                        nickname = info.nickname;
                    }
                } catch (Exception ignored) {
                }
                final String name = nickname;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onLoggedIn();
                        }
                        Toast.makeText(getContext(), TextUtils.isEmpty(name)
                                ? "已登录" : "已登录：" + name, Toast.LENGTH_SHORT).show();
                        dismiss();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 手动 cookie

    private void applyManualCookie() {
        String raw = cookieInput.getText() == null ? "" : cookieInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            statusView.setText("先粘一段 cookie，或者按「换一张」扫码");
            return;
        }
        if (!raw.contains("SESSDATA")) {
            Toast.makeText(getContext(), "这段 cookie 里没有 SESSDATA，登不上",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        finished = true;
        polling = false;
        BiliClient.get(getContext()).setCookie(raw);
        if (listener != null) {
            listener.onLoggedIn();
        }
        dismiss();
    }

    @Override
    public void dismiss() {
        finished = true;
        polling = false;
        if (ui != null) {
            ui.removeCallbacks(pollTask);
        }
        super.dismiss();
    }

    @Override
    public void onBackPressed() {
        // 返回必须能退出，且不改登录态：没确认成功的码直接作废
        dismiss();
    }
}

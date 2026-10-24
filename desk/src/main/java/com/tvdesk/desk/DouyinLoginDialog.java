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
import android.widget.TextView;

import com.tvdesk.core.douyin.DouyinClient;
import com.tvdesk.core.douyin.DouyinPushServer;



/**
 * 抖音「登录」弹窗：本机起一个局域网表单，二维码指向它；同时保留直接粘 cookie 的后路。
 *
 * <h3>为什么不是扫码登录</h3>
 * B 站那个弹窗能真扫码，是因为 {@code passport-login/web/qrcode} 这套接口对第三方还开着。
 * 抖音没有等价物：网页端登录全在 {@code passport.douyin.com}，带滑块与设备指纹风控，
 * 电视里既登不上也过不了验证。所以这里唯一可行的路径是把电脑上已有的 cookie 搬过来，
 * 二维码只是把用户送到本机那个表单页——键盘在手机上手，比遥控器逐字敲两三 KB 强。
 *
 * <h3>三条出路</h3>
 * 扫码表单、手动粘贴、取消/清除。前两条各对应一种网络情况（同网段 / 不同网段），
 * 后两条保证「点进来看了一眼麻烦」能原路退出。弹窗自身不改登录态：
 * 只有表单提交上来、或用户按「用这段 cookie」才写 cookie。
 *
 * <h3>服务生命周期</h3>
 * 只在弹窗显示期间开着，{@link #dismiss()} 立刻停。没有鉴权，所以不能常驻；
 * 端口被占会往上涨一位，实在起不来就退回「手动粘贴」那条路，并把地址提示换成交流说明。
 */
public final class DouyinLoginDialog extends Dialog {

    /** 首选端口。core 里 TVBox 的 RemoteServer 用的是另一族端口，故意错开 */
    private static final int BASE_PORT = 9979;
    private static final int PORT_PROBES = 6;

    private final Listener listener;
    /** 为什么被打开（没登录 / 接口原文）；null 表示用户自己按的「登录」 */
    private final String reason;
    private android.os.Handler ui;
    private ImageView qrView;
    private View qrArea;
    private TextView statusView;
    private TextView urlView;
    private EditText cookieInput;
    private DouyinPushServer server;
    private boolean finished;

    public interface Listener {
        /** cookie 变了（导入了或清掉了），页面按它刷新登录态相关的入口 */
        void onCookieChanged();
    }

    public DouyinLoginDialog(Activity activity, String reason, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.reason = reason;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_douyin_login);
        sizeWindow();
        ui = new android.os.Handler(android.os.Looper.getMainLooper());

        qrView = findViewById(R.id.douyin_login_qr);
        qrArea = findViewById(R.id.douyin_login_qr_area);
        statusView = findViewById(R.id.douyin_login_status);
        urlView = findViewById(R.id.douyin_login_url);
        cookieInput = findViewById(R.id.douyin_login_input);

        // 被互动失败顶开时，第一行说的是那件事而不是常规的扫码提示：
        // 用户刚按了点赞，突然跳出一个只讲「扫码」的框，只会以为程序发疯了
        if (!TextUtils.isEmpty(reason)) {
            statusView.setText(reason + " · 可以清掉重来，或按「取消」回去");
        }

        findViewById(R.id.dialog_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.douyin_login_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyCookie(cookieInput.getText() == null ? "" : cookieInput.getText().toString());
            }
        });
        findViewById(R.id.douyin_login_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DouyinClient.get(getContext()).clearCookie();
                cookieInput.setText("");
                statusView.setText("已清除登录态 · 按「取消」退出");
                notifyChanged();
            }
        });

        startServer();

        // 初始焦点给「取消」：这个弹窗十有八九是进来看一眼发现要粘一长串就走，
        // 焦点落在输入框上会让人以为必须先填东西才能退出。
        findViewById(R.id.dialog_btn_cancel).post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    findViewById(R.id.dialog_btn_cancel).requestFocus();
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

    // ---------------------------------------------------------------- 本机服务

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                DouyinPushServer started = null;
                String failure = null;
                try {
                    started = DouyinPushServer.startFrom(BASE_PORT, PORT_PROBES,
                            new DouyinPushServer.Listener() {
                                @Override
                                public void onCookie(final String raw) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!finished) {
                                                applyCookie(raw);
                                            }
                                        }
                                    });
                                }

                                /**
                                 * 搜索关键词不归这个弹窗管。
                                 *
                                 * <p>两张表单其实同一个服务在回，端口让位时谁也不会和另一个
                                 * 同时开着——真开了也是对面那个弹窗该接的话，这里只当没听见。
                                 */
                                @Override
                                public void onQuery(String keyword) {
                                }
                            });
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                final DouyinPushServer value = started;
                final String error = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (finished) {
                            if (value != null) {
                                value.stop();
                            }
                            return;
                        }
                        if (value == null) {
                            // 起不来不影响另一条出路，把话说清楚就行
                            noQrcode("本机推送服务起不来"
                                    + (TextUtils.isEmpty(error) ? "" : "（" + error + "）")
                                    + " · 请直接用下面的输入框粘 cookie");
                            return;
                        }
                        server = value;
                        String url = DouyinPushServer.formUrl(value.getListeningPort());
                        if (TextUtils.isEmpty(url)) {
                            noQrcode("没取到电视的局域网地址（多半不在同一个网段）"
                                    + " · 请用下面的输入框粘 cookie");
                            return;
                        }
                        qrView.setImageBitmap(QrCode.of(url, 512));
                        urlView.setText(url);
                        if (TextUtils.isEmpty(reason)) {
                            statusView.setText(
                                    "用手机或电脑扫这个码 · 把浏览器里的 cookie 提交上来");
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 二维码这条路断了（服务起不来 / 没拿到局域网地址）：整块白底收掉，只留文字出口。
     *
     * <p>留着白框等于在电视上摆一块空白，用户会以为还在加载；而第一行那句「为什么被打开」
     * 也不能被覆盖掉——他是按了点赞才看到这个弹窗的，只讲扫码不讲点赞，就像程序发疯了。
     */
    private void noQrcode(String note) {
        // 收的是整块二维码区（含白框）：面板高度跟着内容走，不会留下半屏空白
        qrArea.setVisibility(View.GONE);
        urlView.setVisibility(View.GONE);
        statusView.setText(TextUtils.isEmpty(reason) ? note : reason + " · " + note);
    }

    /** 收下并落盘一段 cookie */
    private void applyCookie(String raw) {
        String cleaned = DouyinPushServer.sanitize(raw == null ? "" : raw);
        if (TextUtils.isEmpty(cleaned)) {
            statusView.setText("没收到有效的 cookie · 再粘一次或按「取消」退出");
            return;
        }
        DouyinClient client = DouyinClient.get(getContext());
        client.setCookie(cleaned);
        boolean full = client.hasLoginCookie();
        statusView.setText(full ? "已导入 · 搜索和点赞能用了 · 按「取消」返回"
                : "已导入，但" + client.missingCookieHint() + " · 按「取消」返回");
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onCookieChanged();
        }
    }

    @Override
    public void dismiss() {
        finished = true;
        if (server != null) {
            final DouyinPushServer value = server;
            server = null;
            // 同搜索弹窗：立刻 stop() 会把刚 POST 上来的那次响应掐在半路，
            // 手机上看到的是「连接被重置」，只会让人以为没提交成功又提交一遍
            if (ui == null) {
                value.stop();
            } else {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        value.stop();
                    }
                }, 700);
            }
        }
        super.dismiss();
    }
}

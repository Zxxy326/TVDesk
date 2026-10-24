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

import com.tvdesk.core.douyin.DouyinPushServer;

/**
 * 抖音「搜索」弹窗：本机起一个局域网表单，二维码指向它的 {@code /search}；
 * 同时也留一条在电视上直接敲关键词的后路。
 *
 * <h3>为什么又是局域网表单</h3>
 * 抖音的搜索接口本身不难调（{@code DouyinClient.search} 早就通了），难的是「把要搜的那句话
 * 送进电视」。遥控器输入法逐字翻候选词，搜两个汉字比导一次 cookie 还劝退，所以借
 * {@link DouyinLoginDialog} 那套已经验证过的机制：手机扫开网页敲字，POST 回电视。
 *
 * <h3>和登录弹窗共用一个端口族</h3>
 * 两张表单由同一个服务的两条路径回（{@code /} 和 {@code /search}），端口都从 9979 往上让。
 * 因为端口是探测出来的，两个弹窗不会互相抢不到——后开的那个会自动让一位，
 * 而二维码内容跟着实际端口走，所以指的永远是自己的那张表单。
 *
 * <h3>收到关键词就关</h3>
 * 和登录弹窗不同，这里没有「先看看再决定」的余地：用户已经搜了，挡在前面的弹窗必须让开，
 * 让他直接看到结果。所以表单一提交（或用户按了「搜这个词」）就回调 + {@code dismiss()}，
 * 服务跟着停——弹窗自身不常驻，这也是它不带鉴权还能开着的唯一前提。
 */
public final class DouyinSearchDialog extends Dialog {

    /** 和登录弹窗同一起点：端口让位是探测出来的，撞不上 */
    private static final int BASE_PORT = 9979;
    private static final int PORT_PROBES = 6;

    public interface Listener {
        /**
         * 拿到了关键词，弹窗即将关闭。
         *
         * @param keyword 已去掉首尾空白、非空
         */
        void onSearch(String keyword);
    }

    private final Listener listener;
    private android.os.Handler ui;
    private ImageView qrView;
    private View qrArea;
    private TextView statusView;
    private TextView urlView;
    private EditText input;
    private DouyinPushServer server;
    private boolean finished;

    public DouyinSearchDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_douyin_search);
        sizeWindow();
        ui = new android.os.Handler(android.os.Looper.getMainLooper());

        qrView = findViewById(R.id.douyin_search_qr);
        qrArea = findViewById(R.id.douyin_search_qr_area);
        statusView = findViewById(R.id.douyin_search_status);
        urlView = findViewById(R.id.douyin_search_url);
        input = findViewById(R.id.douyin_search_input);

        findViewById(R.id.dialog_btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.douyin_search_go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit(input.getText() == null ? "" : input.getText().toString());
            }
        });

        startServer();

        // 初始焦点给输入框：这个弹窗九成是「不想扫码，直接敲两个字」。
        // 扫码那条路照样通（二维码和地址都摆在上面），只是不逼用户先解决网络问题。
        input.requestFocus();
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
                                public void onCookie(String raw) {
                                    // 这个弹窗只挂搜索表单；cookie 页是登录弹窗的事
                                }

                                @Override
                                public void onQuery(final String keyword) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!finished) {
                                                submit(keyword);
                                            }
                                        }
                                    });
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
                                    + " · 请直接在下面的输入框敲关键词");
                            return;
                        }
                        server = value;
                        String url = DouyinPushServer.searchUrl(value.getListeningPort());
                        if (TextUtils.isEmpty(url)) {
                            noQrcode("没取到电视的局域网地址（多半不在同一个网段）"
                                    + " · 请直接在下面的输入框敲关键词");
                            return;
                        }
                        qrView.setImageBitmap(QrCode.of(url, 512));
                        urlView.setText(url);
                        statusView.setText("扫这个码用手机敲 · 或者直接在下面的输入框里打");
                    }
                });
            }
        }).start();
    }

    /**
     * 二维码这条路断了：整块白底收掉，只留文字出口。
     *
     * <p>留着白框等于在电视上摆一块空白，用户会以为还在加载；下面那个输入框才是这里的路。
     * 收的是整块二维码区（连同它下面那行地址），面板高度跟着内容走，不会留下半屏空洞。
     */
    private void noQrcode(String note) {
        qrArea.setVisibility(View.GONE);
        urlView.setVisibility(View.GONE);
        statusView.setText(note);
    }

    // ---------------------------------------------------------------- 交出关键词

    /** 收下关键词并关掉弹窗；空串只提示，不会把用户堵在这里 */
    private void submit(String raw) {
        String keyword = raw == null ? "" : raw.trim();
        if (keyword.length() == 0) {
            statusView.setText("还没输入关键词 · 打个词再按「搜这个词」，或按「取消」退出");
            return;
        }
        finished = true;
        if (listener != null) {
            listener.onSearch(keyword);
        }
        dismiss();
    }

    @Override
    public void dismiss() {
        finished = true;
        if (server != null) {
            final DouyinPushServer value = server;
            server = null;
            // 当场 stop() 会把刚提交那次请求的 socket 掐掉：手机上只得到「连接被重置」，
            // 看不到「已经送到电视了」那页，还以为没提交成功。让半秒把响应发完再关。
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

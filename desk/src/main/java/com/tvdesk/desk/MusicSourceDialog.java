package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.catvod.music.LxManager;
import com.github.catvod.music.LxPushServer;

import java.io.File;

/**
 * 音乐音源的扫码导入弹窗。
 *
 * <h3>为什么不是在电视上填表</h3>
 * 音源是一份几百 KB 的 .js，遥控器逐字敲不现实；电视上也没有系统的文件选择器。
 * 所以这里起一个局域网接收页，二维码指向它，用户在手机上选文件上传。
 * 这和抖音登录弹窗是同一套思路，只是收的东西从 cookie 换成了脚本。
 *
 * <h3>生命周期</h3>
 * 服务只在弹窗显示期间开着，{@link #dismiss()} 立刻停。没有鉴权，不能常驻。
 * 端口从 9987 往上让位，避开抖音那个 9979 和 TVBox 的 9978。
 */
public final class MusicSourceDialog extends Dialog {

    private static final int BASE_PORT = 9987;
    private static final int PORT_PROBES = 6;

    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LxPushServer server;
    private boolean finished;

    private ImageView qrView;
    private View qrArea;
    private TextView urlView;
    private TextView statusView;

    public interface Listener {
        /** 收到一个新音源（或用户要求重扫）：页面自己去重扫列表 */
        void onSourceImported();
    }

    public MusicSourceDialog(Activity activity, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_lx_source);
        sizeWindow();

        qrView = findViewById(R.id.lx_source_qr);
        qrArea = findViewById(R.id.lx_source_qr_area);
        urlView = findViewById(R.id.lx_source_url);
        statusView = findViewById(R.id.lx_source_status);

        findViewById(R.id.lx_source_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        findViewById(R.id.lx_source_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusView.setText("正在重新扫描音源目录…");
                if (listener != null) {
                    listener.onSourceImported();
                }
            }
        });

        startServer();

        // 进来看一眼就走的人占多数：焦点给「完成」，不给「重新扫描」
        findViewById(R.id.lx_source_done).post(new Runnable() {
            @Override
            public void run() {
                if (isShowing()) {
                    findViewById(R.id.lx_source_done).requestFocus();
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

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                LxPushServer started = null;
                String failure = null;
                try {
                    started = LxPushServer.startFrom(BASE_PORT, PORT_PROBES, getContext(),
                            new LxPushServer.Listener() {
                                @Override
                                public void onImported(File file, String name) {
                                    final String label = name;
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (finished) {
                                                return;
                                            }
                                            // 文件在上传回调前已经落盘，扫描只是刷新列表；
                                            // 原先这里写「正在导入…」但没人再改回来，会一直停在半路
                                            statusView.setText("已收到 " + label + " · 已加入音源列表，可以继续传下一个");
                                            if (listener != null) {
                                                listener.onSourceImported();
                                            }
                                        }
                                    });
                                }
                            });
                } catch (Exception e) {
                    failure = e.getMessage();
                }
                final LxPushServer value = started;
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
                            qrArea.setVisibility(View.GONE);
                            urlView.setVisibility(View.GONE);
                            statusView.setText("本机接收服务起不来"
                                    + (TextUtils.isEmpty(error) ? "" : "（" + error + "）")
                                    + " · 可以按「重新扫描」读取文件管理器/U 盘放进来的 .js");
                            return;
                        }
                        server = value;
                        String url = LxPushServer.formUrl(value.getListeningPort());
                        if (TextUtils.isEmpty(url)) {
                            qrArea.setVisibility(View.GONE);
                            urlView.setVisibility(View.GONE);
                            statusView.setText("没取到电视的局域网地址（多半不在同一个网段）"
                                    + " · 也可以把 .js 放进 "
                                    + LxManager.dir(getContext()).getAbsolutePath()
                                    + " 再按「重新扫描」");
                            return;
                        }
                        qrView.setImageBitmap(QrCode.of(url, 512));
                        urlView.setText(url);
                        statusView.setText("用手机扫这个码 · 选一个 LX 音源 .js 上传");
                    }
                });
            }
        }).start();
    }

    @Override
    public void dismiss() {
        finished = true;
        if (server != null) {
            server.stop();
            server = null;
        }
        super.dismiss();
    }
}

package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tvdesk.desk.motion.FocusScaler;

/**
 * 「发现新版本」弹窗。
 *
 * <h3>为什么不用 AlertDialog</h3>
 * 系统那套在电视上是一块白底方块 + 直角按钮，和整个界面（玻璃面板、圆角胶囊、聚焦放大）
 * 完全不同风格。这里和「返回桌面」那个确认框用同一块 {@code bg_dialog_panel}，
 * 按钮也是同一套 {@code bg_dialog_btn}，缩放到 1.04 —— 看起来才是这个 App 的一部分。
 *
 * <h3>三个阶段</h3>
 * <ol>
 *   <li><b>问</b>：标题 + 版本 + 更新说明 + 三颗按钮；</li>
 *   <li><b>下</b>：说明区让位给进度条，按钮禁用（这一下不能退，退会留下半个包）；</li>
 *   <li><b>完</b>：关掉弹窗，交给系统的安装界面。</li>
 * </ol>
 */
public final class UpdateDialog extends Dialog {

    public interface Listener {
        /** 用户点了「下载并安装」 */
        void onDownload();

        /** 用户点了「忽略此版本」 */
        void onIgnore();

        /** 用户关掉了弹窗（以后再说 / BACK） */
        void onLater();
    }

    private final Listener listener;

    private TextView title;
    private TextView subtitle;
    private TextView notes;
    private View notesScroll;
    private View progressBox;
    private ProgressBar progress;
    private TextView progressText;
    private TextView downloadBtn;
    private TextView ignoreBtn;
    private TextView laterBtn;

    private boolean chosen;
    private boolean downloading;

    public UpdateDialog(Activity activity, String versionName, String currentVersion,
                        String body, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.listener = listener;
        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!chosen && !downloading && UpdateDialog.this.listener != null) {
                    UpdateDialog.this.listener.onLater();
                }
            }
        });
        setCanceledOnTouchOutside(true);

        View root = activity.getLayoutInflater().inflate(R.layout.dialog_update, null);
        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        title = root.findViewById(R.id.update_title);
        subtitle = root.findViewById(R.id.update_subtitle);
        notes = root.findViewById(R.id.update_notes);
        notesScroll = root.findViewById(R.id.update_notes_scroll);
        progressBox = root.findViewById(R.id.update_progress_box);
        progress = root.findViewById(R.id.update_progress);
        progressText = root.findViewById(R.id.update_progress_text);
        downloadBtn = root.findViewById(R.id.update_download);
        ignoreBtn = root.findViewById(R.id.update_ignore);
        laterBtn = root.findViewById(R.id.update_later);

        title.setText("发现新版本 " + versionName);
        subtitle.setText("当前 " + currentVersion + " · 更新不会丢配置和登录");
        notes.setText(body == null || body.trim().isEmpty() ? "（这次没写更新说明）" : body.trim());

        FocusScaler.disableSystemHighlightTree(root);
        FocusScaler.attach(downloadBtn, 1.04f);
        FocusScaler.attach(ignoreBtn, 1.04f);
        FocusScaler.attach(laterBtn, 1.04f);

        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen = true;
                if (UpdateDialog.this.listener != null) {
                    UpdateDialog.this.listener.onDownload();
                }
            }
        });
        ignoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen = true;
                dismiss();
                if (UpdateDialog.this.listener != null) {
                    UpdateDialog.this.listener.onIgnore();
                }
            }
        });
        laterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chosen = true;
                dismiss();
                if (UpdateDialog.this.listener != null) {
                    UpdateDialog.this.listener.onLater();
                }
            }
        });

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.62f;
            window.setAttributes(attrs);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 弹窗自己的背景是透明的：面板圆角靠 bg_dialog_panel 自己画
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
    }

    /** 进入下载态：说明区换成进度条，按钮全部禁用（这期间不能退） */
    public void setDownloading(boolean value) {
        downloading = value;
        chosen = value || chosen;
        notesScroll.setVisibility(value ? View.GONE : View.VISIBLE);
        progressBox.setVisibility(value ? View.VISIBLE : View.GONE);
        int visibility = value ? View.GONE : View.VISIBLE;
        downloadBtn.setVisibility(visibility);
        ignoreBtn.setVisibility(visibility);
        laterBtn.setVisibility(visibility);
        setCancelable(!value);
        if (value) {
            progress.setProgress(0);
            progressText.setText("准备下载…");
        }
    }

    public void setProgress(int percent) {
        if (!downloading) {
            return;
        }
        progress.setProgress(Math.max(0, Math.min(100, percent)));
        progressText.setText(percent >= 100 ? "下载完成，正在打开安装界面…" : ("正在下载 " + percent + "%"));
    }

    /** 下载失败：退回可操作状态，并把原因写进说明区 */
    public void showError(String message) {
        downloading = false;
        chosen = false;
        progressBox.setVisibility(View.GONE);
        notesScroll.setVisibility(View.VISIBLE);
        downloadBtn.setVisibility(View.VISIBLE);
        ignoreBtn.setVisibility(View.VISIBLE);
        laterBtn.setVisibility(View.VISIBLE);
        setCancelable(true);
        notes.setText("下载失败：" + (message == null ? "未知原因" : message) + "\n\n可以再试一次，或者稍后在设置里更新。");
        downloadBtn.requestFocus();
    }

    /** 下载完就关掉，别挡着系统的安装界面 */
    public void closeAfterInstall() {
        chosen = true;
        dismiss();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (downloading) {
            // 下载中 BACK 不关弹窗：关掉会留下半个包，用户也不知道该等还是重来
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}

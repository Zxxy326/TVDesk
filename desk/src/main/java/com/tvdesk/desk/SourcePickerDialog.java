package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.catvod.music.LxSource;
import com.github.catvod.music.MusicSourceDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「选择音源」弹窗 —— 列出内置网易云与已安装 JS 脚本注册的源。
 *
 * <p>对齐像素播放器 v1.7.2：播放器菜单里能看见所有能出链接的源，选中后对当前歌曲
 * 重新解析，队列与播放进度保持不变。
 *
 * <h3>为什么按 id 去重</h3>
 * 一个 JS 脚本可以注册多个平台（同 id {@code lx:文件名}、platform 不同）。
 * 选源这一层只决定「用哪个脚本」，具体平台由歌曲自己的 platform 决定，
 * 所以同一脚本的多平台行合并成一条，否则列表里会出现好几行一模一样的名字。
 */
public final class SourcePickerDialog extends Dialog {

    public interface Listener {
        /** 用户选定了某个源。空串表示「自动」 */
        void onPick(MusicSourceDef def);
    }

    private final List<MusicSourceDef> sources;
    private final String currentId;
    private final Listener listener;

    public SourcePickerDialog(Activity activity, List<MusicSourceDef> sources,
                              String currentId, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.sources = dedupe(sources);
        this.currentId = currentId == null ? "" : currentId;
        this.listener = listener;
    }

    /** 同一 id 只留第一条（内置 wy + 每个脚本一条） */
    private static List<MusicSourceDef> dedupe(List<MusicSourceDef> in) {
        List<MusicSourceDef> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (MusicSourceDef d : in) {
            if (d == null || TextUtils.isEmpty(d.id) || seen.contains(d.id)) {
                continue;
            }
            seen.add(d.id);
            out.add(d);
        }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_dialog_panel);
        int pad = dp(26);
        root.setPadding(pad, dp(22), pad, dp(20));

        TextView title = new TextView(getContext());
        title.setText("选择音源");
        title.setTextColor(getContext().getResources().getColor(R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setFocusable(true);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(getContext());
        hint.setText("选中后重新解析当前歌曲，队列与播放进度保留");
        hint.setTextColor(getContext().getResources().getColor(R.color.text_secondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        hintLp.bottomMargin = dp(14);
        root.addView(hint, hintLp);

        ScrollView scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        root.addView(scroll, scrollLp);

        // 「自动」：交回解析核心按缓存→首选→JS→官方 的顺序自己挑
        list.addView(buildRow(null));

        for (MusicSourceDef def : sources) {
            list.addView(buildRow(def));
        }

        if (sources.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("还没有已安装的音源脚本");
            empty.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(14), dp(12), dp(14), dp(12));
            list.addView(empty);
        }

        setContentView(root);
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(getWindow().getDecorView());
        sizeWindow();
    }

    /** 一行源。def 为 null 表示「自动」 */
    private View buildRow(final MusicSourceDef def) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_module_item);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(true);
        int padH = dp(16);
        row.setPadding(padH, dp(12), padH, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        // 左侧平台色竖条：内置网易云红、QQ 绿、酷我橙、酷狗蓝
        View bar = new View(getContext());
        String platform = def == null ? "auto" : def.platform;
        bar.setBackgroundColor(platformColor(platform));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(4), dp(34));
        barLp.rightMargin = dp(12);
        row.addView(bar, barLp);

        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, textsLp);

        TextView name = new TextView(getContext());
        name.setText(def == null ? "自动选择" : def.name);
        name.setTextColor(getContext().getResources().getColor(R.color.text_primary));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(name);

        TextView meta = new TextView(getContext());
        String sub;
        if (def == null) {
            sub = "按缓存 · 首选源 · 脚本 · 官方接口依次尝试";
        } else {
            sub = def.displayMeta();
            if (TextUtils.isEmpty(sub)) {
                sub = LxSource.platformName(def.platform);
            }
        }
        meta.setText(sub);
        meta.setTextColor(getContext().getResources().getColor(R.color.text_tertiary));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(2);
        texts.addView(meta, metaLp);

        boolean inUse = def == null ? TextUtils.isEmpty(currentId)
                : TextUtils.equals(def.id, currentId);
        if (inUse) {
            TextView badge = new TextView(getContext());
            badge.setText("使用中");
            badge.setTextColor(0xFF6E8BFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = dp(10);
            row.addView(badge, badgeLp);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPick(def);
                }
                dismiss();
            }
        });
        return row;
    }

    /** 平台品牌色（对齐像素播放器 v1.8.0 的标识色） */
    static int platformColor(String platform) {
        if (platform == null) {
            return 0xFF6E8BFF;
        }
        switch (platform) {
            case "wy":
                return 0xFFE64141; // 网易云红
            case "tx":
            case "qq":
                return 0xFF31C27C; // QQ 音乐绿
            case "kw":
                return 0xFFFF7A00; // 酷我橙
            case "kg":
                return 0xFF2E7CF6; // 酷狗蓝
            case "mg":
                return 0xFFFF5C8A; // 咪咕粉
            default:
                return 0xFF6E8BFF;
        }
    }

    private int dp(int v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private void sizeWindow() {
        Window w = getWindow();
        if (w == null) {
            return;
        }
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = dp(680);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.setAttributes(lp);
    }
}

package com.tvdesk.desk;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.widget.BackdropView;

/**
 * 未开工模块（抖音）的内嵌占位页。
 *
 * <p>即使是占位，也和真模块走同一条「内嵌页面」通道——风格按 MEMORY.md 的
 * 统一基准画（desk_bg + BackdropView + module_title_size + 1.06 焦点缩放），
 * 之后换成真内容时只是换一个 ModulePage 实现，宿主代码一行不用动。
 */
public class PlaceholderPage extends FrameLayout implements ModulePage {

    private TextView back;

    public PlaceholderPage(Context context, String moduleName, final Runnable onClose) {
        super(context);

        // 背景：desk_bg 底 + BackdropView 氛围光（模块页统一基准，不许纯色）
        View base = new View(context);
        base.setBackgroundResource(R.color.desk_bg);
        addView(base, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(new BackdropView(context), new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        addView(center, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView title = new TextView(context);
        title.setText(moduleName);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(32); // module_title_size
        title.getPaint().setFakeBoldText(true);
        center.addView(title);

        TextView hint = new TextView(context);
        hint.setText("模块建设中 · 敬请期待");
        hint.setTextColor(getResources().getColor(R.color.text_tertiary));
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(12);
        center.addView(hint, hintLp);

        // 总得有个能聚焦的东西，不然遥控器进来就是「死屏」
        back = new TextView(context);
        back.setText("← 返回桌面");
        back.setTextColor(getResources().getColor(R.color.text_primary));
        back.setTextSize(16);
        back.setGravity(Gravity.CENTER);
        back.setBackgroundResource(R.drawable.bg_module_item);
        back.setFocusable(true);
        back.setClickable(true);
        int padH = dp(18), padV = dp(10);
        back.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        backLp.topMargin = dp(28);
        center.addView(back, backLp);
        FocusScaler.attach(back, 1.06f);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onClose.run();
            }
        });
        back.requestFocus();
    }

    @Override
    public void onEnter() {
        // 每次进来都把焦点放回「返回桌面」，避免焦点落在藏起来的桌面上
        post(new Runnable() {
            @Override
            public void run() {
                back.requestFocus();
            }
        });
    }

    @Override
    public boolean handleKeyDown(int keyCode) {
        // 占位页没有层级，所有键交回宿主（BACK 由宿主关页）
        return false;
    }

    @Override
    public void onRelease() {
        // 无资源
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

package com.tvdesk.desk;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.PressFx;

/**
 * 一行设置项，照桌面设置页（activity_settings.xml）那套生成：左边标题 + 说明，
 * 右边取值 / 药丸 / 箭头，整行可焦可点。
 *
 * <p>B 站播放设置、弹幕设置这类弹窗里的行也走这里。同一个 app 里两类设置长两副样子，
 * 用户每进一处都得重新学一遍「哪儿能按、按了会怎样」。
 */
final class SettingsRow {

    /** 右侧是取值文字：循环选项（清晰度、倍速、画面比例…） */
    static final int TEXT = 0;
    /** 右侧是开关药丸，selected 表示「开」，和设置页那颗同一个 drawable */
    static final int TOGGLE = 1;
    /** 右侧是箭头：按下去是下一级页面，当前取值写在说明行里 */
    static final int ENTRY = 2;

    final View view;
    private final TextView note;
    private final TextView value;
    private final int kind;

    static SettingsRow add(LinearLayout parent, Context context, String label, String noteText,
                           String valueText, int kind, Runnable action) {
        SettingsRow row = new SettingsRow(context, label, noteText, valueText, kind, action);
        parent.addView(row.view);
        return row;
    }

    private SettingsRow(Context context, String label, String noteText, String valueText,
                        final int kind, final Runnable action) {
        this.kind = kind;
        Resources res = context.getResources();
        float density = res.getDisplayMetrics().density;
        int padH = res.getDimensionPixelSize(R.dimen.settings_row_pad_h);
        int rowHeight = res.getDimensionPixelSize(R.dimen.settings_row_height);
        int gap = res.getDimensionPixelSize(R.dimen.settings_row_gap);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_setting_row);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(true);
        row.setMinimumHeight(rowHeight);
        row.setPadding(padH, Math.round(14 * density), padH, Math.round(14 * density));
        row.setId(View.generateViewId());

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setFocusable(false);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.settings_item_size));
        title.setTextColor(res.getColor(R.color.text_primary));
        title.setSingleLine(true);
        title.setFocusable(false);
        texts.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.settings_note_size));
        note.setTextColor(res.getColor(R.color.text_secondary));
        note.setFocusable(false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = Math.round(4 * density);
        texts.addView(note, noteLp);
        setNote(noteText);

        View pressed = row;
        if (kind == ENTRY) {
            value = null;
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setContentDescription(null);
            chevron.setFocusable(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round(20 * density), Math.round(20 * density));
            lp.leftMargin = Math.round(16 * density);
            row.addView(chevron, lp);
        } else {
            TextView text = new TextView(context);
            value = text;
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.settings_value_size));
            text.setSingleLine(true);
            text.setFocusable(false);
            text.setMaxWidth(Math.round(240 * density));
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Math.round(16 * density);
            if (kind == TOGGLE) {
                // 药丸是状态本身，不只是一段文字：点它和点整行是同一个动作
                text.setBackgroundResource(R.drawable.bg_setting_switch);
                text.setGravity(Gravity.CENTER);
                text.setTypeface(null, android.graphics.Typeface.BOLD);
                text.setMinimumWidth(Math.round(104 * density));
                text.setPadding(Math.round(14 * density), Math.round(7 * density),
                        Math.round(14 * density), Math.round(7 * density));
            }
            row.addView(text, lp);
            pressed = text;
        }
        setValue(valueText);

        final View squishTarget = pressed;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 压扁的是取值那颗，不是整行：整行的缩放归 FocusScaler 管，
                // 两个动画写同一个属性会互相把值钉回 1
                PressFx.squish(squishTarget);
                action.run();
            }
        });
        // 焦点放大给得比桌面的卡片小：设置行是整行宽的，1.08 会顶出弹窗边
        FocusScaler.attach(row, 1.03f);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = gap;
        // 左右各留 14dp：焦点放大 3% 会往两边长十几个 dp，
        // 行紧贴滚动区边的话长出去的圆角正好被 ScrollView 裁掉，聚焦看着像「缺一块」
        lp.leftMargin = Math.round(14 * density);
        lp.rightMargin = Math.round(14 * density);
        row.setLayoutParams(lp);
        this.view = row;
    }

    void setValue(String text) {
        if (value == null) {
            return;
        }
        value.setText(text);
        if (kind == TEXT) {
            value.setTextColor(value.getResources().getColor(R.color.text_primary));
        }
    }

    void setNote(String text) {
        note.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        note.setText(text);
    }

    /** 开关行：状态压在药丸的 selected 上，文字和颜色跟着走（同 SettingsActivity#renderKeepPlaying） */
    void setOn(boolean on) {
        if (value == null) {
            return;
        }
        value.setSelected(on);
        value.setText(on ? "开" : "关");
        value.setTextColor(value.getResources().getColor(
                on ? R.color.text_on_light : R.color.text_secondary));
    }
}

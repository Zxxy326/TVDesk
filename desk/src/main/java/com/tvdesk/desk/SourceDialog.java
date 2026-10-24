package com.tvdesk.desk;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 「配置地址」弹窗。
 *
 * <h3>它是纯 UI</h3>
 * 这个类<b>不写任何配置</b>。它只做三件事：把当前地址和历史填进界面、收用户的输入、
 * 把用户的选择回传给调用方。真正写 TVBox 配置的是
 * {@link com.tvdesk.core.feed.SourceConfig}，由设置页和信号源页各自调。
 *
 * <p>这么切是为了「谁改配置」只有一个答案。弹窗如果自己写配置，
 * 以后想在应用前加个校验、或者应用后想刷新别的界面，就得反过来从弹窗往外捅回调。
 *
 * <h3>交互流程（遥控器）</h3>
 * <pre>
 *   输入框 ←→ 历史第 1 条 ←→ … ←→ 历史第 n 条 ←→ [清空] [取消] [应用]
 *     ↑______________________________________________|
 * </pre>
 * 上下是按布局顺序走的，但两头接了直通线（见 {@link #wireFocus}）：
 * 从输入框按一下「下」直接落到历史第一条，历史最后一条按「下」直接落到「应用」，
 * 按钮按「上」回到历史最后一条。这样「输入一个地址然后应用」只要 1 次下 + 1 次确认，
 * 不用把历史从头走到尾。
 *
 * <p>这里的「下」不是靠 {@code nextFocusDownId} 自动生效的：输入框是 EditText，
 * 它会把方向键当成光标移动吃掉，焦点压根走不到下一格。所以输入框上的上下键、
 * 确定键都在 {@link #wireIme} 里手动接住了。
 *
 * <p>焦点在输入框时按遥控器「确定」＝点「应用」，不用先按「下」。
 *
 * <h3>两个刻意的选择</h3>
 * <ul>
 *   <li><b>选历史只填进输入框，不直接应用。</b>选中后焦点自动跳到「应用」，
 *       用户按一下确认即可。多按一次，换来的是「换源前能看清自己要换成什么」——
 *       电视上误触一次就换掉内容源，代价比多按一次大。</li>
 *   <li><b>不和源站通信。</b>这里不做「测试这个源能不能用」，那是拉配置（还可能连带着下 jar）
 *       的活，几秒到几十秒。能不能用由桌面回报：返回桌面后顶栏的提示条会把
 *       「还没配置 / 源没响应 / 这个源给不出类目」分开说。</li>
 * </ul>
 */
public final class SourceDialog extends Dialog {

    /**
     * 历史最多列几条。
     *
     * <p>TVBox 的历史最多存 30 条，全列出来的话弹窗比屏幕还高，而且遥控器要从头按到尾。
     * 8 条覆盖了绝大多数「切回上一个源」的场景；被截掉的会在列表末尾说明还有多少条，
     * 不做无声截断——用户看不到自己昨天填的地址，会以为记录丢了。
     */
    private static final int MAX_HISTORY = 8;

    /** 用户动作回传。两种动作都会让弹窗关闭 */
    public interface Listener {
        /** 点了「应用」。{@code url} 已 trim，非空 */
        void onApply(String url);

        /** 点了「清空」 */
        void onClear();
    }

    private final String currentUrl;
    private final List<String> history;
    private final Listener listener;

    private EditText input;
    private LinearLayout historyBox;
    private ScrollView historyScroll;
    private View historyEmpty;
    private View applyButton;

    /** 已经发出过动作（应用/清空），避免同一个键从两条路进来时重复触发 */
    private boolean actionSent;

    public SourceDialog(Activity activity, String currentUrl, List<String> history, Listener listener) {
        super(activity, R.style.DeskDialogTheme);
        this.currentUrl = currentUrl == null ? "" : currentUrl;
        this.history = history;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_source);
        sizeWindow();

        input = findViewById(R.id.dialog_input);
        historyBox = findViewById(R.id.dialog_history);
        historyScroll = findViewById(R.id.dialog_history_scroll);
        historyEmpty = findViewById(R.id.dialog_history_empty);
        applyButton = findViewById(R.id.dialog_btn_apply);

        input.setText(currentUrl);
        // 进到这里就是要改地址的，全选省得用户先按一串删除键
        input.setSelectAllOnFocus(true);

        buildHistory();
        wireIme();
        wireButtons();
        wireFocus();

        // onCreate 阶段 View 还没 layout，直接 requestFocus 会失败
        final View first = historyBox.getChildCount() > 0 ? historyBox.getChildAt(0) : input;
        first.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing()) {
                    return;
                }
                first.requestFocus();
            }
        });
    }

    /**
     * 窗口尺寸。
     *
     * <p>宽高都交给内容决定（{@code dialog_source.xml} 里根布局是固定宽 + wrap 高）。
     * 只额外压深一点背景遮罩：设置页本身的文字还在后面，不压暗会两层糊在一起。
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

    // ---------------------------------------------------------------- 列表

    private void buildHistory() {
        historyBox.removeAllViews();
        int count = history == null ? 0 : history.size();
        if (count == 0) {
            // 没有历史就不占位置：空着一条 224dp 的滚动区，弹窗会显得很空
            historyScroll.setVisibility(View.GONE);
            historyEmpty.setVisibility(View.VISIBLE);
            return;
        }

        int shown = Math.min(count, MAX_HISTORY);
        for (int i = 0; i < shown; i++) {
            historyBox.addView(buildHistoryRow(history.get(i)));
        }
        if (count > shown) {
            historyBox.addView(buildHistoryOverflow(count - shown));
        }
    }

    private View buildHistoryRow(final String url) {
        final Context context = getContext();
        int padH = context.getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h);
        int height = context.getResources().getDimensionPixelSize(R.dimen.settings_dialog_row_height);

        TextView row = new TextView(context);
        String builtin = com.tvdesk.core.feed.BuiltinSources.displayName(url);
        row.setText(TextUtils.isEmpty(builtin) ? url : builtin);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.setTextColor(context.getResources().getColorStateList(R.color.dialog_item_text));
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.setBackgroundResource(R.drawable.bg_dialog_item);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, 0, padH, 0);
        row.setFocusable(true);
        row.setClickable(true);
        row.setFocusableInTouchMode(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.bottomMargin = context.getResources().getDimensionPixelSize(R.dimen.settings_row_gap) / 2;
        row.setLayoutParams(lp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 只填进输入框，不直接应用——用户有机会看清自己要换成什么
                input.setText(url);
                input.setSelection(input.getText().length());
                applyButton.requestFocus();
            }
        });
        return row;
    }

    /** 被 {@link #MAX_HISTORY} 截掉的那些：说清楚还剩几条，别让用户以为记录丢了 */
    private View buildHistoryOverflow(int hidden) {
        Context context = getContext();
        int padH = context.getResources().getDimensionPixelSize(R.dimen.settings_row_pad_h);

        TextView note = new TextView(context);
        note.setText("还有 " + hidden + " 个更早的源没列出");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(context.getResources().getColor(R.color.text_tertiary));
        note.setBackgroundResource(R.drawable.bg_dialog_item);
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(padH, 0, padH, 0);
        note.setFocusable(false);
        note.setClickable(false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.getResources().getDimensionPixelSize(R.dimen.settings_dialog_row_height));
        note.setLayoutParams(lp);
        return note;
    }

    // ---------------------------------------------------------------- 焦点与输入法

    /**
     * 焦点通路。
     *
     * <p>动态建出来的行默认没有 id，{@code setNextFocusDownId} 就没法指认它们，
     * 所以先给每一行生成一个 id。
     */
    private void wireFocus() {
        int rows = historyBox.getChildCount();
        for (int i = 0; i < rows; i++) {
            View row = historyBox.getChildAt(i);
            if (row.isFocusable()) {
                row.setId(View.generateViewId());
            }
        }

        View lastFocusableRow = null;
        for (int i = rows - 1; i >= 0; i--) {
            if (historyBox.getChildAt(i).isFocusable()) {
                lastFocusableRow = historyBox.getChildAt(i);
                break;
            }
        }

        // 输入框「下」：有历史就落到第一条，没有就直接落到「应用」
        if (rows > 0 && historyBox.getChildAt(0).isFocusable()) {
            input.setNextFocusDownId(historyBox.getChildAt(0).getId());
            // 反向：历史第一条「上」回输入框。不写的话焦点搜索可能落在包着列表的
            // ScrollView 上（它自己也可聚焦），用户会看到一个框在列表边框上
            historyBox.getChildAt(0).setNextFocusUpId(R.id.dialog_input);
        } else {
            input.setNextFocusDownId(R.id.dialog_btn_apply);
        }
        // 历史最后一条「下」：直接到「应用」，不用在列表里继续按
        if (lastFocusableRow != null) {
            lastFocusableRow.setNextFocusDownId(R.id.dialog_btn_apply);
        }
        // 按钮「上」：回到历史最后一条（没有历史就回输入框），而不是一步跳回输入框——
        // 否则用户在列表和按钮之间来回时会反复弹起输入法
        int upTarget = lastFocusableRow != null ? lastFocusableRow.getId() : R.id.dialog_input;
        findViewById(R.id.dialog_btn_clear).setNextFocusUpId(upTarget);
        findViewById(R.id.dialog_btn_cancel).setNextFocusUpId(upTarget);
        findViewById(R.id.dialog_btn_apply).setNextFocusUpId(upTarget);
    }

    /**
     * 输入法的弹出与收起跟着焦点走。
     *
     * <p>不在弹窗打开时就弹输入法：那样会把历史列表整个盖住，而多数时候用户是来挑一个用过的源、
     * 根本不是来打字的。只有焦点真的落到输入框上（用户按了「上」或者从空历史进来的）才弹。
     */
    private void wireIme() {
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    showIme();
                } else {
                    hideIme();
                }
            }
        });
        // 遥控器/输入法的「完成」键等同于点「应用」，省一次方向键
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

        // 上下键必须在这里截走。
        //
        // EditText 自己会消费方向键——单行输入框里「上/下」在它看来是「光标移到上/下一行」，
        // 于是按键在 View.dispatchKeyEvent 阶段就被吃掉了，根本轮不到系统的焦点搜索，
        // 我们给输入框设的 nextFocusDownId 永远没人问。表现就是：遥控器按「下」，
        // 焦点卡在输入框里出不来，用户填完地址够不到「应用」。
        // （TVBox 原版的配置地址弹窗也不走方向键，它靠 IME 的 action 在两个输入框之间跳。）
        //
        // 单行输入框里上下键没有任何编辑含义，截走是安全的；左右键要留给光标，不能碰。
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        moveFocusDown();
                    }
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // 上面没有别的可聚焦控件，吃掉即可，别让焦点跑到弹窗外面去
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // 遥控器的「确定」在 EditText 上不会触发 onEditorAction（那是 IME 的活），
                    // 所以要自己接一下，跟点「应用」等价
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        submit();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    /** 把焦点从输入框交出去。目标由 {@link #wireFocus} 设的 nextFocusDownId 决定 */
    private void moveFocusDown() {
        View target = input.focusSearch(View.FOCUS_DOWN);
        if (target == null || target == input) {
            target = applyButton;
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    /**
     * 弹输入法。
     *
     * <p>真机上踩过两次，两个坑叠在一起，最后表现是「焦点明明在输入框上，键盘死活不出来，
     * 但按一下字母键它又出来了」：
     * <ol>
     *   <li><b>标志用了 {@code SHOW_IMPLICIT}。</b>那个标志的含义就是「这是个隐式请求，
     *       窗口<b>不一定</b>显示」——系统照着字面意思把它丢了。要用 0（显式请求）。</li>
     *   <li><b>调得太早。</b>{@code showSoftInput} 要求目标 View 已经是 IME 当前服务的那个
     *       （{@code InputMethodManager} 内部比对 {@code mServedView}），
     *       而焦点切过去的那一刻还没切完；弹窗窗口本身也要先拿到焦点。
     *       所以这里 post 一次，让消息循环把这两件事办完再叫。</li>
     * </ol>
     * 这两条都不会抛异常、也不打日志，只表现为「什么都没发生」，所以写在注释里备查。
     */
    private void showIme() {
        if (input == null) {
            return;
        }
        input.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || input == null || !input.isFocused()) {
                    return;
                }
                InputMethodManager imm = (InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, 0);
                }
            }
        });
    }

    /**
     * 弹窗窗口自己拿到焦点时再叫一次输入法。
     *
     * <p>弹窗刚 {@code show()} 出来的瞬间，这个窗口还没有窗口焦点，那会儿的请求一样会被丢。
     * 初始焦点落在输入框上（历史为空时就是这样）的情况下，只有走到这里才弹得出来。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && input != null && input.isFocused()) {
            showIme();
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
        findViewById(R.id.dialog_btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (actionSent) {
                    return;
                }
                actionSent = true;
                hideIme();
                if (listener != null) {
                    listener.onClear();
                }
                dismiss();
            }
        });
    }

    private void submit() {
        // 「确定」可能同时从两条路进来（IME 的 actionDone 和上面的按键监听），
        // 已经发出动作就不再发第二次，免得同一个地址被应用两遍
        if (actionSent) {
            return;
        }
        String url = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            // 空地址不算「清空」——清空有它自己的按钮，是个明确得多的动作。
            // 这里只是没填完，留在弹窗里继续填。
            Toast.makeText(getContext(), R.string.settings_toast_empty_url, Toast.LENGTH_SHORT).show();
            input.requestFocus();
            return;
        }
        hideIme();
        actionSent = true;
        if (listener != null) {
            listener.onApply(url);
        }
        dismiss();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && input != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }
}

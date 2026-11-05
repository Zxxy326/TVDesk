package com.tvdesk.desk;

import com.tvdesk.core.ui.DeskToast;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ImgUtil;
import com.tvdesk.core.feed.DetailFeed;
import com.tvdesk.desk.motion.FocusScaler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内嵌搜索页。
 *
 * <p>输入有两条路：遥控器用左边的屏幕键盘（A-Z/0-9），中文则把焦点移到片名框，
 * 用系统输入法直接打字——电视上的输入法通常带拼音，屏幕键盘只解决英文和数字。
 * 两条路写的是同一个 EditText，所以结果和普通搜索框完全一致。
 *
 * <p>结果按源陆续回来（{@link DetailFeed#search} 每拿到一个源就回一批），
 * 不等最慢的源。点结果进内嵌详情页。
 */
public class SearchPage extends FrameLayout implements ModulePage {

    private static final String TAG = "SearchPage";

    public interface Host {
        void openVideo(Movie.Video vod);
    }

    /** 输入停顿这么久再自动搜，避免每按一个字母都打一遍所有源 */
    private static final long LIVE_SEARCH_DELAY = 700L;

    private final Host host;

    private EditText input;
    private ImageView goButton;
    private LinearLayout keys;
    private com.tvdesk.desk.widget.TileWall grid;
    private ProgressBar loading;
    private TextView emptyView;
    private View historyBox;
    private LinearLayout historyWords;

    private final List<Movie.Video> results = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    /** 最近点开的结果位置，从详情页退回来时把焦点还给它 */
    private int openedIndex = -1;
    private final ResultAdapter adapter = new ResultAdapter();
    private final Handler MAIN = new Handler(Looper.getMainLooper());

    private int seq;
    private boolean released;
    private boolean pendingFocusResults;

    private final Runnable liveSearch = new Runnable() {
        @Override
        public void run() {
            String wd = query();
            if (wd.length() >= 2) {
                // 打字过程中的自动搜索：别收输入法（用户还在拼），也别抢焦点
                searchNow(false, true);
            }
        }
    };

    public SearchPage(Context context, Host host) {
        super(context);
        this.host = host;
        LayoutInflater.from(context).inflate(R.layout.page_search, this, true);

        input = findViewById(R.id.search_input);
        goButton = findViewById(R.id.search_go);
        keys = findViewById(R.id.search_keys);
        grid = findViewById(R.id.search_grid);
        loading = findViewById(R.id.search_loading);
        emptyView = findViewById(R.id.search_empty);
        historyBox = findViewById(R.id.search_history);
        historyWords = findViewById(R.id.search_history_words);

        grid.setAdapter(adapter);
        grid.setNumColumns(GRID_COLUMNS);
        grid.setSpacingDp(16f, 18f);
        // 到边：左边是字母键盘（用户按左就是要回键盘），上下到顶回输入框，其余回弹
        grid.setEdgeListener(new com.tvdesk.desk.widget.TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusFirstKey();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    input.requestFocus();
                    return true;
                }
                return false;
            }
        });
        buildKeyboard();
        wireInput();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        // 从详情页退回来时，焦点还落在刚才打开的那张卡上，而不是重新从键盘开始
        if (openedIndex >= 0 && openedIndex < results.size()
                && grid.getVisibility() == View.VISIBLE) {
            focusResult(openedIndex);
            return;
        }
        if (query().isEmpty()) {
            showHistory();
        }
        focusInput();
    }

    /**
     * 进页面时焦点落在片名框里。
     *
     * <p>用户进搜索页要做的第一件事就是输入，焦点先站在字母键盘上等于让他先挪一次手。
     * 但电视上焦点落到输入框会顺手把系统输入法叫出来，把下半屏盖掉 —— 这里的默认输入
     * 方式是左边那套字母键盘，所以落焦之后要把输入法收回去（{@link #hideIme()}）。
     */
    private void focusInput() {
        // 页面刚塞进宿主、还没量完布局时 requestFocus 会落空，
        // 随后系统按「第一个可聚焦」把焦点给到键盘的 A —— 那正是要避免的结果。
        // 所以等这一轮布局结束再要焦点。
        if (input.getViewTreeObserver().isAlive()) {
            input.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            input.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            grabInputFocus();
                        }
                    });
        }
        postDelayed(new Runnable() {
            @Override
            public void run() {
                grabInputFocus();
            }
        }, 220);
        // 输入法是「焦点变了之后」才弹的，一次 hide 往往赶在它前面
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!gone() && input.hasFocus()) {
                    hideIme();
                }
            }
        }, 420);
    }

    private void grabInputFocus() {
        if (gone() || input.hasFocus()) {
            return;
        }
        input.requestFocus();
        Editable text = input.getText();
        if (text != null) {
            input.setSelection(text.length());
        }
        hideIme();
    }

    /**
     * 带关键词进来（详情页的「搜索片源」、桌面点只有片名的条目）。
     *
     * @param searchImmediately true 表示进来就搜，不用用户再按一次「搜索」
     */
    public void prefill(String keyword, boolean searchImmediately) {
        if (TextUtils.isEmpty(keyword)) {
            return;
        }
        input.setText(keyword);
        input.setSelection(input.getText().length());
        if (searchImmediately) {
            searchNow(true);
        }
    }

    @Override
    public void onRelease() {
        released = true;
        seq++;
        MAIN.removeCallbacks(liveSearch);
        DetailFeed.cancelSearch();
        hideIme();
    }

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BACK && input.hasFocus()) {
            // 层级表 L4：输入法弹着的时候，第一次返回只把它收起来（焦点退回键盘，
            // 再按一次才是退出本页）；输入法没弹（用左边屏幕键盘打的字），这一下就该退页了。
            if (imeActive()) {
                hideIme();
                focusFirstKey();
                return Back.consume("影视搜索·收起输入法");
            }
            return Back.pass("影视搜索·退出搜索页");
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 焦点还在结果墙／最近搜索里：返回先回「顶栏」（片名框 + 键盘这一块），
            // 再按一次才退出搜索页。和其它模块「内容 → 顶栏」是同一个口径。
            //
            // 落点选键盘而不是片名框：焦点一进 EditText，系统输入法会自己再弹出来，
            // 下一次返回又会被判成「还在输入态」，用户得按三次才出得去。
            if (grid.findFocus() != null || historyWords.findFocus() != null) {
                hideIme();
                focusFirstKey();
                return Back.consume("影视搜索·回搜索栏");
            }
            return Back.pass("影视搜索·退出搜索页");
        }
        return false;
    }

    /** 输入法当前是不是正为片名框弹着 */
    private boolean imeActive() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        return imm != null && input != null && imm.isActive(input);
    }

    private boolean gone() {
        Context c = getContext();
        return released || !(c instanceof Activity)
                || ((Activity) c).isFinishing() || ((Activity) c).isDestroyed();
    }

    // ---------------------------------------------------------------- 输入

    private void wireInput() {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                MAIN.removeCallbacks(liveSearch);
                if (s.length() >= 2) {
                    MAIN.postDelayed(liveSearch, LIVE_SEARCH_DELAY);
                }
            }
        });

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_UP)) {
                    hideIme();
                    searchNow(true);
                    return true;
                }
                return false;
            }
        });

        // EditText 会自己吃掉上下键（当成光标移动），焦点出不去。单行输入框里
        // 上下键没有编辑含义，截走是安全的；左右键要留给光标
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        hideIme();
                        focusFirstKey();
                    }
                    return true;
                }
                return false;
            }
        });

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchNow(true);
            }
        });
        FocusScaler.attach(goButton, 1.06f);
    }

    private void buildKeyboard() {
        String[][] rows = {
                {"A", "B", "C", "D", "E", "F"},
                {"G", "H", "I", "J", "K", "L"},
                {"M", "N", "O", "P", "Q", "R"},
                {"S", "T", "U", "V", "W", "X"},
                {"Y", "Z", "0", "1", "2", "3"},
                {"4", "5", "6", "7", "8", "9"},
        };
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineParams.bottomMargin = dp(5);
            line.setLayoutParams(lineParams);
            for (String key : row) {
                line.addView(buildKey(key));
            }
            keys.addView(line);
        }

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(buildAction("删除", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteLast();
            }
        }));
        actions.addView(buildAction("清空", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                emptyView.setVisibility(View.GONE);
                showHistory();
                focusFirstKey();
            }
        }));
        actions.addView(buildAction("搜索", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchNow(true);
            }
        }));
        keys.addView(actions);
    }

    private TextView buildKey(final String text) {
        TextView key = new TextView(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lp.rightMargin = dp(8);
        key.setLayoutParams(lp);
        key.setBackgroundResource(R.drawable.bg_dialog_item);
        key.setFocusable(true);
        key.setFocusableInTouchMode(true);
        key.setClickable(true);
        key.setGravity(Gravity.CENTER);
        key.setText(text);
        key.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        key.setTextSize(15);
        key.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                append(text);
            }
        });
        return key;
    }

    private TextView buildAction(String text, boolean primary, View.OnClickListener listener) {
        TextView button = new TextView(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_pill);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setGravity(Gravity.CENTER);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(primary
                ? getResources().getColor(R.color.text_on_light)
                : getResources().getColor(R.color.text_primary));
        button.setOnClickListener(listener);
        return button;
    }

    private void append(String text) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        input.getText().replace(start, end, text);
        input.setSelection(start + text.length());
    }

    private void deleteLast() {
        Editable editable = input.getText();
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        if (start != end) {
            editable.delete(Math.min(start, end), Math.max(start, end));
            return;
        }
        if (start > 0) {
            editable.delete(start - 1, start);
        }
    }

    // ---------------------------------------------------------------- 搜索历史

    /**
     * 把「最近搜索」摆出来。
     *
     * <p>电视上用遥控器打字很贵，搜过的片名应该一点就回来 ——
     * TVBox 的搜索页也是先给最近几个词。
     */
    private void showHistory() {
        if (historyBox == null || historyWords == null) {
            return;
        }
        List<String> words = SearchHistory.list(getContext());
        historyWords.removeAllViews();
        if (words.isEmpty()) {
            historyBox.setVisibility(View.GONE);
            return;
        }
        // 两列、最多 6 条：键盘占掉大半列，再多就会画到下面的提示文字上。
        // 「仅文字」那一版没有焦点框，遥控器走上去看不出停在哪，所以这里给回
        // 带底的条目 + 放大，跟键盘按键是同一套视觉语言。
        int count = Math.min(words.size(), 6);
        LinearLayout line = null;
        for (int i = 0; i < count; i++) {
            final String word = words.get(i);
            if (i % 2 == 0) {
                line = new LinearLayout(getContext());
                line.setOrientation(LinearLayout.HORIZONTAL);
                // 这一行是「宽度刚好等于两个条目」的容器：默认 clipChildren=true 时，
                // 条目按 1.06 放大出来的那一圈会被这一行的边界切掉，
                // 看到的就是焦点框左右两侧被裁平（用户报的正是这个）
                line.setClipChildren(false);
                line.setClipToPadding(false);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lineParams.bottomMargin = dp(6);
                line.setLayoutParams(lineParams);
                historyWords.addView(line);
            }
            TextView item = new TextView(getContext());
            item.setText(word);
            item.setTextSize(14);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
            item.setBackgroundResource(R.drawable.bg_module_item);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClickable(true);
            item.setPadding(dp(12), 0, dp(12), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i % 2 == 0) {
                lp.rightMargin = dp(6);
            }
            item.setLayoutParams(lp);
            com.tvdesk.desk.motion.FocusScaler.attach(item,
                    com.tvdesk.desk.motion.FocusScaler.SCALE_FOCUSED);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    input.setText(word);
                    input.setSelection(word.length());
                    searchNow(true);
                }
            });
            if (line != null) {
                line.addView(item);
            }
        }
        historyBox.setVisibility(View.VISIBLE);
    }

    private void hideHistory() {
        if (historyBox != null) {
            historyBox.setVisibility(View.GONE);
        }
    }

    private String query() {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    /**
     * 同步把焦点放回键盘第一个字母。
     *
     * <p>为什么要有「同步」这一版：清空结果、隐藏结果墙之前必须先把焦点挪走。
     * {@link #focusFirstKey()} 是 post 出去的（布局还没铺好时也能用），
     * 但清空的瞬间焦点已经没了，中间那两帧焦点会被系统补到片名框上。
     */
    private boolean moveFocusToKeyboard() {
        if (keys.getChildCount() == 0) {
            return false;
        }
        View row = keys.getChildAt(0);
        View first = row instanceof ViewGroup ? ((ViewGroup) row).getChildAt(0) : row;
        return first != null && first.requestFocus();
    }

    private void focusFirstKey() {
        post(new Runnable() {
            @Override
            public void run() {
                if (gone()) {
                    return;
                }
                moveFocusToKeyboard();
            }
        });
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && input != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- 搜索

    private void searchNow(boolean focusResults) {
        searchNow(focusResults, false);
    }

    /**
     * 发起一次搜索。
     *
     * @param focusResults 结果回来后把焦点送进结果墙（只有用户亲手按「搜索」才这么做）
     * @param fromTyping   由输入停顿触发的自动搜索：此时焦点在片名框/键盘上，
     *                     既不能收输入法，也不能动焦点
     */
    private void searchNow(boolean focusResults, boolean fromTyping) {
        MAIN.removeCallbacks(liveSearch);
        String wd = query();
        if (TextUtils.isEmpty(wd)) {
            DeskToast.makeText(getContext(), "先输入片名", Toast.LENGTH_SHORT).show();
            focusFirstKey();
            return;
        }
        if (!fromTyping) {
            hideIme();
        }
        hideHistory();
        SearchHistory.record(getContext(), wd);
        final int mySeq = ++seq;
        pendingFocusResults = focusResults;
        // 结果墙要清空重来。焦点若正在里面，先把焦点**明确地**送回键盘再清 ——
        // 直接清空/隐藏会让系统在子 View 消失后按几何关系补一个，
        // 用户看到的就是「搜一下，焦点自己跑到片名框去了」。
        if (grid.findFocus() != null) {
            moveFocusToKeyboard();
        }
        results.clear();
        seen.clear();
        openedIndex = -1;
        grid.notifyDataSetChanged();
        loading.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        grid.setVisibility(View.INVISIBLE);

        DetailFeed.search(wd, new DetailFeed.SearchCallback() {
            @Override
            public void onBatch(String sourceKey, String sourceName, List<Movie.Video> videos) {
                if (mySeq != seq || gone()) {
                    return;
                }
                for (Movie.Video vod : videos) {
                    String key = sourceKey + "|" + vod.id;
                    if (seen.add(key)) {
                        results.add(vod);
                    }
                }
                if (results.isEmpty()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                emptyView.setVisibility(View.GONE);
                grid.setVisibility(View.VISIBLE);
                grid.notifyDataSetChanged();
            }

            @Override
            public void onDone() {
                if (mySeq != seq || gone()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                android.util.Log.i(TAG, "搜索「" + wd + "」结束，共 " + results.size() + " 条");
                if (results.isEmpty()) {
                    // 纯字母多半是在用拼音首字母。这批源（catvod 爬虫包的各站）
                    // 站内搜索只认中文关键词，拼音发过去必然是空的 —— 说清楚，
                    // 否则用户会以为「搜索坏了」。
                    boolean lettersOnly = wd.matches("^[A-Za-z0-9]+$");
                    emptyView.setText(lettersOnly
                            ? "没找到「" + wd + "」\n当前这批源不支持拼音首字母搜索\n换个源，或把焦点移到片名框用输入法打中文"
                            : "没找到「" + wd + "」\n换个片名，或到设置里加个搜索源");
                    if (grid.findFocus() != null) {
                        moveFocusToKeyboard();
                    }
                    // 这里不再把「最近搜索」摊出来：片名框里还有关键词的时候，
                    // 右边突然多出一列可聚焦的历史词，用户从键盘往右一按就落到那上面，
                    // 看着就是「焦点乱跳、结果区进不去」。历史只在片名为空时出现。
                    emptyView.setVisibility(View.VISIBLE);
                    grid.setVisibility(View.INVISIBLE);
                    return;
                }
                grid.setVisibility(View.VISIBLE);
                if (pendingFocusResults) {
                    pendingFocusResults = false;
                    focusResult(0);
                }
            }
        });
    }

    private void focusResult(final int index) {
        if (results.isEmpty()) {
            return;
        }
        int target = Math.max(0, Math.min(index, results.size() - 1));
        grid.focusPosition(target);
    }

    // ---------------------------------------------------------------- 结果

    /** 搜索结果每行 4 张（沿用原来 GridView 的排版） */
    private static final int GRID_COLUMNS = 5;

    private final class ResultAdapter implements com.tvdesk.desk.widget.TileWall.Adapter {
        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public String keyAt(int index) {
            if (index < 0 || index >= results.size()) {
                return null;
            }
            Movie.Video vod = results.get(index);
            if (!TextUtils.isEmpty(vod.id)) {
                return "v:" + vod.sourceKey + "|" + vod.id;
            }
            return "n:" + vod.name;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            View root = convertView != null ? convertView
                    : LayoutInflater.from(getContext())
                    .inflate(R.layout.item_search_poster, parent, false);
            if (root == null) {
                return new View(getContext());
            }
            Movie.Video vod = results.get(position);
            ImageView img = root.findViewById(R.id.poster_img);
            TextView name = root.findViewById(R.id.poster_name);
            TextView note = root.findViewById(R.id.poster_note);
            name.setText(vod.name);
            // 每条结果都标出来自哪个源。同一部片会在好几个站各有一条（就像 TVBox），
            // 以前只有「备注为空」时才显示源名，而网盘源几乎都带「4K正式版」这类备注，
            // 于是一屏卡片看起来像同一个站刷出来的，用户以为没聚合。
            String src = sourceName(vod.sourceKey);
            String noteText = vod.note == null ? "" : vod.note.trim();
            if (!TextUtils.isEmpty(src) && !src.equals(noteText)) {
                noteText = noteText.isEmpty() ? src : src + " · " + noteText;
            }
            note.setText(noteText);
            note.setVisibility(TextUtils.isEmpty(noteText) ? View.GONE : View.VISIBLE);
            loadImage(img, vod.pic);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius));

            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            FocusScaler.attach(root, FocusScaler.SCALE_FOCUSED);
            root.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (host != null && position < results.size()) {
                        openedIndex = position;
                        host.openVideo(results.get(position));
                    }
                }
            });
            return root;
        }
    }

    private String sourceName(String sourceKey) {
        try {
            com.github.tvbox.osc.bean.SourceBean bean =
                    com.github.tvbox.osc.api.ApiConfig.get().getSource(sourceKey);
            if (bean != null && !TextUtils.isEmpty(bean.getName())) {
                return bean.getName();
            }
        } catch (Throwable ignored) {
        }
        return sourceKey == null ? "" : sourceKey;
    }

    private void loadImage(ImageView target, String url) {
        if (target == null || TextUtils.isEmpty(url)) {
            return;
        }
        try {
            Object model = ImgUtil.getImageModel(url);
            if (model == null) {
                return;
            }
            Drawable fallback = AppCompatResources.getDrawable(getContext(), R.drawable.bg_poster);
            if (fallback == null) {
                Glide.with(getContext()).load(model).centerCrop().into(target);
            } else {
                Glide.with(getContext()).load(model)
                        .placeholder(fallback)
                        .error(fallback)
                        .centerCrop()
                        .into(target);
            }
        } catch (Throwable th) {
            android.util.Log.w(TAG, "load image failed: " + url, th);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

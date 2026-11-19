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
 * <p>输入走<b>系统输入法</b>：进页面自动把输入法弹起来，不再自绘屏幕键盘。
 * 电视上的输入法一般带拼音和语音，比遥控器逐个字母挪快得多；「最近搜索」退到
 * 输入框下面一行，搜过的词一点就回来。
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

    /**
     * 输入法此刻是不是「我们主动弹起来」的状态。
     *
     * <p>不能用 {@code imm.isActive(input)} 判断：键盘收起来之后它照样返回 true
     * （输入连接还在，只是不可见），返回键就会一直走「收输入法」那一支，
     * 用户按多少下都退不出搜索页。所以自己记一个开关，show/hide 时同步。
     */
    private boolean imeShown;

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
        grid = findViewById(R.id.search_grid);
        loading = findViewById(R.id.search_loading);
        emptyView = findViewById(R.id.search_empty);
        historyBox = findViewById(R.id.search_history);
        historyWords = findViewById(R.id.search_history_words);

        grid.setAdapter(adapter);
        grid.setNumColumns(GRID_COLUMNS);
        grid.setSpacingDp(16f, 18f);
        // 到边：上/左都回输入框（没有键盘列了，回输入框就是回「输入区」）
        grid.setEdgeListener(new com.tvdesk.desk.widget.TileWall.OnEdgeListener() {
            @Override
            public boolean onEdge(int keyCode, int index) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    focusInput();
                    return true;
                }
                return false;
            }
        });
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
     * 进页面 / 从结果区回输入框：聚焦片名框并把系统输入法弹起来。
     *
     * <p>页面里已经没有自绘键盘了，输入法就是唯一的输入方式 —— 所以弹输入法是主路径，
     * 不再是「顺手带出来、随后收回去」的副作用。
     */
    private void focusInput() {
        // 页面刚塞进宿主、还没量完布局时 requestFocus 会落空，等这一轮布局结束再要焦点
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
        // 输入法是「焦点落定之后」才弹的，补一次，赶在系统第一次弹失败时兜住
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!gone() && input.hasFocus()) {
                    showIme();
                }
            }
        }, 420);
    }

    private void grabInputFocus() {
        if (gone()) {
            return;
        }
        if (!input.hasFocus()) {
            input.requestFocus();
            Editable text = input.getText();
            if (text != null) {
                input.setSelection(text.length());
            }
        }
        showIme();
    }

    /** 弹系统输入法（进搜索页、从结果区回输入框都走这里） */
    private void showIme() {
        if (input == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            imeShown = true;
        }
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
        if (keyCode != KeyEvent.KEYCODE_BACK) {
            return false;
        }
        // 层级表 L4：输入法弹着的时候，第一次返回只把它收起来；收起后这一下
        // 不该同时退页，否则用户想收个键盘就直接被弹出去了。
        //
        // 焦点在结果墙里时没有「中间态」可回：回片名框必然把输入法弹回来，
        // 下一次返回又变成「收输入法」，要按三次才出得去。所以这里直接退页，
        // 想改词按「上」回输入框。
        // 还要再量一次「键盘是不是真的还在」：很多输入法会自己吃掉第一次返回
        // （它自己把键盘收起来），我们这边的 onKeyDown 根本收不到，标志位却还是 true。
        // 只看标志位的话，用户按第二下会被当成「再收一次键盘」，要按三下才退得出去。
        if (imeShown && imeVisibleNow()) {
            hideIme();
            return Back.consume("影视搜索·收起输入法");
        }
        hideIme();
        return Back.pass("影视搜索·退出搜索页");
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
                        // 没有键盘列了：向下就是进内容区 —— 有结果进结果墙，
                        // 没结果就去「最近搜索」，两样都没有就留在片名框
                        hideIme();
                        if (grid.getVisibility() == View.VISIBLE && !results.isEmpty()) {
                            focusResult(0);
                        } else if (historyBox != null && historyBox.getVisibility() == View.VISIBLE
                                && historyWords != null && historyWords.getChildCount() > 0) {
                            historyWords.getChildAt(0).requestFocus();
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        // 焦点一回到片名框，系统就会把输入法弹出来（不管是不是我们叫的）。
        // 这里补记一笔，否则下一次返回会被判成「键盘没开」直接退页，把键盘留在屏幕上。
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    imeShown = true;
                }
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
        // 一行最多 6 条，横向均分：整屏都给了结果墙，历史只在输入框下面占一条。
        int count = Math.min(words.size(), 6);
        for (int i = 0; i < count; i++) {
            final String word = words.get(i);
            TextView item = new TextView(getContext());
            item.setText(word);
            item.setTextSize(13);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setGravity(Gravity.CENTER);
            item.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
            item.setBackgroundResource(R.drawable.bg_module_item);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClickable(true);
            item.setPadding(dp(10), 0, dp(10), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i < count - 1) {
                lp.rightMargin = dp(8);
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
            historyWords.addView(item);
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
     * 同步把焦点收回片名框（不主动弹输入法）。
     *
     * <p>「同步」是为了在清空结果、隐藏结果墙之前先把焦点挪走：清空的瞬间
     * 焦点已经没了，中间那两帧系统会按几何关系自己补一个落点，
     * 用户看到的就是「搜一下，焦点自己乱跑」。
     *
     * <p>不调 {@link #showIme()}：搜索时键盘盖住结果没有任何意义，
     * 调用方那边的 {@link #hideIme()} 会把它收掉。
     */
    private boolean moveFocusToInput() {
        return input != null && input.requestFocus();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && input != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        imeShown = false;
    }

    /**
     * 软键盘此刻是不是真的占着屏幕下半截。
     *
     * <p>{@code InputMethodManager.isActive} 不可靠（键盘收起后照样 true），
     * 所以直接量可见区域：键盘弹着时窗口可视区会被压短。
     */
    private boolean imeVisibleNow() {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            getWindowVisibleDisplayFrame(r);
            int rootH = getRootView().getHeight();
            return rootH > 0 && (rootH - r.bottom) > dp(100);
        } catch (Throwable th) {
            return false;
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
            focusInput();
            return;
        }
        hideHistory();
        SearchHistory.record(getContext(), wd);
        final int mySeq = ++seq;
        pendingFocusResults = focusResults;
        // 结果墙要清空重来。焦点若正在里面，先把焦点**明确地**收回片名框再清 ——
        // 直接清空/隐藏会让系统在子 View 消失后按几何关系补一个落点。
        //
        // 顺序必须是「先移焦点、后收输入法」：EditText 拿到焦点会顺手把输入法弹起来，
        // 先收的话下一步又被弹回来，键盘就盖在刚搜出来的结果上了。
        if (grid.findFocus() != null) {
            moveFocusToInput();
        }
        if (!fromTyping) {
            hideIme();
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
                            ? "没找到「" + wd + "」\n当前这批源不支持拼音首字母搜索\n换个源，或用输入法打中文片名"
                            : "没找到「" + wd + "」\n换个片名，或到设置里加个搜索源");
                    if (grid.findFocus() != null) {
                        moveFocusToInput();
                    }
                    // 这里不再把「最近搜索」摊出来：片名框里还有关键词的时候，
                    // 结果区上方突然多出一排可聚焦的历史词，用户往下走就容易落到那上面，
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

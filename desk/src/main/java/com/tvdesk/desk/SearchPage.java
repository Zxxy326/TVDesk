package com.tvdesk.desk;

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
    private GridView grid;
    private ProgressBar loading;
    private TextView emptyView;

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
                searchNow(false);
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

        grid.setAdapter(adapter);
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
        focusFirstKey();
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
            // 层级表 L4：第一次返回只收输入法/离开输入框，不直接退页
            hideIme();
            focusFirstKey();
            return Back.consume("影视搜索·收起输入法");
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return Back.pass("影视搜索·退出搜索页");
        }
        return false;
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
            lineParams.bottomMargin = dp(8);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.rightMargin = dp(8);
        key.setLayoutParams(lp);
        key.setBackgroundResource(R.drawable.bg_dialog_item);
        key.setFocusable(true);
        key.setFocusableInTouchMode(true);
        key.setClickable(true);
        key.setGravity(Gravity.CENTER);
        key.setText(text);
        key.setTextColor(AppCompatResources.getColorStateList(getContext(), R.color.nav_item_text));
        key.setTextSize(16);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
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

    private String query() {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void focusFirstKey() {
        post(new Runnable() {
            @Override
            public void run() {
                if (gone()) {
                    return;
                }
                if (keys.getChildCount() == 0) {
                    return;
                }
                View row = keys.getChildAt(0);
                View first = row instanceof ViewGroup ? ((ViewGroup) row).getChildAt(0) : row;
                if (first != null) {
                    first.requestFocus();
                }
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
        MAIN.removeCallbacks(liveSearch);
        String wd = query();
        if (TextUtils.isEmpty(wd)) {
            Toast.makeText(getContext(), "先输入片名", Toast.LENGTH_SHORT).show();
            focusFirstKey();
            return;
        }
        hideIme();
        final int mySeq = ++seq;
        pendingFocusResults = focusResults;
        results.clear();
        seen.clear();
        openedIndex = -1;
        adapter.notifyDataSetChanged();
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
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onDone() {
                if (mySeq != seq || gone()) {
                    return;
                }
                loading.setVisibility(View.GONE);
                if (results.isEmpty()) {
                    emptyView.setText("没找到「" + wd + "」\n换个片名，或到设置里加个搜索源");
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
        final int target = Math.max(0, Math.min(index, results.size() - 1));
        grid.setSelection(target);
        grid.post(new Runnable() {
            @Override
            public void run() {
                View child = grid.getChildAt(target - grid.getFirstVisiblePosition());
                if (child != null) {
                    child.requestFocus();
                }
            }
        });
    }

    // ---------------------------------------------------------------- 结果

    private final class ResultAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public Object getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
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
            String noteText = vod.note != null ? vod.note : "";
            if (TextUtils.isEmpty(noteText)) {
                noteText = sourceName(vod.sourceKey);
            }
            note.setText(noteText);
            note.setVisibility(TextUtils.isEmpty(noteText) ? View.GONE : View.VISIBLE);
            loadImage(img, vod.pic);
            PosterCorners.clip(img,
                    getResources().getDimensionPixelSize(R.dimen.poster_radius));

            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            FocusScaler.attach(root, 1.08f);
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

package com.tvdesk.desk.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词层：解析 LRC 原文，并按播放进度把当前那句顶到中间。
 *
 * <p>调用方只做两件事——{@link #setSource(String)} 丢进接口返回的 LRC 原文，
 * {@link #setPosition(long)} 喂当前毫秒。解析、找当前行、滚动动画都在这里。
 *
 * <p>字号比手机上的歌词控件整体大一档，超宽的句子是缩字号而不是打省略号：
 * 电视是三米外看的，截掉的半句既读不通也补不回来。
 */
public class LyricView extends View {

    /** 一句歌词：它出现的时间点和文本 */
    public static class Line {
        public final long atMs;
        public final String text;

        Line(long atMs, String text) {
            this.atMs = atMs;
            this.text = text;
        }
    }

    /** 时间标签：[00:12.34] 和 [00:12:34] 两种写法在网易云的歌词里都见得到 */
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private List<Line> lines = Collections.emptyList();
    /** 当前行下标；-1 表示还没有歌词 */
    private int index = -1;
    /** 滚动位置，单位是「行」。动画改的是它，画的时候按它排布 */
    private float offset;
    private ValueAnimator scroller;

    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float lineGap;
    private float activeSize;
    private float idleSize;
    private String placeholder = "暂无歌词";

    public LyricView(Context context) {
        this(context, null);
    }

    public LyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        // 行距 56dp：原来 44dp 在电视上是「挤成一坨的几行字」，当前句和上下句之间
        // 需要留出呼吸，用户三米外靠的是「哪行最大最亮」定位，不是逐行读
        lineGap = 56 * density;
        activeSize = 26 * scaledDensity;
        idleSize = 18 * scaledDensity;
        activePaint.setColor(0xFFFFFFFF);
        activePaint.setTextAlign(Paint.Align.CENTER);
        activePaint.setFakeBoldText(true);
        idlePaint.setColor(0xFFB9B9C2);
        idlePaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setColor(0xFF7A7A86);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------- 数据

    /** 换一首歌：整层重来。旧的滚动动画留着会把新歌词从半行处开始滚 */
    public void setSource(String lrc) {
        lines = parse(lrc);
        index = -1;
        offset = 0f;
        stopScroller();
        invalidate();
    }

    public void clearLyric() {
        setSource(null);
    }

    public boolean hasLyric() {
        return !lines.isEmpty();
    }

    /** 没有歌词时显示的那句话（「正在找歌词…」/「暂无歌词」/「这首歌没有官方歌词」） */
    public void setPlaceholder(String text) {
        placeholder = TextUtils.isEmpty(text) ? "暂无歌词" : text;
        invalidate();
    }

    /** 播放进度推进时调用。同一行内反复调用不会重启动画 */
    public void setPosition(long ms) {
        if (lines.isEmpty()) {
            return;
        }
        int target = indexAt(ms);
        if (target == index) {
            return;
        }
        index = target;
        scrollTowards(target);
    }

    private void scrollTowards(int target) {
        stopScroller();
        scroller = ValueAnimator.ofFloat(offset, target);
        scroller.setDuration(420);
        scroller.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        scroller.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                offset = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        scroller.start();
    }

    private void stopScroller() {
        if (scroller != null) {
            scroller.cancel();
            scroller = null;
        }
    }

    /** 最后一句「时间点已经过了」的行。开头那几秒就亮第一句，比整片灰着更像在放歌 */
    private int indexAt(long ms) {
        int low = 0;
        int high = lines.size() - 1;
        int found = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).atMs <= ms) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop();
        float bottom = getHeight() - getPaddingBottom();
        float centerX = (left + right) / 2f;
        float avail = right - left;

        if (lines.isEmpty()) {
            hintPaint.setTextSize(idleSize);
            canvas.drawText(placeholder, centerX,
                    baseline(hintPaint, top + (bottom - top) * 0.42f), hintPaint);
            return;
        }

        // 当前句停在 42% 高度而不是正中间：下面留得多一点，往上滚时能看到后面的词排队进来
        float anchor = top + (bottom - top) * 0.42f;
        for (int i = 0; i < lines.size(); i++) {
            float distance = i - offset;
            float abs = Math.abs(distance);
            if (abs > 5.5f) {
                continue;
            }
            float y = anchor + distance * lineGap;
            if (y < top - lineGap || y > bottom + lineGap) {
                continue;
            }
            Line line = lines.get(i);
            boolean current = abs < 0.5f;
            Paint paint = current ? activePaint : idlePaint;
            float size = current ? activeSize : idleSize;
            paint.setTextSize(size);
            if (!current) {
                // 离当前句越远越淡，最外圈基本看不见，避免上下边缘挤成一片灰
                paint.setAlpha(Math.max(0, Math.round(200 - abs * 34)));
            }
            float width = paint.measureText(line.text);
            if (width > avail && avail > 0) {
                size *= avail / width;
                paint.setTextSize(size);
            }
            canvas.drawText(line.text, centerX, baseline(paint, y), paint);
        }
    }

    /** 让文字在给定垂直中心处上下对称 */
    private static float baseline(Paint paint, float centerY) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return centerY - (fm.ascent + fm.descent) / 2f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopScroller();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- LRC

    /**
     * 解析 LRC 原文。
     *
     * <p>一行可能有多个时间标签（副歌重复时官方就这么写），每个都产出一条；
     * {@code [ti:][ar:][by:]} 这类元信息匹配不上时间格式，天然被跳过，
     * 只有 {@code [offset:]} 要单独处理——它单位是毫秒，官方接口给的值经常差几十毫秒，
     * 不补的话第一句会慢半拍。
     */
    public static List<Line> parse(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        List<Line> out = new ArrayList<>();
        long shift = 0;
        Matcher off = OFFSET.matcher(raw);
        if (off.find()) {
            try {
                shift = Long.parseLong(off.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        String[] rows = raw.split("\n");
        for (String row : rows) {
            Matcher m = TIME.matcher(row);
            List<Long> at = new ArrayList<>();
            while (m.find()) {
                at.add(timeOf(m) + shift);
            }
            if (at.isEmpty()) {
                continue;
            }
            String text = m.reset().replaceAll("").trim();
            if (text.isEmpty()) {
                continue;
            }
            for (Long aLong : at) {
                out.add(new Line(aLong, text));
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return a.atMs < b.atMs ? -1 : (a.atMs == b.atMs ? 0 : 1);
            }
        });
        return out;
    }

    private static long timeOf(Matcher m) {
        long minutes = Long.parseLong(m.group(1));
        long seconds = Long.parseLong(m.group(2));
        long ms = 0;
        String frac = m.group(3);
        if (!TextUtils.isEmpty(frac)) {
            // 两位是百分之一秒，三位是毫秒；再多就按毫秒截断
            long digits = Long.parseLong(frac);
            ms = frac.length() == 2 ? digits * 10 : (digits / (long) Math.pow(10, frac.length() - 3));
        }
        return minutes * 60000 + seconds * 1000 + ms;
    }
}

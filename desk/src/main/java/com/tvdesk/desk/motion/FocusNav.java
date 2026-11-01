package com.tvdesk.desk.motion;

import android.view.KeyEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * 显式焦点路由（一屏二维网格）。
 *
 * <h3>为什么要有这一层</h3>
 * 系统那套是<b>几何寻焦</b>：按一下右键，它在整棵视图树里找「中心点在我右边、距离最近的可聚焦控件」。
 * 这个规则在卡片墙里注定出错 —— 墙外左上角那颗模块胶囊的矩形恰好满足条件，于是第一列再按左，
 * 焦点就飞到屏幕左上角。以前每遇到一次就在页面层拦一次（{@code atHorizontalEdge} /
 * {@code swallowAtEdge} / {@code keepFocusAtEdges}），拦完顶栏又冒出同类问题，永远修不完。
 *
 * <p>这里换成 Aerial 的做法：<b>焦点是数据</b>。
 * 一张卡在第几行第几列、右边还有没有卡，都是算出来的，不用猜：
 * <ul>
 *   <li>左右：下标 ±1，越过行首/行尾就到边（回弹）；</li>
 *   <li>上下：下标 ±列数，越界就到边；</li>
 *   <li>到边交给 {@link Host#onEdge} 决定：吞掉（回弹），或由页面把焦点交给顶栏/别的区。</li>
 * </ul>
 *
 * <h3>为什么按 key 记而不按 View 记</h3>
 * 卡片墙重排（换栏目、换排序、加载下一批）会把子 View 重建一遍，手里那个引用就失效了 ——
 * 以前靠 {@code requestFocus()} 撞运气 + 32ms 重试，中间那几帧焦点已经在左上角闪过了。
 * 这里记的是 key（应用页用包名），重建完 {@code focusKey()} 就能找回同一个位置。
 */
public final class FocusNav {

    /** 方向键到边之后的处置 */
    public static final int EDGE_SWALLOW = 0;
    /** 方向键到边，页面接手（例如往上交给顶栏） */
    public static final int EDGE_HANDLED = 1;

    /** {@link #handleKeyDown} 的结果：这一下已经消费掉 */
    public static final int HANDLED = 0;
    /** {@link #handleKeyDown} 的结果：到边且页面要接管，调用方应该放行 */
    public static final int PASS = 1;

    public interface Host {
        int count();

        int columns();

        /** 第 index 张卡对应的视图；还没挂上（界面还没铺完）返回 null */
        View viewAt(int index);

        /** 第 index 张卡的稳定标识（重建后按它找回焦点）；没有就返回 null */
        String keyAt(int index);

        /** 焦点从 from 移到 to（-1 表示还没有焦点） */
        void onFocusMoved(int from, int to);

        /**
         * 方向键走到了网格边界。
         *
         * @return {@link #EDGE_HANDLED} 表示页面已经接管（焦点交出去了）；
         *         {@link #EDGE_SWALLOW} 表示吃掉这一下（停在原地）
         */
        int onEdge(int keyCode, int index);
    }

    private final Host host;
    private final Map<String, Integer> keys = new HashMap<>();

    private int current = -1;
    /** 上一次聚焦停在哪个 key 上：子视图重建时用它把焦点找回来 */
    private String lastKey;

    public FocusNav(Host host) {
        this.host = host;
    }

    public int currentIndex() {
        return current;
    }

    public String currentKey() {
        return lastKey;
    }

    /** 卡片重建后重新登记 key（调用前请先把 keys 清掉） */
    public void reset() {
        keys.clear();
        for (int i = 0, n = host.count(); i < n; i++) {
            String key = host.keyAt(i);
            if (key != null) {
                keys.put(key, i);
            }
        }
        if (lastKey != null) {
            Integer index = keys.get(lastKey);
            current = index == null ? -1 : index;
        }
    }

    /**
     * 处理一次方向键（只传 ACTION_DOWN）。
     *
     * @return {@link #HANDLED} 这一下已经消费；{@link #PASS} 到边且页面要接管，调用方应放行
     */
    public int handleKeyDown(int keyCode) {
        int columns = Math.max(1, host.columns());
        int count = host.count();
        if (count <= 0) {
            return HANDLED;
        }
        if (current < 0 || current >= count) {
            // 当前没有落点（刚切完栏目/刚重建）：先按 key 找回原位置，找不到落第一张
            if (lastKey != null && focusKey(lastKey)) {
                return HANDLED;
            }
            focusIndex(0);
            return HANDLED;
        }
        int column = current % columns;
        int next;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (column == 0) {
                    return edge(keyCode);
                }
                next = current - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (column == columns - 1 || current == count - 1) {
                    return edge(keyCode);
                }
                next = current + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (current - columns < 0) {
                    return edge(keyCode);
                }
                next = current - columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (current + columns >= count) {
                    return edge(keyCode);
                }
                next = current + columns;
                break;
            default:
                return HANDLED;
        }
        focusIndex(next);
        return HANDLED;
    }

    private int edge(int keyCode) {
        // 不管页面是「把焦点交给顶栏」还是「原地回弹」，这一下都算消费掉：
        // 前者焦点已经转移，再放行会让同一个按键被页面处理第二遍。
        host.onEdge(keyCode, current);
        return HANDLED;
    }

    /** 把焦点放到第 index 张卡上 */
    public boolean focusIndex(int index) {
        int count = host.count();
        if (index < 0 || index >= count) {
            return false;
        }
        View view = host.viewAt(index);
        if (view == null) {
            // 还没挂上（界面正在铺）：先记下来，等铺完由页面调 focusKey 补
            current = index;
            lastKey = host.keyAt(index);
            return false;
        }
        if (!view.requestFocus()) {
            return false;
        }
        int from = current;
        current = index;
        lastKey = host.keyAt(index);
        host.onFocusMoved(from, index);
        return true;
    }

    /** 按 key 找回落点（子视图重建后用） */
    public boolean focusKey(String key) {
        if (key == null) {
            return false;
        }
        Integer index = keys.get(key);
        if (index == null) {
            return false;
        }
        return focusIndex(index);
    }

    /** 记住当前落点（用户自己走出网格时不要清 —— 回来还要用它） */
    public void rememberCurrentKey() {
        if (current >= 0 && current < host.count()) {
            lastKey = host.keyAt(current);
        }
    }

    /** 用户明确离开网格：位置保留，但不再主动抢焦点 */
    public void clearCurrent() {
        current = -1;
    }
}

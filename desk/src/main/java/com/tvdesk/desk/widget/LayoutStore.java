package com.tvdesk.desk.widget;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用顺序的持久化。
 *
 * <h3>为什么需要</h3>
 * 电视上装的应用有几十个，用户真正常用的就那么几个。默认按名称 / 更新时间排，
 * 每次都要重新找。Aerial TV 给了「自己排」的入口（{@code LayoutStore.kt}），
 * 做法很简单也很有效：把用户排好的顺序记下来，下次打开照旧。
 *
 * <h3>怎么存的</h3>
 * <ul>
 *   <li>{@code order} —— 一行一个包名，逗号分隔，就是用户排的顺序；</li>
 *   <li>{@code hidden} —— 用户藏掉的应用（不想在墙上看见）。</li>
 * </ul>
 * 用逗号分隔而不是 {@code StringSet}：集合没有顺序，存下来就乱了。
 *
 * <h3>怎么用</h3>
 * <pre>
 *   LayoutStore store = new LayoutStore(context);
 *   List&lt;Entry&gt; arranged = store.arrange(catalog);   // 按用户顺序排好
 *   store.move(pkg, delta);                             // 上移 / 下移
 * </pre>
 */
public class LayoutStore {

    private static final String PREFS = "app_layout";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";

    private final SharedPreferences prefs;

    public LayoutStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读出用户排好的顺序（包名） */
    public List<String> order() {
        String raw = prefs.getString(KEY_ORDER, "");
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String key : raw.split(",")) {
            key = key.trim();
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return out;
    }

    /** 用户藏掉的应用 */
    public Set<String> hidden() {
        return new LinkedHashSet<>(prefs.getStringSet(KEY_HIDDEN, new LinkedHashSet<String>()));
    }

    /**
     * 把目录按用户顺序排好。
     *
     * <p>三类应用：
     * <ul>
     *   <li>用户排过的 —— 按排的顺序；</li>
     *   <li>新装的（不在用户顺序里）—— 插到用户排的那些<b>前面</b>，
     *       这样新装的应用立刻看得见，不用重新排一遍；</li>
     *   <li>用户藏掉的 —— 不出现。</li>
     * </ul>
     *
     * @param catalog 全部应用（每个元素要能给出包名）
     * @param keyOf   从应用对象取包名
     * @param <T>     应用类型
     */
    public <T> List<T> arrange(List<T> catalog, KeyOf<T> keyOf) {
        List<String> userOrder = order();
        Set<String> hiddenSet = hidden();
        List<T> out = new ArrayList<>();
        // 已经排过的
        for (String key : userOrder) {
            for (T item : catalog) {
                if (keyOf.key(item).equals(key) && !hiddenSet.contains(key)) {
                    out.add(item);
                    break;
                }
            }
        }
        // 新装的：排到前面
        Set<String> placed = new LinkedHashSet<>();
        for (T item : catalog) {
            placed.add(keyOf.key(item));
        }
        List<T> fresh = new ArrayList<>();
        for (T item : catalog) {
            String key = keyOf.key(item);
            if (userOrder.contains(key) || hiddenSet.contains(key)) {
                continue;
            }
            fresh.add(item);
        }
        out.addAll(0, fresh);
        return out;
    }

    /** 取包名的函数（避免为了排序引入泛型接口的额外文件） */
    public interface KeyOf<T> {
        String key(T item);
    }

    /**
     * 把某个应用往前挪 {@code delta} 位（负数往后）。
     *
     * <p>只对「已经排进用户顺序」的应用有效：新装的应用（还没进顺序）先按
     * {@link #arrange} 插到最前面，用户再调它就是在这个基础上的微调。
     */
    public void move(String pkg, int delta) {
        List<String> order = order();
        int at = order.indexOf(pkg);
        if (at < 0 || delta == 0) {
            return;
        }
        int to = at + delta;
        if (to < 0 || to >= order.size()) {
            return;
        }
        order.remove(at);
        order.add(to, pkg);
        save(order);
    }

    /** 把某个应用放到顺序里的第一位 */
    public void moveToTop(String pkg) {
        List<String> order = order();
        order.remove(pkg);
        order.add(0, pkg);
        save(order);
    }

    /** 记下当前顺序 */
    public void save(List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (String key : order) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(key);
        }
        prefs.edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    /** 隐藏 / 取消隐藏一个应用 */
    public void setHidden(String pkg, boolean hide) {
        Set<String> set = new LinkedHashSet<>(hidden());
        if (hide) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply();
    }

    /** 用户排过顺序没有 */
    public boolean hasCustomOrder() {
        return !order().isEmpty();
    }

    /** 清掉自定义，回到默认排序 */
    public void reset() {
        prefs.edit().remove(KEY_ORDER).remove(KEY_HIDDEN).apply();
    }

    /** 把一个列表切成逗号分隔的字符串（调试用） */
    public static String join(List<String> list) {
        return TextUtilsJoin.join(list);
    }

    /** 小工具：避免依赖 android.text.TextUtils 的重载 */
    private static final class TextUtilsJoin {
        static String join(List<String> list) {
            StringBuilder sb = new StringBuilder();
            for (String s : Arrays.asList(list.toArray(new String[0]))) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s);
            }
            return sb.toString();
        }
    }
}

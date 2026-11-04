package com.tvdesk.desk;

import android.view.View;

/**
 * 「焦点在内容区时，返回按视觉层级一层层往上退」的唯一实现。
 *
 * <h3>为什么要有这一层</h3>
 * 电视遥控器上，返回键是用户唯一的「我退出去一点」的手段。以前各页的规矩是
 * 「页里没浮层可收 → 直接把整个模块关掉回桌面」，于是出现最常见的一种懵：
 * 焦点明明还在下面第三排的海报上，一按返回，人已经回到首页了 ——
 * 中间那几层（操作行、顶栏页签）被跨了过去，用户不知道自己在哪，只能再一路找回来。
 *
 * <p>统一成一条：<b>一次返回只往上退一层</b>。层级按视觉从上到下排：
 *
 * <pre>
 *   内容区（卡片墙 / 列表 / 设置卡 / Hero）
 *        ↑ 返回
 *   操作行（播放全部、换一批、刷新、分类筛选…）
 *        ↑ 返回
 *   顶栏页签（本页的页签行）
 *        ↑ 返回
 *   交给宿主：关模块页 / 回桌面
 * </pre>
 *
 * <p>没有操作行、或者操作行当前是隐藏的（例如「播放全部」在没有歌单的栏目里是 GONE），
 * 这一层自动跳过，不会出现「按一下什么都没动」。
 *
 * <h3>怎么用</h3>
 * 各页在自己 {@code handleKeyDown} 的 BACK 分支<b>最后</b>调一次（前面该收的浮层、
 * 子视图、输入态先收完），从里到外把层级列出来：
 *
 * <pre>
 * if (keyCode == KeyEvent.KEYCODE_BACK) {
 *     ...L2/L3/L4 各层...
 *     if (BackTop.climb("音乐",
 *             BackTop.step("回操作行", playAllBtn, focusPlayAll),
 *             BackTop.step("回顶栏", navItems, focusChip))) {
 *         return true;
 *     }
 *     return Back.pass("音乐·页内已无层级");
 * }
 * </pre>
 *
 * <p>只有一个顶栏的页面用 {@link #toTopBar} 就行，它是 {@link #climb} 的两层特例。
 *
 * <p>判断方式刻意写成「焦点<b>已经</b>在哪一层」，而不是「焦点是否在内容区」：
 * 页面里总有层级之外的控件，用「不在这一层就往上走一层」兜住这些边角，
 * 返回键永远不会在用户没准备的时候把整页关掉。
 */
final class BackTop {

    private BackTop() {
    }

    /** 层级链上的一层：容器 + 把焦点放到这一层的动作 */
    static final class Step {
        final String name;
        final View view;
        final Runnable focus;

        private Step(String name, View view, Runnable focus) {
            this.name = name;
            this.view = view;
            this.focus = focus;
        }
    }

    /**
     * 列一层。
     *
     * @param name  写进返回日志的层名（操作行 / 顶栏…）
     * @param view  这一层的容器；用它判断「焦点是不是已经在这一层」
     * @param focus 把焦点放到这一层第一个可聚焦控件上；传 null 表示这一层只参与判定、
     *              不接收焦点（比如左上角「桌面」切换器：它是退出出口，不是可退的中间层）
     */
    static Step step(String name, View view, Runnable focus) {
        return new Step(name, view, focus);
    }

    /**
     * 从里到外一层层往上退：焦点当前在哪一层，就把焦点交给它的<b>上一层</b>。
     *
     * @param page  写进返回日志的页名（和 {@link Back} 里其它层保持一致）
     * @param steps 层级链，顺序必须是「从离内容最近的一层」到「最外层（顶栏）」
     * @return true 表示这次返回被某一层收下了（调用方直接 return true）；
     *         false 表示焦点已经在最外层，该交给宿主关模块页
     */
    static boolean climb(String page, Step... steps) {
        if (steps == null || steps.length == 0) {
            return false;
        }
        // 从外往里找焦点当前在哪一层：先认最外层，免得内外层嵌套时判成里层
        int current = -1;
        for (int i = steps.length - 1; i >= 0; i--) {
            Step step = steps[i];
            if (step != null && step.view != null && step.view.findFocus() != null) {
                current = i;
                break;
            }
        }
        if (current == steps.length - 1) {
            // 已经在最外层（顶栏），这一层没得退
            return false;
        }
        // 往上退：跳过隐藏的层和「只参与判定」的层
        for (int i = current + 1; i < steps.length; i++) {
            Step step = steps[i];
            if (step == null || step.view == null || step.focus == null) {
                continue;
            }
            if (step.view.getVisibility() != View.VISIBLE) {
                continue;
            }
            step.focus.run();
            // 真的落上去了才算收下；落不上（这一层没有可聚焦控件）就继续往上，
            // 绝不能吞掉返回键却什么都不做
            if (step.view.findFocus() != null) {
                return Back.consume(page + "·" + step.name);
            }
        }
        return false;
    }

    /**
     * 只有一层顶栏的页面用它。
     *
     * @param label       写进返回日志的页名
     * @param topBar      本页顶栏容器（页签行／导航行）；隐藏或没有时这一层不生效
     * @param focusTop    把焦点放到顶栏「当前那一项」上的动作
     * @param alsoTopLevel 也算「已经在顶层」的控件（例如左上角切换器），可空
     * @return true 表示这次返回被顶栏这一层收下了
     */
    static boolean toTopBar(String label, View topBar, Runnable focusTop, View... alsoTopLevel) {
        if (topBar == null || focusTop == null || topBar.getVisibility() != View.VISIBLE) {
            return false;
        }
        // 已经在顶栏：这一层没得退，交回上一层（关模块页 / 回桌面）
        if (topBar.findFocus() != null) {
            return false;
        }
        if (alsoTopLevel != null) {
            for (View view : alsoTopLevel) {
                if (view != null && view.findFocus() != null) {
                    return false;
                }
            }
        }
        focusTop.run();
        if (topBar.findFocus() == null) {
            return false;
        }
        return Back.consume(label + "·回顶栏");
    }
}

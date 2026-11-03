package com.tvdesk.desk;

import android.view.View;

/**
 * 「焦点在内容区时，返回先回顶栏」的唯一实现。
 *
 * <h3>为什么要有这一层</h3>
 * 电视遥控器上，返回键是用户唯一的「我退出去一点」的手段。以前各页的规矩是
 * 「页里没浮层可收 → 直接把整个模块关掉回桌面」，于是出现最常见的一种懵：
 * 焦点明明还在下面第三排的海报上，一按返回，人已经回到首页了 ——
 * 中间那层「顶栏页签」被跨了过去，用户不知道自己在哪，只能再一路找回来。
 *
 * <p>统一成一条：<b>焦点不在顶栏，返回只把焦点收回顶栏；焦点已经在顶栏，
 * 这一次返回才交给上一层</b>（关模块页 / 回桌面）。也就是「一按退一层」，
 * 不多不少。
 *
 * <h3>怎么用</h3>
 * 各页在自己 {@code handleKeyDown} 的 BACK 分支<b>最后</b>调一次（前面该收的浮层、
 * 子视图、输入态先收完），顶栏传本页的页签行，{@code focusTop} 传「把焦点放到当前页签」：
 *
 * <pre>
 * if (keyCode == KeyEvent.KEYCODE_BACK) {
 *     ...L2/L3/L4 各层...
 *     if (BackTop.toTopBar("哔哩哔哩", navItems, this::focusChip)) return true;
 *     return Back.pass("哔哩哔哩·页内已无层级");
 * }
 * </pre>
 *
 * <p>判断方式刻意写成「焦点是否<b>已经</b>在顶栏」，而不是「焦点是否在内容区」：
 * 页面里总有顶栏和内容之外的控件（播放全部、搜索框、左上角切换器…），
 * 用「不在顶栏就往顶栏走」兜住这些边角，返回键永远不会在用户没准备的时候把页面关掉。
 */
final class BackTop {

    private BackTop() {
    }

    /**
     * 把焦点从内容区收回顶栏。
     *
     * @param label       写进返回日志的页名（和 {@link Back} 里其它层保持一致）
     * @param topBar      本页顶栏容器（页签行／导航行）；隐藏或没有时这一层不生效
     * @param focusTop    把焦点放到顶栏「当前那一项」上的动作
     * @param alsoTopLevel 也算「已经在顶层」的控件（例如左上角切换器），可空
     * @return true 表示这次返回被顶栏这一层收下了（调用方直接 return true）
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
        return Back.consume(label + "·回顶栏");
    }
}

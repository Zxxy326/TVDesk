package com.tvdesk.desk;

import android.view.KeyEvent;

/**
 * 桌面的内嵌模块页。挂到 DeskActivity 的 module_host 里，
 * 不再起独立 Activity——选到模块栏，内容直接出现在当前界面。
 *
 * <h3>为什么不跳 Activity</h3>
 * 跳转等于换窗口：桌面被压栈、焦点状态全丢、回来还要重建。内嵌成 View 之后，
 * 模块页和桌面共享同一个窗口，切走再切回进度原样还在（音乐甚至可以放着歌不中断）。
 *
 * <h3>返回键的统一层级（全站唯一口径）</h3>
 * 一次返回<b>只退一层</b>，从上往下问，谁在最上面谁先收。各页的
 * {@link #handleKeyDown(int)} 必须按这张表的顺序排自己的分支，
 * 分支名照「层名」写进 {@link Back} 日志，方便用遥控器一按就对：
 *
 * <table border="1">
 *   <tr><th>层</th><th>收什么</th><th>收起后焦点落点</th></tr>
 *   <tr><td>L1 弹层</td><td>Dialog（登录、换源、保持播放…）</td>
 *       <td>Dialog 自己还原；必须是独立窗口，不许做成页内浮层</td></tr>
 *   <tr><td>L2 本页浮层</td><td>胶囊菜单、功能抽屉、播放控制条、展开的进度条</td>
 *       <td>该页的主控件（页签／卡片墙），由收的那一层负责</td></tr>
 *   <tr><td>L3 页内子视图</td><td>钻进去的歌单／收藏夹／分区／历史／沉浸播放页</td>
 *       <td><b>钻进来时那张卡／那一行</b>，不是列表第一行</td></tr>
 *   <tr><td>L4 输入态</td><td>输入法、搜索框里的词</td>
 *       <td>先收输入法，再清词；清完留在搜索框</td></tr>
 *   <tr><td>L5 内容页</td><td>叠在模块页上的详情页／搜索页／播放页（宿主的 pageStack）</td>
 *       <td>由宿主 {@code DeskActivity} 还回打开它之前的控件</td></tr>
 *   <tr><td>L6 模块页</td><td>整个模块页</td>
 *       <td>同上：回到桌面里打开它的那个控件（切换器／Hero／海报）</td></tr>
 *   <tr><td>L7 桌面</td><td>海报墙模式退回首页；其余不退出（桌面是栈底，本 App 还接管 HOME）</td>
 *       <td>首页 Hero</td></tr>
 * </table>
 *
 * <p>两条推论，写代码时最容易漏：
 * <ul>
 *   <li>页签和它下面的列表<b>是同一层</b>，不是两级页面——别在 L3 后面再加一次「先回页签」，
 *       那只会让人以为遥控器失灵；</li>
 *   <li>退层要<b>回到来处</b>：从第 7 张卡钻进去的，退回来还停在第 7 张。
 *       L5/L6 由宿主记账（见 {@code DeskActivity#pageReturnFocus}），L3 各页自己记。</li>
 * </ul>
 *
 * <h3>宿主契约</h3>
 * <ul>
 *   <li>打开：宿主把页 addView 进 module_host、置 VISIBLE，调 {@link #onEnter()}；</li>
 *   <li>切走／退后台：宿主调 {@link #onLeave()}，实例保留不销毁；</li>
 *   <li>按键：宿主 onKeyDown 先给 {@link #handleKeyDown(int)}，返回 false 的 BACK 由宿主关页；</li>
 *   <li>销毁：宿主 onDestroy 调 {@link #onRelease()} 回收播放器/quickjs 这类重资源。</li>
 * </ul>
 */
public interface ModulePage {

    /** 页面被切到前台时调用：申请权限、扫库、把焦点放到默认位置 */
    void onEnter();

    /**
     * 页面被切走（换版块、关页）或整个应用退到后台时调用，实例仍然保留。
     *
     * <p>持有播放器的页要在这里停声——缓存页只是 {@code setVisibility(GONE)}，
     * 看不见却在响的音视频比黑屏更难解释。默认什么都不做：音乐页的「切走继续听」
     * 是设计如此（见类注释），不该被这个钩子顺手掐掉。
     *
     * <p>是否停由用户的{@code 设置 → 离页后续播}决定，具体见各页实现。
     * {@link #onEnter()} 是它的对称出口：进来时该续的续回去。
     */
    default void onLeave() {
    }

    /**
     * 当前是否有声音在放。宿主在「回桌面 / 换模块」前问一次，拿它决定要不要弹
     * 「保持播放」的提示——不然用户按 BACK 回桌面，音乐还在响，却不知道是哪里在响。
     */
    default boolean hasActivePlayback() {
        return false;
    }

    /** 停声（保留页面状态和播放位置，不释放播放器） */
    default void stopPlayback() {
    }

    /**
     * 用户刚在「保持播放」弹窗里选了继续：下一次 {@link #onLeave()} 不要停声。
     * 一次性标记，各页在 onLeave 里自己清掉；不写进全局设置。
     */
    default void setKeepPlayingOnce(boolean keep) {
    }

    /**
     * 页面优先消费按键。
     *
     * <p>BACK 分支按类注释那张表的顺序排（L2 浮层 → L3 子视图 → L4 输入态），
     * 每个分支用 {@link Back#consume(String)} 收，落到表尾用 {@link Back#pass(String)} 交回宿主。
     *
     * @return true 表示页面处理了（含 BACK 退一层）；false 交回宿主
     *         （BACK 此时是 L5/L6：弹内容页或关模块页）
     */
    boolean handleKeyDown(int keyCode);

    /**
     * 带原始事件的版本。默认转调上面那个，只有需要分辨「按一下」和「按住连发」的页才覆盖。
     *
     * <p>典型是抖音：竖屏信息流规定「按键一次切一条」，按住上键的自动重复如果照单全收，
     * 就是技术方案里专门警告过的「一下跳三条」。重复事件只出现在这里（{@code repeatCount > 0}），
     * 用户快速连按两下仍然是两次独立按键，不会被误杀。
     */
    default boolean handleKeyDown(int keyCode, KeyEvent event) {
        return handleKeyDown(keyCode);
    }

    /**
     * 抬键。默认不接，只有需要区分「按一下」和「按住」的页才覆盖。
     *
     * <p>{@link #handleKeyDown(int, KeyEvent)} 只能看见一次次的按下（含自动重复），
     * 松手那一刻没有任何信号，所以「短按做 A、长按做 B」必须靠这里收尾。
     */
    default boolean handleKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    /** 宿主销毁时回收资源。切模块不调这个——页实例缓存复用 */
    void onRelease();
}

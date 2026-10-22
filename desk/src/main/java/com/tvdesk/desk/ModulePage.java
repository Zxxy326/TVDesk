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
     * @return true 表示页面处理了（含 BACK 退层级）；false 交回宿主
     *         （BACK 此时由宿主关页回桌面）
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

package com.tvdesk.desk.motion;

import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 方向键连发识别。
 *
 * <p>遥控器按住方向键，系统会把同一个 keyCode 反复送进来。桌面必须在连发期间把焦点动画
 * 从 {@link FocusScaler#DURATION_FOCUS}（180ms）压到 {@link FocusScaler#DURATION_REPEAT}
 * （80ms）。不压的后果不是「动画慢一点」，而是**每次按键都排一个 180ms 的动画**：
 * 连发间隔只有 50ms，动画永远排不完队，视觉上先是一段没反应的停顿、然后整屏猛跳一下——
 * 这正是当初那句「焦点/动画不跟手」。
 *
 * <h3>判据只有一条窗口</h3>
 *
 * <pre>
 *   连发 ⟺ repeatCount &gt; 0   或   距上一次方向键按下 ≤ 400ms
 * </pre>
 *
 * <p>不做「必须是同一个键」的判断：按住右键时事件当然都是同一个键，但来回搓（右-左-右）
 * 一样是在快速移动焦点，按「不同键就另起一串」处理反而会在搓方向时掉回 180ms。
 * 也不做「中间必须没有抬起过」的判断——见下面「为什么砍掉两个判据」。
 *
 * <p>400ms 取的是 {@code ViewConfiguration.getKeyRepeatTimeout()} 的语义（本机就是 400）。
 * 写成常量而不是去读系统值，是为了让「手感」有一处**明确的、可以对着调的**数字，
 * 不被各个盒子改过的系统值带偏。
 *
 * <h3>为什么砍掉两个判据</h3>
 *
 * <p>最初的设计是三个判据并存（{@code repeatCount > 0} / 没有抬起过 / 间隔够短），
 * 其中一个写成了「同一个键、中间没有 ACTION_UP、间隔 ≤ 400ms」。真机上怎么都逼不出来：
 * {@code adb shell input keyevent} 永远成对发 down+up；换成 {@code sendevent} 直接往
 * {@code /dev/input/event0} 灌内核自动重复（{@code EV_KEY} 值 2）绕开 up，系统又把它
 * 翻译成 {@code repeatCount=1}，还是落回第一个判据。也就是说那条分支在 Android 上
 * **没有真实输入能走到**——而没被触发过的分支等于没写过。
 *
 * <p>砍掉它的同时把剩下两个也并进同一个窗口，是因为它们的取舍方向是反的：
 *
 * <ul>
 *   <li><b>漏判</b>（该压缩没压缩）→ 用户看到的就是最初那句「不跟手」；</li>
 *   <li><b>误判</b>（不该压缩却压缩了）→ 动画从 180ms 变 80ms，只是更快，没有对错问题。</li>
 * </ul>
 *
 * <p>两边代价不对等，就该往误判那一侧靠。窗口开大也一样：把限死的 50ms 放宽到 400ms，
 * 只影响「要不要更快一点」这个表现层问题，不会让任何一次焦点移动出错。
 *
 * <h3>不留看门狗</h3>
 *
 * <p>不需要定时器去「发现连发已经结束」：每次 down 都会重算一遍，间隔超过窗口就自然
 * 回到非连发态。所以即使 ACTION_UP 被丢了（{@code adb shell input} 和部分遥控器都会丢），
 * 下一次按键也能自己纠正，不会卡在连发态里。
 *
 * <p><b>也不做「焦点切换时清空」。</b>同为按下方向键，焦点从 A 移到 B 恰恰是最需要压缩的时刻。
 * 在那里清空等于把压缩关掉，还会让每跨一格都重新计时。
 *
 * <p>所有调用都在主线程（按键分发和焦点变化都只在主线程），因此不加锁。
 */
public final class KeyRepeatHandler {

    private static final String TAG = "KeyRepeat";

    /**
     * 连发窗口：上一次方向键按下之后这么久以内又按了一次，就认为是在按住/快速连按。
     *
     * <p>对应 {@code ViewConfiguration.getKeyRepeatTimeout()}，本机 400ms。
     */
    private static final long REPEAT_WINDOW_MS = 400L;

    private static final KeyRepeatHandler INSTANCE = new KeyRepeatHandler();

    public static KeyRepeatHandler get() {
        return INSTANCE;
    }

    /** 上一次方向键按下的时刻（{@link SystemClock#uptimeMillis()}），0 表示还没有过 */
    private long lastDownTime;
    /** 当前这一次按下是否算连发 */
    private boolean repeating;

    private KeyRepeatHandler() {
    }

    /** 当前是否处于连发中。{@link FocusScaler} 按它决定用哪个时长。 */
    public boolean isRepeating() {
        return repeating;
    }

    /**
     * 喂一次按下事件。必须在 {@code super.dispatchKeyEvent()} **之前**调用——
     * 焦点移动是在分发过程中同步发生的，那一刻会去问 {@link #isRepeating()}，
     * 晚了就永远是上一轮的状态。
     */
    public void onKeyDown(KeyEvent event) {
        if (!isDirection(event.getKeyCode())) {
            // 按了方向键以外的键，说明这一串已经结束
            reset();
            return;
        }

        long now = SystemClock.uptimeMillis();
        long gap = now - lastDownTime;
        boolean burst = event.getRepeatCount() > 0
                || (lastDownTime != 0L && gap <= REPEAT_WINDOW_MS);

        lastDownTime = now;
        setRepeating(burst, event.getRepeatCount(), gap);
    }

    /** 喂一次抬起事件。松手即结束这一串。 */
    public void onKeyUp(KeyEvent event) {
        if (!isDirection(event.getKeyCode())) {
            return;
        }
        // 保留 lastDownTime：松手后马上再按（≤ 窗口）仍按连发算，手感更连续
        setRepeating(false, -1, -1L);
    }

    /** 清空到「没有按下任何方向键」的初始态 */
    public void reset() {
        lastDownTime = 0L;
        setRepeating(false, -1, -1L);
    }

    /**
     * 只在状态翻转时打一行日志。
     *
     * <p>连发的进出必须能从 logcat 上直接看出来，否则「手感不对」只能靠猜：
     * 是压根没进连发（判据有问题），还是进了但动画没压缩（{@link FocusScaler} 那头有问题），
     * 这两种可能是两段完全不同的代码。每次 down 都打就淹了，翻转发一次足够定位。
     *
     * @param gap 距上一次方向键按下的间隔，{@code -1} 表示不是由按下触发的
     */
    private void setRepeating(boolean value, int repeatCount, long gap) {
        if (value == repeating) {
            return;
        }
        repeating = value;
        if (value) {
            android.util.Log.d(TAG, "连发开始 repeatCount=" + repeatCount
                    + (gap >= 0L ? " 距上次 " + gap + "ms" : ""));
        } else {
            android.util.Log.d(TAG, "连发结束");
        }
    }

    /**
     * 只有方向键会移动焦点，也只有它需要压缩。
     *
     * <p>确认键（DPAD_CENTER / ENTER）不移动焦点；音量、菜单之类的键即使连发也不会触发
     * {@link FocusScaler}，压不压缩没有区别，混进来只会让判据变复杂。
     */
    private static boolean isDirection(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return false;
        }
    }
}

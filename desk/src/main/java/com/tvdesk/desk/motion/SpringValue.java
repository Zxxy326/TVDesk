package com.tvdesk.desk.motion;

/**
 * 临界阻尼弹簧（一个浮点值）。
 *
 * <h3>为什么不用 {@code ValueAnimator} + ease 曲线</h3>
 * 曲线的「手感」是写死在时长里的：180ms 到点，中途改目标只能重启动画、从当前位置再来一段，
 * 连按时就积压成一串半截动画（看着像掉帧）。弹簧反过来 —— <b>改目标就行</b>，
 * 速度和位置是连续量，连按只是把 target 换掉，画面从当前速度接着走，
 * 所以「按住右不放，磁贴一路滑过去」是顺的。
 *
 * <p>参数取临界阻尼（{@code dampingRatio = 1}）：不过冲、不回弹。
 * Aerial 在焦点放大上也是这么定的 —— 回过冲的话，白描边会跟着抖一下。
 *
 * <h3>怎么算</h3>
 * 半隐式欧拉（先更新速度，再用新速度更新位置）。每帧调用 {@link #advance(float)}，
 * 步长按真实帧间隔传（上限 {@link #MAX_STEP}，卡一帧时不会跳）。
 * 刚度决定「多快」，可以按用途调：焦点放大用 {@link #STIFF_FOCUS}，滚动用 {@link #STIFF_SCROLL}。
 */
public final class SpringValue {

    /** 焦点缩放/描边这类小位移：约 0.28 秒到位 */
    public static final float STIFF_FOCUS = 520f;
    /** 滚动/翻页这类大位移：约 0.45 秒到位，慢一点才看得清方向 */
    public static final float STIFF_SCROLL = 220f;

    /** 单帧步长上限（秒）。掉帧时按 32ms 算，宁可慢一点也不要一步跳过去 */
    private static final float MAX_STEP = 0.032f;
    /** 收敛阈值：位置差和速度都小到这个量级就停 */
    private static final float REST_DISTANCE = 0.0008f;
    private static final float REST_VELOCITY = 0.02f;

    private final float stiffness;
    private final float damping;

    private float value;
    private float target;
    private float velocity;

    public SpringValue(float stiffness) {
        this.stiffness = stiffness;
        // 临界阻尼：c = 2 * sqrt(k)
        this.damping = 2f * (float) Math.sqrt(stiffness);
    }

    public void snapTo(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    public void setTarget(float t) {
        target = t;
    }

    public void setTarget(float t, float initialVelocity) {
        target = t;
        velocity = initialVelocity;
    }

    public float value() {
        return value;
    }

    public float target() {
        return target;
    }

    public boolean isAtRest() {
        return Math.abs(value - target) < REST_DISTANCE && Math.abs(velocity) < REST_VELOCITY;
    }

    /**
     * 走一步。
     *
     * @param dtSeconds 距上一帧的秒数（调用方保证 > 0）
     * @return 还在动就 true；停下来（位置吸附到目标）返回 false
     */
    public boolean advance(float dtSeconds) {
        if (dtSeconds <= 0f) {
            return !isAtRest();
        }
        float dt = Math.min(dtSeconds, MAX_STEP);
        float force = -stiffness * (value - target) - damping * velocity;
        velocity += force * dt;
        value += velocity * dt;
        if (Math.abs(value - target) < REST_DISTANCE && Math.abs(velocity) < REST_VELOCITY) {
            value = target;
            velocity = 0f;
            return false;
        }
        return true;
    }
}

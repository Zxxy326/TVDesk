package com.tvdesk.desk.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.tvdesk.desk.R;

/**
 * 按压形变。移植自 react-bits 的 PulseHeart（图标跳一下）和 SquishSwitch（开关压扁）。
 *
 * <p>react-bits 那两个组件是 React + CSS/JS 弹簧，本工程是 Java View 体系，引不进来；
 * 搬的是它们的**关键帧和时长**，用 {@link ObjectAnimator} 的多属性关键帧重实现。
 *
 * <p>为什么不直接映射成弹簧：SquishSwitch 用 react-spring 的 stiffness/damping 跑物理积分，
 * 而这两处形变只有 200 多毫秒，一次性关键帧和弹簧在肉眼上分不出差别，关键帧还不用每帧算积分。
 *
 * <p>形变都落在 {@code scaleX/scaleY} 上，因此**不能挂到会被 {@link FocusScaler} 缩放的元素**
 * （卡片根节点、胶囊按钮本体）：那是同一个属性，动画结束时把值钉回 1 会让聚焦中的元素当场缩回去。
 * 用法是挂到聚焦元素内部的子 View 上——抖音的点赞图标、设置里的开关键。
 */
public final class PressFx {

    /** PulseHeart 原值：一次心跳 560ms */
    private static final long HEART_MS = 560L;
    /** SquishSwitch 的弹簧大约压过去再回弹的时间 */
    private static final long SQUISH_MS = 240L;

    private PressFx() {
    }

    /** 点赞、收藏这类「按下去要有回响」的图标：冲高 1.35 再落回 */
    public static void heart(View view) {
        play(view, HEART_MS,
                new float[] {1f, 1.35f, 0.9f, 1.1f, 1f},
                new float[] {1f, 1.35f, 0.9f, 1.1f, 1f},
                new float[] {0f, 0.22f, 0.46f, 0.70f, 1f});
    }

    /** 开关切换：横向被挤扁、纵向鼓起来，再松开 */
    public static void squish(View view) {
        play(view, SQUISH_MS,
                new float[] {1f, 0.86f, 1.05f, 1f},
                new float[] {1f, 1.10f, 0.96f, 1f},
                new float[] {0f, 0.34f, 0.68f, 1f});
    }

    private static void play(View view, long duration, float[] xValues,
            float[] yValues, float[] fractions) {
        if (view == null) {
            return;
        }
        cancelIfRunning(view);

        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(view,
                holder("scaleX", xValues, fractions),
                holder("scaleY", yValues, fractions));
        animator.setDuration(duration);
        // 关键帧自己就带过冲，外面再套一条缓动曲线会缓冲两次，变成果冻
        animator.setInterpolator(new LinearInterpolator());
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 只有仍是自己时才复位：连点时新动画已经换过 tag，不能去踩它写的值
                if (view.getTag(R.id.tag_press_fx) == animation) {
                    view.setTag(R.id.tag_press_fx, null);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                }
            }
        });
        view.setTag(R.id.tag_press_fx, animator);
        animator.start();
    }

    private static PropertyValuesHolder holder(String property, float[] values, float[] fractions) {
        Keyframe[] keyframes = new Keyframe[values.length];
        for (int i = 0; i < values.length; i++) {
            Keyframe keyframe = Keyframe.ofFloat(fractions[i], values[i]);
            // 每段内部再缓一次，否则峰谷是折线，看着像抽搐
            keyframe.setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG);
            keyframes[i] = keyframe;
        }
        return PropertyValuesHolder.ofKeyframe(property, keyframes);
    }

    private static void cancelIfRunning(View view) {
        Object running = view.getTag(R.id.tag_press_fx);
        if (running instanceof Animator) {
            ((Animator) running).cancel();
        }
        view.setTag(R.id.tag_press_fx, null);
    }
}

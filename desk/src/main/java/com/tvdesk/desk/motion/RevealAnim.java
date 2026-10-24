package com.tvdesk.desk.motion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;

import com.tvdesk.desk.R;

/**
 * 海报入场的统一节奏：错峰淡入 + 轻微上浮。
 *
 * <p>用 ObjectAnimator 而不是 {@link View#animate()}：焦点缩放走的是 ViewPropertyAnimator，
 * 焦点落下时它会 cancel 掉挂在同一个 view 上的全部属性动画（包括正在跑的 alpha、
 * translationY），卡片会停在半透明、半浮起的位置。两套动画分开，互不打断。
 */
public final class RevealAnim {

    private static final long DURATION = 260L;
    private static final long STAGGER = 26L;
    private static final int MAX_STEPS = 8;

    private RevealAnim() {
    }

    /**
     * 从透明 + 下方位移浮入。
     *
     * @param order 卡片在当前屏里的顺序，用来算错峰延迟；超过 {@link #MAX_STEPS} 的一起出发
     * @param fromY 起始下移量（像素）
     */
    public static void staggerIn(View view, int order, float fromY) {
        if (view == null) {
            return;
        }
        cancel(view);
        view.setAlpha(0f);
        view.setTranslationY(fromY);

        ObjectAnimator fade = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f);
        ObjectAnimator rise = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, fromY, 0f);
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(fade, rise);
        set.setDuration(DURATION);
        set.setStartDelay(Math.min(Math.max(order, 0), MAX_STEPS) * STAGGER);
        set.setInterpolator(CubicBezierInterpolator.EASE_OUT_STRONG);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setAlpha(1f);
                view.setTranslationY(0f);
                if (view.getTag(R.id.tag_reveal_anim) == set) {
                    view.setTag(R.id.tag_reveal_anim, null);
                }
            }
        });
        view.setTag(R.id.tag_reveal_anim, set);
        set.start();
    }

    /** 回收/复用卡片前清掉上一轮入场状态，避免它带着半透明落到新位置 */
    public static void reset(View view) {
        if (view == null) {
            return;
        }
        cancel(view);
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

    private static void cancel(View view) {
        Object running = view.getTag(R.id.tag_reveal_anim);
        if (running instanceof Animator) {
            ((Animator) running).cancel();
        }
        view.setTag(R.id.tag_reveal_anim, null);
    }
}

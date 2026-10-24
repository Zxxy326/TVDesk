package com.tvdesk.desk;

import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/** 海报圆角。和桌面首页同一个半径，图片必须裁到圆角里，否则四角会露出直角。 */
public final class PosterCorners {

    private PosterCorners() {
    }

    public static void clip(ImageView art, final int radiusPx) {
        if (art == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        art.setClipToOutline(true);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
    }
}

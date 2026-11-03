package com.tvdesk.desk;

import android.content.Context;

/**
 * 续播点（桌面包的入口）。
 *
 * <p>真正的实现搬到了 {@link com.github.tvbox.osc.cache.ResumePoints}：
 * 播放器（app 模块）里换集、自动下一集也会改 {@code playIndex}，
 * 而 app 依赖 desk 依赖 core —— 只有 core 里的那份能被两边共用。
 * 两边各写一份的话，格式迟早会跑偏，用户看到的就是「这里记得住、那里记不住」。
 */
final class ResumeStore {

    /** 读出来的续播点 */
    static final class Point {
        int index;
        String episode;
        /** 是不是按片名兜底找到的（跨了源，集数不能直接信） */
        boolean byName;

        Point(int index, String episode) {
            this.index = index;
            this.episode = episode;
        }
    }

    private ResumeStore() {
    }

    static void save(Context context, String sourceKey, String vodId, String vodName,
            int index, String episode) {
        com.github.tvbox.osc.cache.ResumePoints.save(sourceKey, vodId, vodName, index, episode);
    }

    static Point read(Context context, String sourceKey, String vodId) {
        return convert(com.github.tvbox.osc.cache.ResumePoints.read(sourceKey, vodId));
    }

    static Point read(Context context, String sourceKey, String vodId, String vodName) {
        return convert(com.github.tvbox.osc.cache.ResumePoints.read(sourceKey, vodId, vodName));
    }

    private static Point convert(com.github.tvbox.osc.cache.ResumePoints.Point point) {
        if (point == null) {
            return null;
        }
        Point result = new Point(point.index, point.episode);
        result.byName = point.byName;
        return result;
    }
}

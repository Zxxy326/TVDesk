package com.tvdesk.core.live;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个频道的节目单，以及「现在在播什么」。
 *
 * <p>节目按开始时间升序存。查「现在」用二分找最后一条 start &lt;= now 的候选，
 * 再确认它还没结束——节目单里常有空档（电视台没给全），这时候就该显示「暂无节目单」，
 * 而不是把上一条的名称硬顶着。
 */
public final class EpgSchedule {

    public static final class Program {
        public final long startMs;
        public final long stopMs;
        public final String title;

        public Program(long startMs, long stopMs, String title) {
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.title = title == null ? "" : title.trim();
        }
    }

    public final List<Program> items = new ArrayList<>();

    public void add(Program program) {
        if (program == null || program.stopMs <= program.startMs) {
            return;
        }
        items.add(program);
    }

    /** 按开始时间排序；XMLTV 里各台的节目不一定有序，解析完统一排一次 */
    public void sort() {
        java.util.Collections.sort(items, new java.util.Comparator<Program>() {
            @Override
            public int compare(Program a, Program b) {
                return Long.compare(a.startMs, b.startMs);
            }
        });
    }

    /** 此刻在播的节目；空档返回 null */
    public Program now(long at) {
        int index = indexAt(at);
        if (index < 0) {
            return null;
        }
        Program candidate = items.get(index);
        return at < candidate.stopMs ? candidate : null;
    }

    /** 下一个节目；没有就返回 null */
    public Program next(long at) {
        int index = indexAt(at);
        int from = index < 0 ? 0 : index;
        for (int i = from; i < items.size(); i++) {
            Program program = items.get(i);
            if (program.startMs > at) {
                return program;
            }
        }
        return null;
    }

    /** 最后一条 start &lt;= at 的下标；都没有返回 -1 */
    private int indexAt(long at) {
        int low = 0;
        int high = items.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (items.get(mid).startMs <= at) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }
}

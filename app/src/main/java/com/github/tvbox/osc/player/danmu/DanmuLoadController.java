package com.github.tvbox.osc.player.danmu;

import android.text.TextUtils;
import android.view.View;

import com.github.tvbox.osc.api.DanmakuApi;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.util.DanmuHelper;
import com.github.tvbox.osc.util.LOG;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;
import xyz.doikki.videoplayer.player.VideoView;

public class DanmuLoadController {
    public interface LoadCallback {
        void onFailed();
    }

    private final MyVideoView videoView;
    private final VodController controller;
    private final DanmakuView danmuView;
    private final DanmakuContext danmakuContext;
    private final AtomicInteger loadSeq = new AtomicInteger();
    private ExecutorService executor;
    private String danmuText = "";
    private String danmuTitle = "";
    private String danmuEpisode = "";
    private int startedSeq = -1;
    private boolean pendingPrepare;
    private boolean temporarilyClosed;
    private LoadCallback loadCallback;
    private volatile int seekGeneration;
    private volatile long pendingSeekPosition = -1;
    /** start 重试次数：prepared 之后要等播放器真的在播，可能得试好几拍 */
    private int startRetries;

    public DanmuLoadController(MyVideoView videoView, VodController controller, DanmakuView danmuView) {
        this.videoView = videoView;
        this.controller = controller;
        this.danmuView = danmuView;
        this.danmakuContext = DanmakuContext.create();
        if (this.videoView != null) {
            this.videoView.setDanmuView(this.danmuView);
            this.videoView.setDanmuSeekListener(this::onDanmuSeek);
            // 视图真的准备好的那一刻就起，不等「稍后重试」猜时间
            this.videoView.setDanmuPreparedListener(this::onViewPrepared);
        }
        applySettings(false);
    }

    public void applySettings(boolean reload) {
        if (danmuView == null || danmakuContext == null) return;
        if (!DanmuHelper.isOpen()) {
            releaseView();
            if (controller != null) controller.setHasDanmu(!TextUtils.isEmpty(danmuText));
            return;
        }
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        int maxLine = DanmuHelper.getMaxLine();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, maxLine);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine);
        danmakuContext.setMaximumLines(maxLines)
                .setScrollSpeedFactor(DanmuHelper.getSpeed())
                .setDanmakuTransparency(DanmuHelper.getAlpha())
                .setScaleTextSize(DanmuHelper.getSizeScale());
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3)
                .setDanmakuMargin(8);
        if (reload && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()) {
            prepare(danmuText);
        }
    }

    public void check(String danmu) {
        check(danmu, "", "");
    }

    public void check(String danmu, String title, String episode) {
        check(danmu, title, episode, null);
    }

    public void check(String danmu, String title, String episode, LoadCallback callback) {
        DanmakuApi.cancel();
        loadCallback = callback;
        seekGeneration++;
        pendingSeekPosition = -1;
        temporarilyClosed = false;
        String newText = TextUtils.isEmpty(danmu) ? "" : danmu.trim();
        String newEpisode = TextUtils.isEmpty(episode) ? "" : episode;
        boolean sameSource = newText.equals(danmuText) && newEpisode.equals(danmuEpisode);
        danmuText = newText;
        danmuTitle = TextUtils.isEmpty(title) ? "" : title;
        danmuEpisode = newEpisode;
        if (sameSource && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()
                && danmuView != null && danmuView.isPrepared()) {
            // 同一集又调了一次（换线路、重试等）：已经准备好了，别再 release 重来，
            // 否则正在屏上的弹幕会被清空，还得等下一轮解析
            if (controller != null) controller.setHasDanmu(true);
            danmuView.setVisibility(View.VISIBLE);
            startIfReady();
            return;
        }
        releaseView();
        boolean hasDanmu = !TextUtils.isEmpty(danmuText);
        if (controller != null) controller.setHasDanmu(hasDanmu);
        if (!hasDanmu || !DanmuHelper.isOpen()) {
            if (danmuView != null) danmuView.setVisibility(View.GONE);
            return;
        }
        if (danmuView != null) danmuView.setVisibility(View.VISIBLE);
        if (!isVideoReady()) {
            pendingPrepare = true;
            return;
        }
        prepare(danmuText);
    }

    public void startIfReady() {
        if (pendingPrepare && !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen()
                && videoView != null && videoView.isPlaying()) {
            pendingPrepare = false;
            prepare(danmuText);
            return;
        }
        startIfReady(loadSeq.get());
        // 状态变化时也顺手补一次重试：例如缓冲结束正在播、但弹幕视图刚好这时 prepared
        if (danmuView != null && !danmuView.isPrepared() && !TextUtils.isEmpty(danmuText)) {
            scheduleStart(loadSeq.get());
        }
    }

    public void reset() {
        DanmakuApi.cancel();
        seekGeneration++;
        pendingSeekPosition = -1;
        temporarilyClosed = false;
        danmuText = "";
        danmuTitle = "";
        danmuEpisode = "";
        pendingPrepare = false;
        loadCallback = null;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (controller != null) controller.setHasDanmu(false);
        releaseView();
    }

    public void close() {
        DanmakuApi.cancel();
        loadSeq.incrementAndGet();
        seekGeneration++;
        pendingSeekPosition = -1;
        startedSeq = -1;
        pendingPrepare = false;
        releaseView();
    }

    public boolean toggle() {
        if (temporarilyClosed) {
            temporarilyClosed = false;
            reloadForPlayback();
            startIfReady();
            return true;
        }
        temporarilyClosed = true;
        close();
        return false;
    }

    public void reloadForPlayback() {
        temporarilyClosed = false;
        loadSeq.incrementAndGet();
        seekGeneration++;
        pendingSeekPosition = -1;
        startedSeq = -1;
        releaseView();
        pendingPrepare = !TextUtils.isEmpty(danmuText) && DanmuHelper.isOpen();
    }

    public void destroy() {
        reset();
        if (videoView != null) {
            videoView.setDanmuSeekListener(null);
            videoView.setDanmuPreparedListener(null);
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void prepare(String danmu) {
        if (TextUtils.isEmpty(danmu)) return;
        pendingPrepare = false;
        int seq = loadSeq.incrementAndGet();
        startedSeq = -1;
        final int currentSeekGeneration = seekGeneration;
        LOG.i("echo-danmu load title: " + safeLog(danmuTitle) + ", episode: " + safeLog(danmuEpisode) + ", source: " + getSourceSummary(danmu));
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        final long initialPosition;
        if (pendingSeekPosition >= 0) {
            initialPosition = pendingSeekPosition;
        } else if (videoView == null) {
            initialPosition = 0;
        } else {
            long currentPosition = videoView.getCurrentPosition();
            initialPosition = currentPosition > 0 ? currentPosition : videoView.getPlaybackPosition();
        }
        LOG.i("echo-danmu parse start at: " + initialPosition);
        executor.execute(() -> {
            Parser currentParser = new Parser(danmu, () -> seq != loadSeq.get(), initialPosition);
            if (seq != loadSeq.get()) return;
            int danmuCount = currentParser.getDanmuCount();
            LOG.i("echo-danmu parsed count: " + danmuCount);
            if (danmuView == null) return;
            final Parser preparedParser = currentParser;
            danmuView.post(() -> {
                if (seq != loadSeq.get() || danmakuContext == null) return;
                try {
                    danmuView.release();
                    if (videoView != null) videoView.setDanmuView(danmuView);
                    if (danmuCount <= 0) {
                        LOG.e("echo-danmu empty after parse");
                        danmuView.setVisibility(View.GONE);
                        notifyLoadFailed(seq);
                        return;
                    }
                    danmuView.prepare(preparedParser, danmakuContext);
                    clearLoadCallback(seq);
                    danmuView.setVisibility(DanmuHelper.isOpen() ? View.VISIBLE : View.GONE);
                    // prepare 是异步的：isPrepared() 什么时候变 true 不确定（设备慢、
                    // 弹幕多时可能一两秒）。原来只等 300/1000 两拍，过了就直接放弃，
                    // 于是「弹幕解好了但一条都不显示」，开关一次才好——现在改成一直试到起来
                    startIfReady(seq);
                    scheduleStart(seq);
                    appendRemaining(preparedParser, seq, currentSeekGeneration);
                } catch (Throwable th) {
                    LOG.e("echo-danmu prepare error: " + th.getMessage());
                    danmuView.setVisibility(View.GONE);
                    notifyLoadFailed(seq);
                }
            });
        });
    }

    private void appendRemaining(Parser parser, int seq, int generation) {
        appendRemaining(parser, seq, generation, false);
    }

    private void appendRemaining(Parser parser, int seq, int generation, boolean seekFirstBatch) {
        if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || !parser.hasMoreDanmaku()) return;
        if (danmuView == null) return;
        if (!danmuView.isPrepared()) {
            danmuView.postDelayed(() -> appendRemaining(parser, seq, generation, seekFirstBatch), 100);
            return;
        }
        if (executor == null || executor.isShutdown()) return;
        executor.execute(() -> {
            if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return;
            List<BaseDanmaku> batch = parser.parseNextBatch();
            if (batch.isEmpty()) return;
            for (BaseDanmaku item : batch) {
                if (seq != loadSeq.get() || generation != seekGeneration || !DanmuHelper.isOpen() || danmuView == null) return;
                danmuView.addDanmaku(item);
            }
            LOG.i("echo-danmu appended count: " + batch.size());
            if (seq == loadSeq.get() && generation == seekGeneration && DanmuHelper.isOpen() && parser.hasMoreDanmaku()) {
                long delay = seekFirstBatch ? 1000 : 50;
                danmuView.postDelayed(() -> appendRemaining(parser, seq, generation), delay);
            }
        });
    }

    private void onDanmuSeek(long position) {
        long targetPosition = Math.max(0, position);
        pendingSeekPosition = targetPosition;
        seekGeneration++;
        loadSeq.incrementAndGet();
        startedSeq = -1;
        if (DanmuHelper.isOpen() && !TextUtils.isEmpty(danmuText)) {
            pendingPrepare = true;
            if (danmuView != null) danmuView.setVisibility(View.GONE);
        }
    }

    private void clearLoadCallback(int seq) {
        if (seq == loadSeq.get()) loadCallback = null;
    }

    private void notifyLoadFailed(int seq) {
        if (seq != loadSeq.get() || loadCallback == null) return;
        LoadCallback callback = loadCallback;
        loadCallback = null;
        callback.onFailed();
    }

    /**
     * 排一次「尝试起播」。
     *
     * <p>每 150ms 试一次，最多 40 次（约 6 秒）。停下来的条件有三个：起来了、
     * 换集/换源了（seq 变了）、或者弹幕被关了。6 秒之后还在缓冲的极端情况，
     * 播放状态变化会再调 {@link #startIfReady()} 补上。
     */
    private void scheduleStart(final int seq) {
        if (danmuView == null || seq != loadSeq.get()) return;
        startRetries = 0;
        danmuView.removeCallbacks(startRetry);
        danmuView.post(startRetry);
    }

    private final Runnable startRetry = new Runnable() {
        @Override
        public void run() {
            if (danmuView == null || danmuView.getVisibility() != View.VISIBLE) return;
            int seq = loadSeq.get();
            if (seq == startedSeq || !DanmuHelper.isOpen() || TextUtils.isEmpty(danmuText)) return;
            if (danmuView.isPrepared()) {
                startIfReady(seq);
                return;
            }
            if (++startRetries < 40) {
                danmuView.postDelayed(this, 150);
            } else {
                LOG.i("echo-danmu start gave up waiting for prepared");
            }
        }
    };

    /** DanmakuView 真的准备好了（{@link DrawHandler.Callback#prepared()}） */
    private void onViewPrepared() {
        int seq = loadSeq.get();
        startRetries = 0;
        startIfReady(seq);
    }

    private void startIfReady(int seq) {
        if (seq != loadSeq.get()
                || seq == startedSeq
                || videoView == null
                || !videoView.isPlaying()
                || danmuView == null
                || !danmuView.isPrepared()
                || !DanmuHelper.isOpen()) {
            return;
        }
        long position = videoView.getCurrentPosition();
        danmuView.setVisibility(View.VISIBLE);
        danmuView.seekTo(position);
        danmuView.start(position);
        startedSeq = seq;
        startRetries = 0;
        danmuView.removeCallbacks(startRetry);
        LOG.i("echo-danmu start at: " + position);
    }

    private boolean isVideoReady() {
        if (videoView == null) return false;
        int state = videoView.getCurrentPlayState();
        return state == VideoView.STATE_PREPARED
                || state == VideoView.STATE_BUFFERED
                || state == VideoView.STATE_PLAYING;
    }

    private void releaseView() {
        if (danmuView == null) return;
        danmuView.removeCallbacks(startRetry);
        startRetries = 0;
        try {
            danmuView.release();
        } catch (Throwable ignored) {
        }
        danmuView.setVisibility(View.GONE);
    }

    private String getSourceSummary(String danmu) {
        if (TextUtils.isEmpty(danmu)) return "";
        if (danmu.startsWith("http") || danmu.startsWith("file")) return danmu;
        return "inline xml length=" + danmu.length();
    }

    private String safeLog(String text) {
        return TextUtils.isEmpty(text) ? "" : text;
    }
}

package com.tvdesk.desk;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.TextureView;
import android.util.Log;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvdesk.core.bg.BackdropItem;
import com.tvdesk.core.bili.BiliClient;
import com.tvdesk.core.bili.model.BiliDetail;
import com.tvdesk.core.bili.model.BiliStream;

import java.util.Map;

/**
 * 沉浸态的动态背景：一个独立的、静音的、无音轨的视频播放器。
 *
 * <p>为什么不复用 {@code BiliPlayerPage} 那一套：那是个完整业务页，自带控制条、弹幕、
 * 进度上报和全局播放实例。背景每隔 8 秒换一次内容，用那套等于每 8 秒起一次「播放页」，
 * 焦点、弹幕、续播记录全都会被搅乱。这里只要「把一段画面铺在桌面上」。
 *
 * <p>只加载画面：B 站 DASH 的画面和声音是两条流，这里只取 {@code dashVideo}，
 * 不合并音轨、音量固定 0。背景视频不应该抢音频焦点，也不该改变音乐的音量
 * （方案第 5 节「音频与资源互斥」）。
 *
 * <p>状态机：{@code 静态图 → 取流/准备 → 首帧 → 播放}，任意阶段可因离页、前台播放、
 * 换条进入停止态。每次换条自增 {@link #generation}，迟到的取流回调、首帧回调只有
 * 代次对得上才允许动画面——8 秒一条的节奏下，取流慢一点就会撞上「已经切到下一条了」。
 */
public class BackgroundWall {

    private static final String TAG = "DeskBackdrop";

    /** 首帧等这么久还没来就回静态图。方案给的初值是 3 秒，可调 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000L;

    /** 默认要 1080P；严格 4K 模式要 4K 档 */
    private static final int QN_FULL_HD = 80;
    private static final int QN_4K = 120;

    public interface Host {
        /** 首帧真的画出来了：现在可以撤掉上面那张静态图 */
        void onBackdropFirstFrame();

        /** 这一条播完了（比目标时长短），或者画面已经不可用：该切下一条 */
        void onBackdropEnded();
    }

    private final Activity activity;
    private final TextureView surface;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SimpleExoPlayer player;
    private BackdropItem current;
    private int generation;
    private boolean firstFrameShown;
    private boolean videoEnabled = true;
    private boolean strict4k;

    private final Runnable firstFrameTimeout = new Runnable() {
        @Override
        public void run() {
            if (current == null || firstFrameShown) {
                return;
            }
            Log.i(TAG, "首帧 " + FIRST_FRAME_TIMEOUT_MS + "ms 没到，回静态图：" + current.title());
            fallbackToImage();
        }
    };

    public BackgroundWall(Activity activity, TextureView surface, Host host) {
        this.activity = activity;
        this.surface = surface;
        this.host = host;
    }

    /** 用户设置：关掉动态背景时整条链路都不启动，连取流都不发 */
    public void setVideoEnabled(boolean enabled) {
        videoEnabled = enabled;
    }

    /** 严格 4K：只接受 4K 档；设备/账户给不出来就回静态图，不偷偷降档 */
    public void setStrict4k(boolean strict) {
        strict4k = strict;
    }

    public boolean isPlaying() {
        return player != null && player.getPlaybackState() != Player.STATE_IDLE
                && player.getPlaybackState() != Player.STATE_ENDED;
    }

    /**
     * 播这一条的视频。
     *
     * <p>调用前宿主已经把这条的静态图铺好了：视频没准备好、或者干脆没有视频，
     * 用户看到的也是这张图，不会出现黑屏空档。
     */
    public void play(final BackdropItem item) {
        if (!videoEnabled || item == null || !item.hasVideo()) {
            return;
        }
        if (activity.isFinishing()) {
            return;
        }
        final int gen = ++generation;
        current = item;
        firstFrameShown = false;
        ui.removeCallbacks(firstFrameTimeout);

        // 取流是网络活，不能压在主线程上；8 秒一条，这里慢一点就直接赶不上了
        new Thread(new Runnable() {
            @Override
            public void run() {
                String url = null;
                String failure = null;
                try {
                    url = resolve(item);
                } catch (Throwable th) {
                    failure = th.getMessage();
                }
                final String target = url;
                final String why = failure;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || current != item) {
                            // 已经切到下一条了，这条的地址没人要了
                            return;
                        }
                        if (TextUtils.isEmpty(target)) {
                            Log.i(TAG, "取不到流，回静态图：" + item.title() + " " + why);
                            fallbackToImage();
                            return;
                        }
                        startPlayer(target, gen);
                    }
                });
            }
        }, "backdrop-stream").start();

        ui.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
    }

    /**
     * 取一条能播的画面地址。
     *
     * <p>只取 DASH 的视频轨：没有音轨就不会有「背景在响」这种事，也省掉一次合并。
     * 严格 4K 模式下 qn=120，拿回来的轨道如果不到 2160p 就放弃——
     * 请求档位和实际轨道不是一回事（方案第 1 节专门强调过）。
     */
    private String resolve(BackdropItem item) throws Exception {
        BiliClient client = BiliClient.get(activity);
        long cid = item.cid;
        if (cid <= 0) {
            BiliDetail detail = client.view(item.bvid);
            if (detail != null) {
                cid = detail.cid;
                item.cid = cid;
            }
        }
        if (cid <= 0) {
            return null;
        }
        BiliStream stream = client.playUrl(item.bvid, cid,
                strict4k ? QN_4K : QN_FULL_HD);
        if (stream == null) {
            return null;
        }
        String url;
        if (stream.kind == BiliStream.DASH) {
            url = stream.dashVideo;
        } else {
            url = stream.urls.isEmpty() ? null : stream.urls.get(0);
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        // 日志只记域名和档位：地址里带签名，不该落进日志
        Log.i(TAG, "背景流 " + item.bvid + " qn=" + stream.quality
                + "/" + stream.requestedQn + " " + hostOf(url));
        return url;
    }

    private void startPlayer(String url, final int gen) {
        try {
            SimpleExoPlayer value = ensurePlayer();
            player.setMediaSource(buildSource(url));
            player.setVolume(0f);
            player.prepare();
            player.setPlayWhenReady(true);
            Log.i(TAG, "开始准备背景视频：" + (current == null ? "" : current.title()));
        } catch (Throwable th) {
            Log.w(TAG, "背景播放器起不来：" + th.getMessage());
            fallbackToImage();
        }
    }

    private SimpleExoPlayer ensurePlayer() {
        if (player != null) {
            return player;
        }
        player = new SimpleExoPlayer.Builder(activity).build();
        player.setVolume(0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (current == null) {
                    return;
                }
                firstFrameShown = true;
                ui.removeCallbacks(firstFrameTimeout);
                recordVideoFormat();
                host.onBackdropFirstFrame();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    // 比目标时长短的片段：播完就切，不循环补足（方案第 5 节）
                    host.onBackdropEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.i(TAG, "背景视频出错，回静态图：" + error.getErrorCodeName());
                fallbackToImage();
            }
        });
        if (surface != null) {
            player.setVideoTextureView(surface);
        }
        return player;
    }

    /** 记下播放器实际用的轨道尺寸，和「请求的档位」分开看 */
    private void recordVideoFormat() {
        if (player == null || current == null) {
            return;
        }
        com.google.android.exoplayer2.Format video = player.getVideoFormat();
        if (video == null) {
            return;
        }
        current.srcWidth = video.width;
        current.srcHeight = video.height;
        current.pixelKnown = video.width > 0 && video.height > 0;
        Log.i(TAG, "实际轨道 " + video.width + "x" + video.height
                + " codec=" + video.codecs + " fps=" + video.frameRate);
    }

    private MediaSource buildSource(String url) {
        Map<String, String> headers = BiliClient.get(activity).playHeaders();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(BiliClient.userAgent())
                .setDefaultRequestProperties(headers)
                // B 站 CDN 会在 https/http 之间跳，不让跳就取不到流
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory factory = new DefaultDataSource.Factory(activity, http);
        return new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
    }

    /** 回到静态图：停掉解码，但保留播放器实例（同一次沉浸里还会再播下一条） */
    private void fallbackToImage() {
        ui.removeCallbacks(firstFrameTimeout);
        firstFrameShown = false;
        generation++;
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 停：离页、退出沉浸、前台要起视频、待机时都得调。
     *
     * <p>只 pause 不够——{@code pause} 不会释放 MediaCodec，前台再开一个 4K 播放器就是
     * 两个硬解实例抢资源（方案第 5 节专门点了这一条）。
     */
    public void stop() {
        generation++;
        current = null;
        firstFrameShown = false;
        ui.removeCallbacks(firstFrameTimeout);
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 页面销毁：连播放器一起放掉 */
    public void release() {
        stop();
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    /** 日志只记域名：地址里有签名，排线路问题不需要整条 URL */
    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }
}

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
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.tvdesk.core.bg.BackdropItem;
import com.tvdesk.core.bg.MovieBackdropStream;
import com.tvdesk.core.douyin.DouyinClient;
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

    /**
     * 屏保出声时的音量。
     *
     * <p>电视的媒体音量一般是 15 档，这里对应第 6 档（0.4）。不动系统音量：
     * 改系统音量会影响其他应用，退出屏保还得记着还原；播放器自己的音量就够了。
     */
    private static final float SOUND_VOLUME = 6f / 15f;

    /** 首帧等这么久还没来就回静态图。方案给的初值是 3 秒，可调 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 3000L;

    /**
     * 影视走 HLS（m3u8），起播比 B 站的 MP4 分片慢得多：要下清单、再下若干个分片。
     * 实测 3 秒不够，放宽到 6 秒；出不了帧就回静态图，这条当「只有图」。
     */
    private static final long MOVIE_FIRST_FRAME_TIMEOUT_MS = 6000L;

    /** 默认要 1080P；严格 4K 模式要 4K 档 */
    private static final int QN_FULL_HD = 80;
    private static final int QN_4K = 120;

    public interface Host {
        /** 首帧真的画出来了：现在可以撤掉上面那张静态图 */
        void onBackdropFirstFrame();

        /**
         * 这一条「有结论了」：首帧到了，或者确定播不了（取不到流 / 超时 / 竖屏 / 出错）。
         *
         * <p>宿主收到后开始计停留时长。之所以不用「换条的时刻」计时：影视那条链路
         * 要先搜源、拉详情、解析地址，好几秒起步——从换条开始算的话，等画面出来
         * 时间已经用完了（实测 8 秒里有一半耗在准备上）。
         */
        void onBackdropReady();

        /** 这一条播完了（比目标时长短），或者画面已经不可用：该切下一条 */
        void onBackdropEnded();
    }

    private final Activity activity;
    private final TextureView surface;
    /** 按视频比例放大裁剪的容器：4:3 的素材直接铺满会横向拉伸 */
    private final AspectRatioFrameLayout ratioBox;
    /** 屏保是否出声。默认静音：背景音是屏保，不该在用户没要求时突然出声 */
    private boolean soundEnabled;
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

    public BackgroundWall(Activity activity, TextureView surface, AspectRatioFrameLayout ratioBox,
                          Host host) {
        this.activity = activity;
        this.surface = surface;
        this.ratioBox = ratioBox;
        this.host = host;
    }

    /** 用户设置：关掉动态背景时整条链路都不启动，连取流都不发 */
    /** 开关屏保声音。改完立刻生效，不用等下一段 */
    public void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
        if (player != null) {
            player.setVolume(enabled ? SOUND_VOLUME : 0f);
        }
    }

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
     *
     * <p>两条来源分开走：B 站有现成的 bvid，一次 playurl 就有流；影视条目只有
     * {@code sourceKey + id}，得先拉详情、必要时让爬虫解析。后者慢得多，
     * 拿不到就回静态图（见 {@link MovieBackdropStream}）。
     */
    public void play(final BackdropItem item) {
        if (!videoEnabled || item == null || activity.isFinishing()) {
            return;
        }
        if (item.isBili() && !item.hasVideo()) {
            return;
        }
        if (item.isDouyin() && TextUtils.isEmpty(item.playUrl)) {
            return;
        }
        if (!item.isBili() && !item.isDouyin() && item.video == null) {
            return;
        }
        final int gen = ++generation;
        current = item;
        firstFrameShown = false;
        ui.removeCallbacks(firstFrameTimeout);

        if (item.isBili()) {
            resolveBili(item, gen);
        } else if (item.isDouyin()) {
            // 抖音推荐流每条都带现成地址，不用解析，直接起
            if (TextUtils.isEmpty(item.playUrl)) {
                fallbackToImage();
                return;
            }
            startPlayer(item.playUrl, gen, FIRST_FRAME_TIMEOUT_MS);
        } else {
            resolveMovie(item, gen);
        }
        // 首帧的计时在 startPlayer 里才开始：影视那边「详情 + 解析」可能就要好几秒，
        // 从 play() 起算的话还没等播放器准备好就超时了
    }

    /** B 站：一次 playurl 拿 DASH 视频轨 */
    private void resolveBili(final BackdropItem item, final int gen) {
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
                        startPlayer(target, gen, FIRST_FRAME_TIMEOUT_MS);
                    }
                });
            }
        }, "backdrop-stream").start();
    }

    /** 影视：详情 → 剧集 → 必要时解析。整条链路都可能拿不到地址，失败即回图 */
    private void resolveMovie(final BackdropItem item, final int gen) {
        MovieBackdropStream.resolve(activity, item.video, new MovieBackdropStream.Callback() {
            @Override
            public void onUrl(final String url) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || current != item) {
                            return;
                        }
                        if (TextUtils.isEmpty(url)) {
                            Log.i(TAG, "影视取不到流，只看静态图：" + item.title());
                            fallbackToImage();
                            return;
                        }
                        Log.i(TAG, "影视背景流 " + hostOf(url) + " " + item.title());
                        startPlayer(url, gen, MOVIE_FIRST_FRAME_TIMEOUT_MS);
                    }
                });
            }
        });
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

    private void startPlayer(String url, final int gen, long firstFrameTimeoutMs) {
        try {
            SimpleExoPlayer value = ensurePlayer();
            player.setMediaSource(buildSource(url));
            player.setVolume(soundEnabled ? SOUND_VOLUME : 0f);
            player.prepare();
            player.setPlayWhenReady(true);
            // 首帧超时从「真的开始准备」算起，取流/解析的时间不算在预算里
            ui.removeCallbacks(firstFrameTimeout);
            ui.postDelayed(firstFrameTimeout, firstFrameTimeoutMs);
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
                // 竖屏素材不铺桌面：两边会被裁掉一大半，中间只剩一条人像
                com.google.android.exoplayer2.Format format = player == null
                        ? null : player.getVideoFormat();
                if (format != null && format.height > format.width) {
                    Log.i(TAG, "竖屏视频不播：" + format.width + "x" + format.height);
                    fallbackToImage();
                    return;
                }
                firstFrameShown = true;
                ui.removeCallbacks(firstFrameTimeout);
                recordVideoFormat();
                host.onBackdropFirstFrame();
                host.onBackdropReady();
            }

            @Override
            public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize videoSize) {
                // 首帧还没到就先看出是竖的，省得白渲染一帧再撤
                if (current == null || videoSize == null) {
                    return;
                }
                if (videoSize.height > videoSize.width) {
                    Log.i(TAG, "竖屏视频不播：" + videoSize.width + "x" + videoSize.height
                            + " " + current.title());
                    fallbackToImage();
                    return;
                }
                // 告诉容器这条视频的真实比例，zoom 模式据此放大裁剪
                if (ratioBox != null && videoSize.height > 0) {
                    ratioBox.setAspectRatio(
                            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height);
                }
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
                if (current != null && !current.isBili()) {
                    // 影视源解析成功但播放被拒（网盘源的 403 最常见），记一笔，
                    // 连续几次之后这个源就不试了
                    MovieBackdropStream.markFailed(current.video);
                }
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
        // 请求头跟着来源走：抖音的 CDN 不认 B 站那套 referer/UA，反过来也一样
        Map<String, String> headers;
        String userAgent;
        if (current != null && current.isDouyin()) {
            headers = DouyinClient.get(activity).playHeaders();
            userAgent = DouyinClient.userAgent();
        } else {
            headers = BiliClient.get(activity).playHeaders();
            userAgent = BiliClient.userAgent();
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                // CDN 会在 https/http 之间跳，不让跳就取不到流
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory factory = new DefaultDataSource.Factory(activity, http);
        Uri uri = Uri.parse(url);
        if (url.contains(".m3u8")) {
            // 影视源里绝大多数是 HLS：ProgressiveMediaSource 播不了清单，会直接报解析错
            return new HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri));
        }
        return new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(uri));
    }

    /** 回到静态图：停掉解码，但保留播放器实例（同一次沉浸里还会再播下一条） */
    private void fallbackToImage() {
        ui.removeCallbacks(firstFrameTimeout);
        firstFrameShown = false;
        generation++;
        // 清掉 current：停掉之后迟到的首帧回调不该再撤静态图（那会露出黑）
        current = null;
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Throwable ignored) {
            }
        }
        // 播不了也是一种「有结论」：宿主该开始计这条的停留时间了，
        // 否则它会一直挂在「等首帧」上不切
        host.onBackdropReady();
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

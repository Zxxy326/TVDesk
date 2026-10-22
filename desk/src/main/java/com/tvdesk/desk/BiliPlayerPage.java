package com.tvdesk.desk;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.player.danmu.Parser;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.tvdesk.core.bili.BiliClient;
import com.tvdesk.core.bili.model.BiliCard;
import com.tvdesk.core.bili.model.BiliDetail;
import com.tvdesk.core.bili.model.BiliEpisode;
import com.tvdesk.core.bili.model.BiliStream;
import com.tvdesk.core.bili.model.LiveRoom;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.widget.WaveProgressView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;

/**
 * B 站播放页（视频稿件 + 直播间共用这一套）。
 *
 * <h3>为什么播放地址在这里取，而不是详情页取好传进来</h3>
 * 取地址要签 wbi、还可能要两次请求（先要整片 mp4，没有再要 DASH）。
 * 放在详情页里点一下会「卡住」几秒；放在这里，界面立刻是黑的 + 一句「正在取播放地址…」，
 * 用户知道发生了什么，而且这一层随时能按 BACK 退出去。
 *
 * <h3>弹幕</h3>
 * 走 core 里 TVBox 现成的解析器 {@link Parser}：B 站的 xml 弹幕格式和它认的是同一个，
 * 直接给它 {@link BiliClient#danmakuXml(long)} 取回来的原文，不重复实现一套分段/缩放。
 * 直播间弹幕要 websocket 长连接（{@code getDanmuInfo} + {@code wss://...}），
 * 这块暂时没接，所以直播只有画面。
 *
 * <h3>按键</h3>
 * 播放/暂停=确定，快退/快进=左右，呼出控制条=上，收起控制条或退出=返回。
 * 上下左右这些键由宿主的 {@code onKeyDown} 转进来（见 {@code DeskActivity} 给
 * {@code activePage.handleKeyDown} 的那一段），本页返回 false 才轮到宿主退页。
 */
public class BiliPlayerPage extends android.widget.FrameLayout implements ModulePage {

    private static final String TAG = "BiliPlayerPage";

    /** 无操作这么久就把控制条收起来。5 秒是「还没看完」和「挡住了」之间的折中 */
    private static final long CONTROLS_TIMEOUT = 5000L;
    private static final long TICK = 500L;
    private static final int SEEK_STEP_MS = 15000;
    /** 按住方向键多久开始连续快进；太短会把「点一下」也吃掉 */
    private static final long HOLD_SEEK_DELAY = 400L;
    /** 连发间隔。每秒约 4 步，配合下面的加速不会把播放器 seek 到卡死 */
    private static final long HOLD_SEEK_INTERVAL = 260L;

    /** 连播模式。数值和 {@link DeskPrefs#biliPlayMode} 一致 */
    public static final int MODE_PAUSE = 0;
    public static final int MODE_ORDER = 1;
    public static final int MODE_SINGLE = 2;
    public static final int MODE_LIST = 3;
    public static final String[] MODE_NAMES = {"播完暂停", "顺序播放", "单个循环", "列表循环"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 列表循环换成推荐视频时会整个替换，所以不能是 final */
    private BiliDetail detail;
    /** 自动连播会换到下一分 P，所以这里不能是 final */
    private BiliEpisode episode;
    private final LiveRoom liveRoom;

    private SimpleExoPlayer player;
    private PlayerView playerView;
    private DanmakuView danmakuView;
    private DanmakuContext danmakuContext;
    private ProgressBar loadingView;
    private View controls;
    private WaveProgressView seekView;
    private TextView posView;
    private TextView durView;
    private ImageView toggleView;
    private ImageView modeView;
    private TextView danmuView;
    private TextView settingsView;
    private TextView titleView;
    private TextView noteView;
    private View infoView;

    private boolean started;
    private boolean released;
    private boolean controlsVisible = true;
    /** 离开页面时正在放，回来才接回去；用户自己按过暂停的话不该替他放 */
    private boolean resumeOnEnter;
    private boolean danmakuOn = true;
    private long durationMs;

    /** 本次要的清晰度。换清晰度会重新取一次流，位置和播放状态原样接上 */
    private int qualityQn;
    private float playbackSpeed = 1f;
    /** 这次取到的流里服务端真给了哪些清晰度，设置弹窗按它列 */
    private final List<BiliStream.Quality> streamQualities = new ArrayList<>();

    /** 空降助手：开关、拉到的片段、已经跳过的片段（同一段只跳一次） */
    private boolean sponsorBlockOn;
    private final List<BiliClient.SponsorSegment> sponsorSegments = new ArrayList<>();
    private final Set<String> skippedSegments = new HashSet<>();
    private String streamBvid = "";
    private long streamCid;
    private int streamGeneration;
    private int sponsorGeneration;

    private boolean settingsOpen;
    private BiliSettingsDialog settingsDialog;
    private int playMode;

    /** 单集视频的「列表」：相关推荐。当前视频不在里面，relatedIndex=-1 表示还没往后走 */
    private final List<BiliCard> relatedQueue = new ArrayList<>();
    private int relatedIndex = -1;
    private boolean relatedFetching;
    private int relatedGeneration;
    private boolean advanceWhenRelatedReady;

    /** 长按快进/快退：方向 1/-1/0，按住时按节拍追加 */
    private int holdSeekDirection;
    private int holdSeekTicks;

    /** 视频稿件 */
    public BiliPlayerPage(Context context, BiliDetail detail, BiliEpisode episode) {
        super(context);
        this.detail = detail;
        this.episode = episode;
        this.liveRoom = null;
        bind(detail == null ? "" : detail.title, episode == null ? "" : episode.part);
    }

    /** 直播间。进来之后还要拿一次播放地址，房间对象里只有信息没有流 */
    public BiliPlayerPage(Context context, LiveRoom room) {
        super(context);
        this.detail = null;
        this.episode = null;
        this.liveRoom = room;
        bind(room == null ? "直播" : room.title, room == null || TextUtils.isEmpty(room.anchor)
                ? "" : "主播 " + room.anchor);
    }

    private void bind(String title, String sub) {
        LayoutInflater.from(getContext()).inflate(R.layout.page_bili_player, this, true);
        // 页自己拿焦点：控制条收起后得有个东西接住方向键，不然寻焦会去找被盖住的桌面卡片
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        playerView = findViewById(R.id.bili_player);
        danmakuView = findViewById(R.id.bili_danmaku);
        loadingView = findViewById(R.id.bili_player_loading);
        controls = findViewById(R.id.bili_controls);
        seekView = findViewById(R.id.bili_seek);
        seekView.setColors(getResources().getColor(R.color.seek_track),
                getResources().getColor(R.color.bili_pink));
        posView = findViewById(R.id.bili_pos);
        durView = findViewById(R.id.bili_dur);
        toggleView = findViewById(R.id.bili_toggle);
        modeView = findViewById(R.id.bili_mode);
        danmuView = findViewById(R.id.bili_danmu);
        settingsView = findViewById(R.id.bili_settings);
        titleView = findViewById(R.id.bili_player_title);
        noteView = findViewById(R.id.bili_player_note);
        infoView = findViewById(R.id.bili_player_info);

        qualityQn = DeskPrefs.biliQuality(getContext());
        playbackSpeed = DeskPrefs.biliSpeed(getContext());
        danmakuOn = DeskPrefs.biliDanmaku(getContext());
        sponsorBlockOn = DeskPrefs.biliSponsorBlock(getContext());
        playMode = DeskPrefs.biliPlayMode(getContext());
        danmuView.setText(danmakuOn ? "弹幕 开" : "弹幕 关");
        applyModeIcon();
        applyResize(DeskPrefs.biliResize(getContext()));

        titleView.setText(TextUtils.isEmpty(title) ? "正在加载…" : title);
        noteView.setText(sub);
        noteView.setVisibility(TextUtils.isEmpty(sub) ? View.GONE : View.VISIBLE);

        FocusScaler.attach(toggleView, 1.06f);
        FocusScaler.attach(modeView, 1.06f);
        FocusScaler.attach(danmuView, 1.06f);
        FocusScaler.attach(settingsView, 1.06f);
        toggleView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        modeView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                cyclePlayMode();
            }
        });
        danmuView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDanmaku();
            }
        });
        settingsView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings();
            }
        });
        setupDanmakuContext();
        scheduleControlsHide();
    }

    /** 直播没有进度条可言，控制条上只留「返回即退出」 */
    private boolean isLive() {
        return liveRoom != null;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onEnter() {
        released = false;
        // 本页自己占焦点：控制条收起来时得有个东西拿着按键，否则方向键会去找
        // 被盖住的桌面卡片。播放器视图和弹幕层都是 focusable=false，不参与抢。
        requestFocus();
        if (started) {
            resumeIfInterrupted();
            showControls();
            return;
        }
        started = true;
        createPlayer();
        if (isLive()) {
            loadLive();
        } else {
            loadVod();
        }
    }

    /**
     * 退到后台：停声，但流和位置都留着。
     *
     * <p>B 站页在换版块时是被 release 掉的，走不到这里；真正需要管的是「按 HOME 走开」
     * ——那时 Activity 只停在 onStop，页面实例还在放。开关见 {@link DeskPrefs}。
     */
    @Override
    public void onLeave() {
        if (released || player == null || !player.getPlayWhenReady()) {
            return;
        }
        if (DeskPrefs.keepPlayingOnLeave(getContext())) {
            return;
        }
        player.setPlayWhenReady(false);
        if (danmakuView != null) {
            danmakuView.pause();
        }
        resumeOnEnter = true;
    }

    /** 被 onLeave 掐断过才接回去，别在用户本来就没在放的时候擅自开播 */
    private void resumeIfInterrupted() {
        if (!resumeOnEnter || released || player == null) {
            return;
        }
        resumeOnEnter = false;
        player.setPlayWhenReady(true);
        resumeDanmaku();
    }

    @Override
    public void onRelease() {
        released = true;
        stopHoldSeek();
        streamGeneration++;
        sponsorGeneration++;
        relatedGeneration++;
        danmakuParser = null;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideTask);
        if (settingsDialog != null) {
            if (settingsDialog.isShowing()) {
                settingsDialog.dismiss();
            }
            settingsDialog = null;
        }
        if (danmakuView != null) {
            try {
                danmakuView.release();
            } catch (Throwable ignored) {
            }
            danmakuView.clearDanmakusOnScreen();
        }
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void createPlayer() {
        BiliClient client = BiliClient.get(getContext());
        Map<String, String> headers = client.playHeaders();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(BiliClient.userAgent())
                .setDefaultRequestProperties(headers)
                // B 站的 CDN 会在 https/http 之间跳，不让跳就取不到流
                .setAllowCrossProtocolRedirects(true);
        DataSource.Factory factory = new DefaultDataSource.Factory(getContext(), http);

        player = new SimpleExoPlayer.Builder(getContext(), hardwareRenderersFactory())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(factory))
                .build();
        playerView.setPlayer(player);
        player.setPlayWhenReady(true);
        player.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
        // 硬解有没有真的生效只能看这里：日志里是 c2.qti/avc 这类名字就是硬解，
        // c2.android/avc 是软解回退
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                android.util.Log.i(TAG, "video decoder: " + decoderName);
            }

            @Override
            public void onVideoInputFormatChanged(EventTime eventTime, Format format,
                                                  DecoderReuseEvaluation reuse) {
                android.util.Log.i(TAG, "video format: " + format.width + "x" + format.height
                        + " " + format.sampleMimeType + " codecs=" + format.codecs);
            }
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (released) {
                    return;
                }
                if (state == Player.STATE_READY) {
                    durationMs = player.getDuration();
                    durView.setText(BiliClient.humanTime(durationMs / 1000L));
                    if (!isLive()) {
                        startDanmaku();
                    }
                    ui.post(tick);
                } else if (state == Player.STATE_ENDED) {
                    if (advancePlayback()) {
                        return;
                    }
                    showControls();
                    Toast.makeText(getContext(), "播放结束", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // 图标只在这里改：暂停/播放的其它调用点（手动切换、切回本页续播）都会
                // 经过播放器状态回调，这里再设一次就不会和它们抢顺序
                toggleView.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                toggleView.setContentDescription(isPlaying ? "暂停" : "播放");
                if (isPlaying) {
                    scheduleControlsHide();
                    if (danmakuView != null && danmakuOn) {
                        resumeDanmaku();
                    }
                } else if (player.getPlaybackState() != Player.STATE_BUFFERING) {
                    // 暂停时把控制条和播放键留在屏幕上；缓冲中不算暂停，不弹出来闪
                    showControls();
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                if (released) {
                    return;
                }
                android.util.Log.w(TAG, "playback error", error);
                Toast.makeText(getContext(), "播放失败：" + (error == null
                        ? "未知错误" : error.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 硬解优先的渲染器工厂。
     *
     * <p>ExoPlayer 默认按系统给的顺序取解码器，某些盒子上 {@code c2.android.*}
     * （软解）排在 {@code c2.qti.*} 前面，4K 一放就掉帧。这里把硬件解码器提到前面，
     * 解不了再靠 {@code setEnableDecoderFallback} 退回软件。
     */
    private DefaultRenderersFactory hardwareRenderersFactory() {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(getContext());
        factory.setEnableDecoderFallback(true);
        factory.setMediaCodecSelector(new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                         boolean requiresSecureDecoder,
                                                         boolean requiresTunnelingDecoder)
                    throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                List<MediaCodecInfo> hardware = new ArrayList<>();
                List<MediaCodecInfo> software = new ArrayList<>();
                for (MediaCodecInfo info : all) {
                    if (info.softwareOnly) {
                        software.add(info);
                    } else {
                        hardware.add(info);
                    }
                }
                hardware.addAll(software);
                return hardware;
            }
        });
        return factory;
    }

    // ---------------------------------------------------------------- 取流

    private void loadVod() {
        if (detail == null || episode == null) {
            Toast.makeText(getContext(), "这条视频没有可播的分 P", Toast.LENGTH_SHORT).show();
            return;
        }
        streamBvid = TextUtils.isEmpty(episode.bvid) ? detail.bvid : episode.bvid;
        streamCid = episode.cid;
        relatedIndex = -1;
        fetchRelated();
        requestStream(qualityQn, 0, true);
    }

    /**
     * 取一路流。首次进来和切清晰度共用：{@code positionMs} 是切之前的位置，
     * 取到之后原地接上，别把用户弹回片头。
     */
    private void requestStream(final int qn, final long positionMs, final boolean initial) {
        if (TextUtils.isEmpty(streamBvid)) {
            return;
        }
        final int generation = ++streamGeneration;
        final boolean wasPlaying = player == null || player.getPlayWhenReady();
        if (initial) {
            loadingView.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliStream result = null;
                String error = null;
                try {
                    result = BiliClient.get(getContext()).playUrl(streamBvid, streamCid, qn);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "取不到播放地址" : e.getMessage();
                }
                final BiliStream value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || generation != streamGeneration) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        if (value == null || !value.playable()) {
                            Toast.makeText(getContext(), failure == null
                                    ? "取不到播放地址" : failure, Toast.LENGTH_LONG).show();
                            return;
                        }
                        playStream(value);
                        if (positionMs > 0) {
                            player.seekTo(positionMs);
                            if (danmakuView != null && danmakuView.isPrepared() && danmakuOn) {
                                danmakuView.seekTo(positionMs);
                            }
                        }
                        player.setPlayWhenReady(wasPlaying);
                        describeStream(value);
                        fetchSponsorSegments();
                    }
                });
            }
        }).start();
    }

    private void switchQuality(int qn) {
        qualityQn = qn;
        DeskPrefs.setBiliQuality(getContext(), qn);
        if (isLive() || released || TextUtils.isEmpty(streamBvid)) {
            return;
        }
        requestStream(qn, player == null ? 0 : player.getCurrentPosition(), false);
    }

    private boolean hasEpisodeList() {
        return detail != null && detail.episodes.size() > 1;
    }

    /**
     * 一集放完按当前模式决定下一步。
     *
     * <p>多 P / 合集用 {@link BiliDetail#episodes} 当列表；单集视频没有列表，
     * 用相关推荐当列表（用户要的「非合集按推荐列表循环」）。返回 true 表示
     * 已经切到下一集/下一个视频，false 表示这一轮到头了。
     */
    private boolean advancePlayback() {
        if (isLive()) {
            return false;
        }
        if (playMode == MODE_SINGLE) {
            replayCurrent();
            return true;
        }
        if (playMode != MODE_ORDER && playMode != MODE_LIST) {
            return false;
        }
        if (hasEpisodeList()) {
            int index = currentEpisodeIndex();
            if (index < 0) {
                return false;
            }
            int next = index + 1;
            if (next >= detail.episodes.size()) {
                if (playMode != MODE_LIST) {
                    return false;
                }
                next = 0;
            }
            BiliEpisode target = detail.episodes.get(next);
            if (target == null || target.cid <= 0) {
                return false;
            }
            playEpisode(target);
            return true;
        }
        if (relatedQueue.isEmpty()) {
            // 推荐还没回来：先记一笔，拉到后如果还停在结尾就续上
            advanceWhenRelatedReady = true;
            fetchRelated();
            return false;
        }
        int next = relatedIndex + 1;
        if (next >= relatedQueue.size()) {
            if (playMode != MODE_LIST) {
                return false;
            }
            next = 0;
        }
        relatedIndex = next;
        playRelated(relatedQueue.get(next));
        return true;
    }

    private void replayCurrent() {
        if (player == null) {
            return;
        }
        skippedSegments.clear();
        if (danmakuView != null && danmakuView.isPrepared()) {
            danmakuView.seekTo(0L);
        }
        player.seekTo(0);
        player.setPlayWhenReady(true);
        showControls();
    }

    /** 把按集算的状态全部清掉，否则上一集的弹幕/跳过记录会带过来 */
    private void resetPlaybackState() {
        durationMs = 0;
        advanceWhenRelatedReady = false;
        skippedSegments.clear();
        sponsorSegments.clear();
        sponsorGeneration++;
        danmakuGeneration++;
        danmakuFetching = false;
        danmakuParser = null;
        if (danmakuView != null) {
            try {
                danmakuView.release();
                danmakuView.clearDanmakusOnScreen();
            } catch (Throwable ignored) {
            }
        }
        seekView.setProgress(0);
        posView.setText("0:00");
        durView.setText("0:00");
    }

    private void playEpisode(BiliEpisode next) {
        episode = next;
        streamBvid = TextUtils.isEmpty(next.bvid) ? detail.bvid : next.bvid;
        streamCid = next.cid;
        relatedGeneration++;
        relatedFetching = false;
        relatedIndex = -1;
        resetPlaybackState();
        showControls();
        requestStream(qualityQn, 0, true);
    }

    /**
     * 单集视频的「下一集」：先取那个视频的详情，再按普通视频起播。
     * 播放页里的 detail 整体换掉，标题/分 P 也跟着换。
     */
    private void playRelated(final BiliCard card) {
        if (card == null || TextUtils.isEmpty(card.bvid) || card.bvid.equals(streamBvid)) {
            return;
        }
        final int generation = ++streamGeneration;
        loadingView.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                BiliDetail fetched = null;
                String error = null;
                try {
                    fetched = BiliClient.get(getContext()).view(card.bvid);
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "取不到下一个视频" : e.getMessage();
                }
                final BiliDetail value = fetched;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || generation != streamGeneration) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        BiliEpisode first = value == null ? null : value.first();
                        if (first == null || first.cid <= 0) {
                            Toast.makeText(getContext(), failure == null
                                    ? "这个视频没有可播的分 P" : failure, Toast.LENGTH_LONG).show();
                            return;
                        }
                        detail = value;
                        episode = first;
                        streamBvid = TextUtils.isEmpty(first.bvid) ? value.bvid : first.bvid;
                        streamCid = first.cid;
                        titleView.setText(value.title);
                        relatedGeneration++;
                        relatedFetching = false;
                        relatedIndex = -1;
                        relatedQueue.clear();
                        resetPlaybackState();
                        showControls();
                        requestStream(qualityQn, 0, true);
                        fetchRelated();
                    }
                });
            }
        }).start();
    }

    /** 单集视频的推荐列表。多 P / 合集不用它 */
    private void fetchRelated() {
        if (isLive() || hasEpisodeList() || TextUtils.isEmpty(streamBvid) || relatedFetching) {
            return;
        }
        final String bvid = streamBvid;
        final int generation = ++relatedGeneration;
        relatedFetching = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<BiliCard> result = new ArrayList<>();
                try {
                    result = BiliClient.get(getContext()).related(bvid);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "related failed", e);
                }
                final List<BiliCard> value = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        relatedFetching = false;
                        if (released || generation != relatedGeneration
                                || !bvid.equals(streamBvid)) {
                            return;
                        }
                        relatedQueue.clear();
                        for (BiliCard card : value) {
                            if (card != null && !TextUtils.isEmpty(card.bvid)
                                    && !card.bvid.equals(bvid)) {
                                relatedQueue.add(card);
                            }
                        }
                        if (advanceWhenRelatedReady && player != null
                                && player.getPlaybackState() == Player.STATE_ENDED) {
                            advanceWhenRelatedReady = false;
                            advancePlayback();
                        }
                    }
                });
            }
        }).start();
    }

    private int currentEpisodeIndex() {
        if (detail == null || episode == null) {
            return -1;
        }
        int position = detail.episodes.indexOf(episode);
        if (position >= 0) {
            return position;
        }
        for (int i = 0; i < detail.episodes.size(); i++) {
            BiliEpisode item = detail.episodes.get(i);
            if (item != null && item.cid == episode.cid) {
                return i;
            }
        }
        return -1;
    }

    private void loadLive() {
        loadingView.setVisibility(View.VISIBLE);
        final long roomId = liveRoom == null ? 0 : liveRoom.roomId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                LiveRoom result = null;
                String error = null;
                try {
                    // h5 给 HLS：老电视解码器对 flv 的支持不齐，m3u8 更稳
                    result = BiliClient.get(getContext()).liveRoom(roomId, "h5");
                } catch (Exception e) {
                    error = TextUtils.isEmpty(e.getMessage()) ? "进不去这个直播间" : e.getMessage();
                }
                final LiveRoom value = result;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        if (value == null || value.urls.isEmpty()) {
                            Toast.makeText(getContext(), failure == null
                                    ? "进不去这个直播间" : failure, Toast.LENGTH_LONG).show();
                            return;
                        }
                        hideControls();
                        seekView.setVisibility(View.GONE);
                        posView.setVisibility(View.GONE);
                        durView.setVisibility(View.GONE);
                        noteView.setText((TextUtils.isEmpty(value.anchor) ? "" : "主播 " + value.anchor
                                + " · ") + BiliStream.nameOf(value.currentQn) + " · 直播");
                        play(value.urls.get(0));
                    }
                });
            }
        }).start();
    }

    /**
     * 把一条 {@link BiliStream} 交给播放器。
     *
     * <p>mp4 是整片（画面和声音在同一个文件里），一条 url 就够；
     * DASH 是画面、声音两个文件，得合并成一路媒体，否则只有图像没有声音。
     */
    private void playStream(BiliStream value) {
        if (value.kind == BiliStream.DASH && !TextUtils.isEmpty(value.dashVideo)) {
            ProgressiveMediaSource.Factory sourceFactory =
                    new ProgressiveMediaSource.Factory(dataSourceFactory());
            if (TextUtils.isEmpty(value.dashAudio)) {
                // 没有音轨就单放画面：B 站偶尔只回 video，塞个 null 进去 ExoPlayer 直接抛
                player.setMediaSource(sourceFactory.createMediaSource(
                        MediaItem.fromUri(value.dashVideo)));
            } else {
                player.setMediaSource(new MergingMediaSource(
                        sourceFactory.createMediaSource(MediaItem.fromUri(value.dashVideo)),
                        sourceFactory.createMediaSource(MediaItem.fromUri(value.dashAudio))));
            }
            player.prepare();
            return;
        }
        if (!value.urls.isEmpty()) {
            play(value.urls.get(0));
            return;
        }
        Toast.makeText(getContext(), "这个清晰度取不到可播的流", Toast.LENGTH_SHORT).show();
    }

    private DataSource.Factory dataSourceFactory() {
        BiliClient client = BiliClient.get(getContext());
        return new DefaultHttpDataSource.Factory()
                .setUserAgent(BiliClient.userAgent())
                .setDefaultRequestProperties(client.playHeaders())
                .setAllowCrossProtocolRedirects(true);
    }

    private void play(String url) {
        if (player == null || TextUtils.isEmpty(url)) {
            return;
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** 清晰度那行说明。要的和服务端给的不一致时要说清楚，否则用户以为是我们卡 */
    private void describeStream(BiliStream value) {
        streamQualities.clear();
        if (value.qualities != null) {
            streamQualities.addAll(value.qualities);
        }
        String text = BiliStream.nameOf(value.quality);
        if (value.quality < value.requestedQn) {
            text += "（已取最高可用）";
        }
        if (value.kind == BiliStream.DASH) {
            text += " · DASH";
        }
        noteView.setText(isEpisodeNamed() ? text + " · " + episode.part : text);
    }

    private boolean isEpisodeNamed() {
        return episode != null && !TextUtils.isEmpty(episode.part) && detail != null
                && detail.episodes.size() > 1;
    }

    // ---------------------------------------------------------------- 设置

    private void openSettings() {
        if (settingsOpen || released || !(getContext() instanceof Activity)) {
            return;
        }
        settingsOpen = true;
        stopHoldSeek();
        showControls();
        settingsDialog = new BiliSettingsDialog((Activity) getContext(),
                new ArrayList<>(streamQualities), qualityQn, isLive(),
                new BiliSettingsDialog.Listener() {
                    @Override
                    public void onQuality(int qn) {
                        switchQuality(qn);
                    }

                    @Override
                    public void onSpeed(float speed) {
                        playbackSpeed = speed;
                        DeskPrefs.setBiliSpeed(getContext(), speed);
                        if (player != null) {
                            player.setPlaybackParameters(new PlaybackParameters(speed));
                        }
                    }

                    @Override
                    public void onResize(int mode) {
                        DeskPrefs.setBiliResize(getContext(), mode);
                        applyResize(mode);
                    }

                    @Override
                    public void onDanmaku(boolean on) {
                        DeskPrefs.setBiliDanmaku(getContext(), on);
                        if (danmakuOn != on) {
                            toggleDanmaku();
                        }
                    }

                    @Override
                    public void onSponsorBlock(boolean on) {
                        sponsorBlockOn = on;
                        DeskPrefs.setBiliSponsorBlock(getContext(), on);
                        if (on) {
                            fetchSponsorSegments();
                        } else {
                            sponsorSegments.clear();
                        }
                    }

                    @Override
                    public void onPlayMode(int mode) {
                        playMode = mode;
                        DeskPrefs.setBiliPlayMode(getContext(), mode);
                        applyModeIcon();
                        if (mode == MODE_ORDER || mode == MODE_LIST) {
                            fetchRelated();
                        }
                    }

                    @Override
                    public void onDanmakuScale(float scale) {
                        DeskPrefs.setBiliDanmakuScale(getContext(), scale);
                        applyDanmakuStyle();
                    }

                    @Override
                    public void onDanmakuArea(int lines) {
                        DeskPrefs.setBiliDanmakuArea(getContext(), lines);
                        applyDanmakuStyle();
                    }

                    @Override
                    public void onDanmakuSpeed(float speed) {
                        DeskPrefs.setBiliDanmakuSpeed(getContext(), speed);
                        applyDanmakuStyle();
                    }

                    @Override
                    public void onDanmakuAlpha(float alpha) {
                        DeskPrefs.setBiliDanmakuAlpha(getContext(), alpha);
                        applyDanmakuStyle();
                    }
                });
        settingsDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                settingsOpen = false;
                settingsDialog = null;
                if (!released) {
                    showControls();
                    requestFocus();
                }
            }
        });
        settingsDialog.show();
    }

    private void applyResize(int mode) {
        if (playerView == null) {
            return;
        }
        playerView.setResizeMode(mode == 1 ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : mode == 2 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    private void cyclePlayMode() {
        playMode = (playMode + 1) % MODE_NAMES.length;
        DeskPrefs.setBiliPlayMode(getContext(), playMode);
        applyModeIcon();
        Toast.makeText(getContext(), "连播：" + MODE_NAMES[playMode], Toast.LENGTH_SHORT).show();
        if (playMode == MODE_ORDER || playMode == MODE_LIST) {
            fetchRelated();
        }
    }

    private void applyModeIcon() {
        if (modeView == null) {
            return;
        }
        int icon;
        switch (playMode) {
            case MODE_ORDER:
                icon = R.drawable.ic_mode_sequence;
                break;
            case MODE_SINGLE:
                icon = R.drawable.ic_mode_one;
                break;
            case MODE_LIST:
                icon = R.drawable.ic_mode_order;
                break;
            default:
                icon = R.drawable.ic_mode_pause;
                break;
        }
        modeView.setImageResource(icon);
        modeView.setContentDescription("连播模式：" + MODE_NAMES[playMode]);
    }

    // ---------------------------------------------------------------- 空降助手

    private void fetchSponsorSegments() {
        sponsorSegments.clear();
        if (!sponsorBlockOn || isLive() || TextUtils.isEmpty(streamBvid)) {
            return;
        }
        final int generation = ++sponsorGeneration;
        final String bvid = streamBvid;
        final long cid = streamCid;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<BiliClient.SponsorSegment> result =
                        BiliClient.get(getContext()).sponsorSegments(bvid, cid);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || generation != sponsorGeneration || !sponsorBlockOn) {
                            return;
                        }
                        for (BiliClient.SponsorSegment item : result) {
                            if (!"skip".equals(item.actionType)
                                    || !isSponsorCategory(item.category)) {
                                continue;
                            }
                            if (TextUtils.isEmpty(item.uuid)) {
                                item.uuid = item.category + "@" + item.start;
                            }
                            sponsorSegments.add(item);
                        }
                        java.util.Collections.sort(sponsorSegments,
                                new java.util.Comparator<BiliClient.SponsorSegment>() {
                                    @Override
                                    public int compare(BiliClient.SponsorSegment a,
                                                       BiliClient.SponsorSegment b) {
                                        return Double.compare(a.start, b.start);
                                    }
                                });
                        // 片段可能比正片晚到：如果此刻已经在某个片段里，补跳一次
                        if (player != null) {
                            checkSponsorSkip(player.getCurrentPosition());
                        }
                    }
                });
            }
        }).start();
    }

    /** 进入片段前一秒就起跳，跳完拉到片段末尾；同一段只跳一次，手动拖回去也不重跳 */
    private void checkSponsorSkip(long positionMs) {
        if (!sponsorBlockOn || isLive() || sponsorSegments.isEmpty()) {
            return;
        }
        for (BiliClient.SponsorSegment item : sponsorSegments) {
            long startMs = Math.round(item.start * 1000);
            long endMs = Math.round(item.end * 1000);
            if (endMs <= startMs || positionMs < startMs - 1000 || positionMs >= endMs) {
                continue;
            }
            if (!skippedSegments.add(item.uuid)) {
                continue;
            }
            seekToDuration(endMs);
            Toast.makeText(getContext(), "空降助手：已跳过「" + sponsorName(item.category)
                    + "」" + BiliClient.humanTime(startMs / 1000) + "–"
                    + BiliClient.humanTime(endMs / 1000), Toast.LENGTH_SHORT).show();
            break;
        }
    }

    private static boolean isSponsorCategory(String category) {
        if (category == null) {
            return false;
        }
        switch (category) {
            case "sponsor":
            case "selfpromo":
            case "interaction":
            case "intro":
            case "outro":
            case "preview":
            case "padding":
            case "filler":
            case "music_offtopic":
                return true;
            default:
                return false;
        }
    }

    private static String sponsorName(String category) {
        if (category == null) {
            return "片段";
        }
        switch (category) {
            case "sponsor":
                return "恰饭";
            case "selfpromo":
                return "自我推广";
            case "interaction":
                return "互动提醒";
            case "intro":
                return "片头";
            case "outro":
                return "片尾";
            case "preview":
                return "预告";
            case "padding":
                return "垫场";
            case "filler":
                return "填充内容";
            case "music_offtopic":
                return "非音乐内容";
            default:
                return "片段";
        }
    }

    // ---------------------------------------------------------------- 弹幕

    private void setupDanmakuContext() {
        danmakuContext = DanmakuContext.create();
        danmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3).setDanmakuMargin(8);
        danmakuView.setClickable(false);
        danmakuView.setVisibility(danmakuOn ? View.VISIBLE : View.GONE);
        applyDanmakuStyle();
    }

    /**
     * 弹幕字号/显示区域/速度/透明度。
     *
     * <p>这几项都走 {@link DanmakuContext} 的 setter，它会把自己标记成配置变更并
     * 通知已挂上的 view；正在屏上的那批不会改大小，清掉让它们按新配置重新排。
     */
    private void applyDanmakuStyle() {
        if (danmakuContext == null) {
            return;
        }
        int scrollLines = DeskPrefs.biliDanmakuArea(getContext());
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, Math.max(2, scrollLines / 2));
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, scrollLines);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, Math.max(2, scrollLines / 2));
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, Math.max(1, scrollLines / 3));
        danmakuContext.setMaximumLines(maxLines)
                .setScrollSpeedFactor(DeskPrefs.biliDanmakuSpeed(getContext()))
                .setScaleTextSize(DeskPrefs.biliDanmakuScale(getContext()))
                .setDanmakuTransparency(DeskPrefs.biliDanmakuAlpha(getContext()));
        if (danmakuView != null && danmakuView.isPrepared()) {
            danmakuView.clearDanmakusOnScreen();
            danmakuView.invalidate();
        }
    }

    /**
     * 起弹幕。
     *
     * <p>取 xml 和解析都放后台（一条热门视频的 xml 有几千条，主线程解会卡住首帧），
     * 解完再回主线程 prepare。时序照抄 app 模块里 TVBox 那条链路：
     * prepare 之后不一定立刻 prepared，所以补两拍重试 start；
     * 解析器是分批吐的（首批 500 条），剩下的要接着喂，否则拖到后半段就没弹幕了。
     */
    private void startDanmaku() {
        if (released || danmakuView == null || danmakuContext == null || !danmakuOn
                || danmakuFetching) {
            return;
        }
        final long cid = streamCid > 0 ? streamCid
                : (episode != null ? episode.cid : (detail == null ? 0 : detail.cid));
        if (cid <= 0) {
            return;
        }
        danmakuFetching = true;
        final int generation = ++danmakuGeneration;
        final long initial = player == null ? 0 : Math.max(0, player.getCurrentPosition());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Parser parser = null;
                int xmlLength = 0;
                String xmlHead = "";
                try {
                    String xml = BiliClient.get(getContext()).danmakuXml(cid);
                    if (xml != null) {
                        xmlLength = xml.length();
                        xmlHead = xml.substring(0, Math.min(80, xml.length()))
                                .replace('\n', ' ').replace('\r', ' ');
                    }
                    if (!released && generation == danmakuGeneration
                            && !TextUtils.isEmpty(xml)) {
                        parser = new Parser(xml, new Parser.CancelChecker() {
                            @Override
                            public boolean isCancelled() {
                                return released || generation != danmakuGeneration;
                            }
                        }, initial);
                    }
                } catch (Exception e) {
                    android.util.Log.w(TAG, "danmaku xml failed cid=" + cid, e);
                }
                final Parser result = parser;
                danmakuFetching = false;
                if (result == null || result.getDanmuCount() <= 0) {
                    // 静默返回过一次：弹幕一条都没上屏时完全没线索，只能靠这里
                    android.util.Log.w(TAG, "danmaku empty cid=" + cid + " xml=" + xmlLength
                            + " count=" + (result == null ? -1 : result.getDanmuCount())
                            + " from=" + initial + " head=" + xmlHead);
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        prepareDanmaku(result, generation);
                    }
                });
            }
        }).start();
    }

    private void prepareDanmaku(Parser parser, final int generation) {
        if (released || generation != danmakuGeneration || danmakuView == null) {
            return;
        }
        try {
            danmakuView.release();
            danmakuView.prepare(parser, danmakuContext);
        } catch (Throwable th) {
            android.util.Log.w(TAG, "danmaku prepare failed", th);
            danmakuView.setVisibility(View.GONE);
            return;
        }
        danmakuParser = parser;
        danmakuView.setVisibility(danmakuOn ? View.VISIBLE : View.GONE);
        startDanmakuAt(generation);
        danmakuView.postDelayed(new Runnable() {
            @Override
            public void run() {
                startDanmakuAt(generation);
            }
        }, 400);
        appendRemaining(generation);
    }

    /** 把解析器剩下那几批喂完。首批只有 500 条，整片视频远不止这个数 */
    private void appendRemaining(final int generation) {
        if (released || generation != danmakuGeneration || danmakuView == null
                || danmakuParser == null || !danmakuParser.hasMoreDanmaku()) {
            return;
        }
        if (!danmakuView.isPrepared()) {
            danmakuView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    appendRemaining(generation);
                }
            }, 120);
            return;
        }
        final Parser parser = danmakuParser;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<BaseDanmaku> batch;
                try {
                    batch = parser.parseNextBatch();
                } catch (Throwable th) {
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (released || generation != danmakuGeneration || danmakuView == null) {
                            return;
                        }
                        for (BaseDanmaku item : batch) {
                            try {
                                danmakuView.addDanmaku(item);
                            } catch (Throwable ignored) {
                            }
                        }
                        danmakuView.forceRender();
                        appendRemaining(generation);
                    }
                });
            }
        }).start();
    }

    private int danmakuGeneration;
    private volatile boolean danmakuFetching;
    private Parser danmakuParser;

    private void startDanmakuAt(int generation) {
        if (released || generation != danmakuGeneration || danmakuView == null || player == null
                || !danmakuView.isPrepared() || !danmakuOn) {
            return;
        }
        long position = player.getCurrentPosition();
        danmakuView.seekTo(position);
        danmakuView.start(position);
    }

    private void resumeDanmaku() {
        if (danmakuView == null || player == null || !danmakuOn) {
            return;
        }
        try {
            if (!danmakuView.isPrepared()) {
                // 首帧比弹幕到得早的情况：xml 还没解完，这里补一次
                startDanmaku();
                return;
            }
            if (danmakuView.isPaused()) {
                danmakuView.resume();
            } else {
                danmakuView.start(player.getCurrentPosition());
            }
        } catch (Throwable ignored) {
        }
    }

    private void toggleDanmaku() {
        danmakuOn = !danmakuOn;
        DeskPrefs.setBiliDanmaku(getContext(), danmakuOn);
        danmuView.setText(danmakuOn ? "弹幕 开" : "弹幕 关");
        if (danmakuView == null) {
            return;
        }
        if (danmakuOn) {
            danmakuView.setVisibility(View.VISIBLE);
            if (danmakuView.isPrepared()) {
                danmakuView.resume();
            } else {
                startDanmaku();
            }
        } else {
            danmakuView.setVisibility(View.GONE);
            try {
                danmakuView.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 控制条

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (released || player == null) {
                return;
            }
            long position = player.getCurrentPosition();
            if (!isLive()) {
                if (durationMs > 0) {
                    seekView.setProgress((int) Math.min(1000, position * 1000L / durationMs));
                }
                posView.setText(BiliClient.humanTime(position / 1000L));
                checkSponsorSkip(position);
            }
            ui.postDelayed(this, TICK);
        }
    };

    private final Runnable hideTask = new Runnable() {
        @Override
        public void run() {
            // 暂停/播完时控制条常驻，否则用户找不到播放键
            if (!isLive() && player != null && !player.isPlaying()
                    && player.getPlaybackState() != Player.STATE_BUFFERING) {
                return;
            }
            hideControls();
        }
    };

    private void seekToDuration(long positionMs) {
        if (player == null || isLive() || positionMs < 0) {
            return;
        }
        long target = positionMs;
        if (durationMs > 0) {
            target = Math.min(positionMs, Math.max(0, durationMs - 1000L));
        }
        player.seekTo(target);
        if (danmakuView != null && danmakuView.isPrepared() && danmakuOn) {
            danmakuView.seekTo(target);
        }
        posView.setText(BiliClient.humanTime(target / 1000L));
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.setPlayWhenReady(false);
            if (danmakuView != null) {
                danmakuView.pause();
            }
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
            }
            player.setPlayWhenReady(true);
            resumeDanmaku();
        }
        ui.postDelayed(tick, 200);
    }

    private void showControls() {
        controlsVisible = true;
        controls.setVisibility(View.VISIBLE);
        if (infoView != null) {
            infoView.setVisibility(View.VISIBLE);
        }
        ui.removeCallbacks(hideTask);
        scheduleControlsHide();
    }

    private void hideControls() {
        controlsVisible = false;
        controls.setVisibility(View.GONE);
        // 标题和清晰度跟着控制条一起收，播放时只留画面和弹幕
        if (infoView != null) {
            infoView.setVisibility(View.GONE);
        }
        ui.removeCallbacks(hideTask);
        // 焦点原本在刚被隐藏的那颗按钮上，不收回来的话下一次方向键会飘到桌面
        requestFocus();
    }

    private void scheduleControlsHide() {
        ui.removeCallbacks(hideTask);
        ui.postDelayed(hideTask, CONTROLS_TIMEOUT);
    }

    // ---------------------------------------------------------------- 按键

    /**
     * 传输键在这里拦，不交给焦点树。
     *
     * <p>原因：本页是内嵌在桌面 Activity 的一个 View，DeskActivity 的
     * {@code onKeyDown} 排在按键分发的最后，而「控制条收起来时页面上没有可聚焦的东西」
     * 是常态——那时 LEFT/RIGHT 会直接被系统的几何寻焦吃掉，焦点跳到别处，
     * 快进快退就变成「换一张卡片」。所以在分发阶段先自己收下。
     *
     * <p>两处让路：焦点在进度条上时左右键归它（逐格拖进度比一次跳 15 秒精确）；
     * 焦点在播放/弹幕这两个按钮上时确定键归按钮自己（否则一次按键既走点击又走
     * {@link #togglePlay()}，开关会被抵消掉）。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (settingsOpen) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stopHoldSeek();
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            View focus = findFocus();
            boolean onButton = focus == toggleView || focus == modeView
                    || focus == danmuView || focus == settingsView;
            // 焦点已经在这排按钮上时自己搬焦点，不交给几何寻焦：寻焦失败的话事件会
            // 一路落到宿主的 handleKeyDown，右键就变成快进而不是走到「设置」
            if (onButton && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.getRepeatCount() == 0) {
                    showControls();
                    moveControlFocus(focus, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
                } else {
                    // 轻点是挪焦点，按住则开始快退/快进——两种意图靠连发区分
                    startHoldSeek(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;
            }
            // 不在按钮上时左右是快退/快进：第一下走一步，按住就连续走
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                showControls();
                startHoldSeek(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            boolean handledHere = keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || (!onButton && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            if (handledHere && handleKeyDown(keyCode)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 控制条内部的左右移动：连播模式 ← 播放 → 弹幕 → 设置。进度条只读，不进这一圈 */
    private void moveControlFocus(View focus, boolean right) {
        if (right) {
            if (focus == toggleView) {
                danmuView.requestFocus();
            } else if (focus == danmuView) {
                settingsView.requestFocus();
            } else if (focus == modeView) {
                toggleView.requestFocus();
            }
            return;
        }
        if (focus == settingsView) {
            danmuView.requestFocus();
        } else if (focus == danmuView) {
            toggleView.requestFocus();
        } else if (focus == toggleView) {
            modeView.requestFocus();
        }
    }

    /**
     * 长按左/右：第一下立刻走一步，之后按节拍连续走，越按步长越大。
     * 同一方向的连发事件不重置计时，所以按住不会变成「走一步停一下」。
     */
    private void startHoldSeek(int direction) {
        if (player == null || isLive() || holdSeekDirection == direction) {
            return;
        }
        holdSeekDirection = direction;
        holdSeekTicks = 0;
        seekOffset(direction * SEEK_STEP_MS);
        ui.removeCallbacks(holdSeekTask);
        ui.postDelayed(holdSeekTask, HOLD_SEEK_DELAY);
    }

    private void stopHoldSeek() {
        holdSeekDirection = 0;
        ui.removeCallbacks(holdSeekTask);
    }

    private final Runnable holdSeekTask = new Runnable() {
        @Override
        public void run() {
            if (released || holdSeekDirection == 0 || player == null || isLive()
                    || settingsOpen) {
                return;
            }
            holdSeekTicks++;
            // 前 2 秒按 15 秒走，之后 30 秒，再往后 1 分钟：长途拖动不用按到手酸
            int step = holdSeekTicks < 8 ? SEEK_STEP_MS
                    : holdSeekTicks < 20 ? SEEK_STEP_MS * 2 : SEEK_STEP_MS * 4;
            seekOffset(holdSeekDirection * step);
            showControls();
            ui.postDelayed(this, HOLD_SEEK_INTERVAL);
        }
    };

    @Override
    public boolean handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            showControls();
            if (!isLive()) {
                seekOffset(-SEEK_STEP_MS);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            showControls();
            if (!isLive()) {
                seekOffset(SEEK_STEP_MS);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // 上：呼出控制条，焦点落在播放键（进度条只读，不再是一个焦点位）
            showControls();
            toggleView.requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (controlsVisible) {
                hideControls();
                requestFocus();
                return true;
            }
            showControls();
            toggleView.requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (!controlsVisible) {
                showControls();
                return true;
            }
            togglePlay();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 控制条还亮着：返回先收控制条，再按一次才真退出——
            // 一按返回就退出播放，电视上很容易误触（遥控器返回键就在拇指底下）
            if (controlsVisible) {
                hideControls();
                return true;
            }
            return false;
        }
        return false;
    }

    private void seekOffset(int offsetMs) {
        if (player == null || isLive()) {
            return;
        }
        seekToDuration(player.getCurrentPosition() + offsetMs);
    }

    /** 宿主要在按 MENU 之前问一下：播放页自己要吃这个键（收起/呼出控制条） */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (released) {
            return;
        }
        if (!hasWindowFocus) {
            // 设置弹窗盖上来不算切走：它只是本页的配置面板，底下的视频不该停
            if (settingsOpen) {
                return;
            }
            // 弹窗、别的 Activity 盖上来：停声并把位置原地留住，回来才不用
            // 对着一段已经播过去的画面。「切走继续放」的开关在这里同样生效。
            onLeave();
            return;
        }
        resumeIfInterrupted();
    }
}

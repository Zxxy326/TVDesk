package com.tvdesk.desk.widget;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * 频谱采样 —— MuYin 的 {@code SpectrumTap} 在 Java 侧的对应实现。
 *
 * <p>MuYin 用自带的 PCM 通路算 FFT；我们这边播放器是 MediaPlayer，拿不到 PCM，
 * 所以走系统 {@link Visualizer}（按音频会话 id 取波形），自己算三档能量：
 * bass / mid / high，范围 0..1，和 MuYin 同样的语义（涟漪的相位速度就吃这三个值）。
 *
 * <p>限制：需要 RECORD_AUDIO 权限（系统要求），没授权时全部返回 0，
 * 涟漪退化成「匀速呼吸 + 咬字脉冲」，不会崩也不会停。
 */
public final class SpectrumTap {

    private static final String TAG = "SpectrumTap";

    public static volatile float bass;
    public static volatile float mid;
    public static volatile float high;

    private static Visualizer visualizer;
    private static int session;

    private SpectrumTap() {
    }

    /** 绑定到某个播放会话；重复调用同一个 session 直接返回 */
    public static void attach(int audioSession) {
        if (audioSession == session && visualizer != null) {
            return;
        }
        detach();
        session = audioSession;
        if (audioSession == 0) {
            return;
        }
        try {
            visualizer = new Visualizer(audioSession);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer v, byte[] waveform, int rate) {
                    compute(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) {
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            visualizer.setEnabled(true);
        } catch (Throwable th) {
            Log.w(TAG, "频谱打不开（多半是没给录音权限）：" + th.getMessage());
            detach();
        }
    }

    public static void detach() {
        try {
            if (visualizer != null) {
                visualizer.setEnabled(false);
                visualizer.release();
            }
        } catch (Throwable ignored) {
        }
        visualizer = null;
        session = 0;
        bass = mid = high = 0f;
    }

    /**
     * 波形 → 三档能量。
     *
     * <p>不做真正的 FFT：把 8 位波形按过零率粗分低/中/高，够驱动涟漪的呼吸和亮度了，
     * 而且 CPU 开销可以忽略（电视上跑三档模糊都嫌贵）。
     */
    private static void compute(byte[] wave) {
        if (wave == null || wave.length < 16) {
            return;
        }
        double sum = 0;
        int crossings = 0;
        int prev = 0;
        for (int i = 0; i < wave.length; i++) {
            int v = wave[i] & 0xFF;
            sum += Math.abs(v - 128);
            if (i > 0 && ((prev - 128) * (v - 128) < 0)) {
                crossings++;
            }
            prev = v;
        }
        float level = (float) Math.min(1.0, (sum / wave.length) / 64.0);
        float rate = crossings / (float) wave.length;
        bass = clamp(level * (1.15f - rate * 2.0f));
        mid = clamp(level * (0.55f + rate));
        high = clamp(level * rate * 1.6f);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

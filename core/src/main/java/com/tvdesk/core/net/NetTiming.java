package com.tvdesk.core.net;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Response;

/**
 * 一次 HTTP 调用的分段耗时日志。
 *
 * <p>排查「所有模块都要等十秒才出内容」这类问题时，只知道总耗时没用：可能是 DNS 慢、
 * 可能是 TCP 连不上在等超时、可能是 TLS 握手卡住、也可能是服务端迟迟不回第一个字节。
 * 这四段各自对应的修法完全不同（换解析、换地址、关 IPv6 优先、换接口），所以分开记。
 *
 * <p>只记时间点，不打 URL 全文（地址里常带签名）。
 *
 * <p><b>必须用 {@link #factory(String)} 挂上去，不要直接 new 一个实例塞进
 * {@code OkHttpClient.Builder.eventListener()}。</b> 传实例时 OkHttp 会拿同一个对象服务
 * 这条 client 上的所有请求，这个类又是「一次请求一组时间戳」的有状态对象：
 * 第二个请求会从上一个请求留下的 connAt 上接着算，日志里就会出现
 * {@code tls=292105 total=1618} 这种自相矛盾的数字（292 秒的 TLS 握手不可能发生）。
 * 走 factory 的话每次请求都是新实例，数字才可信。
 */
public final class NetTiming extends EventListener {

    private static final String TAG = "NetTime";

    private final String owner;
    private long callAt;
    private long dnsAt = -1;
    private long dnsDone;
    private long connAt = -1;
    private long connDone;
    private long tlsAt = -1;
    private long tlsDone;
    private long reqAt = -1;
    private long headersDone;
    private int connectTries;

    private NetTiming(String owner) {
        this.owner = owner;
    }

    /** 每次请求新建一个实例。挂这个，别挂 {@code eventListener(...)}。 */
    public static EventListener.Factory factory(final String owner) {
        return new EventListener.Factory() {
            @Override
            public EventListener create(Call call) {
                return new NetTiming(owner);
            }
        };
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static long gap(long from, long to) {
        return (from < 0 || to <= 0 || to < from) ? -1 : (to - from);
    }

    @Override
    public void callStart(Call call) {
        callAt = now();
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        if (dnsAt < 0) {
            dnsAt = now();
        }
    }

    @Override
    public void dnsEnd(Call call, String domainName, java.util.List<java.net.InetAddress> addresses) {
        if (dnsDone == 0) {
            dnsDone = now();
        }
    }

    @Override
    public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
        connectTries++;
        if (connAt < 0) {
            connAt = now();
        }
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, okhttp3.Protocol protocol) {
        if (connDone == 0) {
            connDone = now();
        }
    }

    @Override
    public void connectFailed(Call call, InetSocketAddress address, Proxy proxy, okhttp3.Protocol protocol,
                              IOException ioe) {
        // 连某一条地址失败（IPv6 黑洞、端口不通）也要留痕：它往往就是那十秒的来源
        Log.w(TAG, owner + " 连接失败 " + address + " " + ioe.getClass().getSimpleName());
    }

    @Override
    public void secureConnectStart(Call call) {
        if (tlsAt < 0) {
            tlsAt = now();
        }
    }

    @Override
    public void secureConnectEnd(Call call, Handshake handshake) {
        tlsDone = now();
    }

    @Override
    public void requestHeadersStart(Call call) {
        reqAt = now();
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
        headersDone = now();
    }

    @Override
    public void callEnd(Call call) {
        report(call, null);
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        report(call, ioe);
    }

    /** 慢到这个数才记：正常的接口几百毫秒，记下来只是噪音 */
    private static final long SLOW_MS = 1200L;

    private void report(Call call, IOException error) {
        String host = call.request().url().host();
        long total = gap(callAt, now());
        if (error == null && total >= 0 && total < SLOW_MS) {
            return;
        }
        Log.i(TAG, owner + " " + host
                + " dns=" + gap(dnsAt, dnsDone)
                + " conn=" + gap(connAt, connDone)
                + " tls=" + gap(tlsAt, tlsDone)
                // ttfb 从「请求头开始发」算到「响应头收完」：连接是复用的话 connAt 是 -1，
                // 拿它当起点会记成 -1，看不出服务端到底慢不慢
                + " ttfb=" + gap(reqAt > 0 ? reqAt : callAt, headersDone)
                + " total=" + total
                + " tries=" + connectTries
                + (error == null ? "" : " 失败=" + error.getClass().getSimpleName()));
    }
}

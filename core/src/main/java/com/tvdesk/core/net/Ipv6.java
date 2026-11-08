package com.tvdesk.core.net;

import android.util.Log;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;

/**
 * 这台机器能不能用 IPv6。
 *
 * <h3>为什么不能只看「有没有 IPv6 地址」</h3>
 * 之前排查加载慢时踩过：电视确实拿到了全局 IPv6 地址，但那条路是黑洞，
 * 连上去既不回 RST 也不回数据，只能等超时。所以「有地址」不等于「能用」，
 * 必须真的连一次。
 *
 * <h3>为什么要判断这个</h3>
 * 高清直播源里有相当一部分是 IPv6 单播（运营商内网转出来的），
 * 清晰度明显好于公网 IPv4 那批。有 IPv6 就该优先用；没有就别去试，
 * 否则每个台都要先等一次连接超时才轮到 IPv4。
 *
 * <p>结果缓存 10 分钟：网络切换（插拔网线、换 Wi-Fi）不会立刻感知，
 * 但也不至于每开一次直播都探测一遍。
 */
public final class Ipv6 {

    private static final String TAG = "Ipv6";

    /** 探测目标：阿里公共 DNS 的 IPv6 地址，国内可达性最好 */
    private static final String PROBE_HOST = "2400:3200::1";
    private static final int PROBE_PORT = 53;
    private static final long PROBE_TIMEOUT_MS = 2500L;
    private static final long CACHE_MS = 10 * 60 * 1000L;

    private static Boolean cached;
    private static long cachedAt;

    private Ipv6() {
    }

    /** 本机是否有全局 IPv6 地址（不含链路本地、回环） */
    public static boolean hasAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet6Address && !address.isLinkLocalAddress()
                            && !address.isLoopbackAddress() && !address.isAnyLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "枚举网卡失败：" + th.getMessage());
        }
        return false;
    }

    /** 能用 IPv6 吗（有地址且真的连得通）。阻塞最多 2.5 秒，别在主线程调 */
    public static synchronized boolean available() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS) {
            return cached;
        }
        boolean ok = false;
        if (hasAddress()) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(PROBE_HOST, PROBE_PORT),
                        (int) PROBE_TIMEOUT_MS);
                ok = true;
            } catch (Throwable th) {
                Log.i(TAG, "IPv6 探测失败：" + th.getClass().getSimpleName());
            } finally {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        } else {
            Log.i(TAG, "没有全局 IPv6 地址");
        }
        cached = ok;
        cachedAt = now;
        Log.i(TAG, "IPv6 可用=" + ok);
        return ok;
    }
}

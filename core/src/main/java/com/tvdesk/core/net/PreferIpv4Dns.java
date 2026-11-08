package com.tvdesk.core.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Dns;

/**
 * 优先 IPv4 的 DNS。
 *
 * <p>为什么需要：不少电视/盒子会拿到一个全局 IPv6 地址，但上游根本没通（或只在局域网内通）。
 * 这时 DNS 会同时给出 AAAA 和 A，OkHttp 按顺序挨个试——先试 IPv6，那条路是黑洞，
 * 既不回 RST 也不回数据，只能等 connect 超时（本项目里是 8 秒），失败后才轮到 IPv4。
 *
 * <p>表现就是「每个模块第一次打开都要等十秒才有内容」，而且**装什么应用都一样**：
 * 是网络栈的问题，不是某段业务代码慢。实测一次抖音请求 tries=3、conn=16049ms。
 *
 * <p>所以只要这台机器有 A 记录，就完全不把 AAAA 交给 OkHttp。代价是丢掉了 IPv6 的
 * 那点好处（对 1080p 的视频和 JSON 接口毫无意义），换来的是不再白等一整个连接超时。
 *
 * <p>反过来，如果某个域名只有 AAAA（纯 IPv6 站点），照样把原结果返回——不制造新的连不上。
 */
public final class PreferIpv4Dns implements Dns {

    public static final PreferIpv4Dns INSTANCE = new PreferIpv4Dns();

    private PreferIpv4Dns() {
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
        return preferV4(Dns.SYSTEM.lookup(hostname));
    }

    /**
     * 有 IPv4 就只留 IPv4；没有就原样返回。
     *
     * <p>给已经在做解析的地方（DoH、hosts 映射）复用，避免到处复制这段判断。
     */
    public static List<InetAddress> preferV4(List<InetAddress> all) {
        if (all == null || all.size() <= 1) {
            return all;
        }
        List<InetAddress> v4 = null;
        for (InetAddress address : all) {
            if (address instanceof Inet4Address) {
                if (v4 == null) {
                    v4 = new ArrayList<>(all.size());
                }
                v4.add(address);
            }
        }
        return v4 == null ? all : v4;
    }
}

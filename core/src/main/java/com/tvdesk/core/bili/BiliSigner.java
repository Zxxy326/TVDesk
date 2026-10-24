package com.tvdesk.core.bili;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站 Web 端 wbi 签名。
 *
 * <p>2023 年起 {@code search/type}、{@code player/wbi/playurl}、{@code space/wbi/*} 这几个接口
 * 要求带 {@code w_rid} + {@code wts}，缺了直接回 -403 或空结果。算法本身只是
 * 「固定重排表 + MD5」，纯 Java 二十行，不需要 quickjs。
 *
 * <p>两个容易写错的地方，都会导致签名对不上：
 * <ul>
 *   <li><b>取值要先过滤 {@code !'()*} 这四个字符</b>再编码。官方 JS 用
 *       {@code encodeURIComponent}，这四个字符它不转义，而 Java 的
 *       {@code URLEncoder.encode} 会转——B 站那边算出来的原文和我们对不上。</li>
 *   <li><b>编码规则不是 {@code URLEncoder}。</b>{@code URLEncoder} 把空格编成 {@code +}、
 *       把 {@code ~} 编成 {@code %7E}，{@code encodeURIComponent} 是空格 {@code %20}、
 *       {@code ~} 原样。这里自己实现（见 {@link #encode}）。</li>
 * </ul>
 */
public final class BiliSigner {

    /** 重排表的前 32 项（完整表 64 项，签名只用前 32） */
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    };

    private static final String FILTERED = "!'()*";

    private BiliSigner() {
    }

    /**
     * 由 nav 接口给的 img_key / sub_key 算混淆密钥。
     *
     * @param imgUrl {@code data.wbi_img.img_url}，这里直接从 URL 里取文件名
     * @param subUrl {@code data.wbi_img.sub_url}
     */
    public static String mixinKey(String imgUrl, String subUrl) {
        String raw = fileNameOf(imgUrl) + fileNameOf(subUrl);
        StringBuilder sb = new StringBuilder(32);
        for (int index : MIXIN_TAB) {
            if (index < raw.length()) {
                sb.append(raw.charAt(index));
            }
        }
        return sb.length() > 32 ? sb.substring(0, 32) : sb.toString();
    }

    /** {@code https://i0.hdslb.com/bfs/wbi/7cd08494...077c.png} → {@code 7cd08494...077c} */
    private static String fileNameOf(String url) {
        if (url == null) {
            return "";
        }
        String name = url;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 给参数加 {@code wts} 和 {@code w_rid}，返回可以直接拼 URL 的查询串。
     *
     * @param params 业务参数，不含 wts / w_rid；null 值会被跳过（和官方行为一致）
     * @param mixin  {@link #mixinKey}
     * @param nowSec 秒级时间戳，参数化出来是为了可测
     */
    public static String signedQuery(Map<String, String> params, String mixin, long nowSec) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry.getValue() != null) {
                    sorted.put(entry.getKey(), entry.getValue());
                }
            }
        }
        sorted.put("wts", String.valueOf(nowSec));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(entry.getKey())).append('=').append(encode(strip(entry.getValue())));
        }
        String signed = query + "&w_rid=" + md5Hex(query.toString() + mixin);
        return signed;
    }

    private static String strip(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (FILTERED.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** encodeURIComponent 等价实现：只放行字母数字和 {@code -_.~} */
    public static String encode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = value.getBytes("UTF-8");
        } catch (Exception e) {
            return value;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(HEX[c >> 4]);
                sb.append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.toLowerCase(HEX[(b >> 4) & 0x0F]));
                sb.append(Character.toLowerCase(HEX[b & 0x0F]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

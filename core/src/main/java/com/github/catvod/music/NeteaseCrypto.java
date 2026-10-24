package com.github.catvod.music;

import android.text.TextUtils;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 weapi 加密。
 *
 * <h3>为什么需要它</h3>
 * 网易云在 2016 年之后把所有明文接口都换成了加密参数：URL 后面挂的
 * {@code params} 是两层 AES，{@code encSecKey} 是 RSA。没有这一步，
 * {@code /weapi/*} 一律回「参数错误」。
 *
 * <h3>算法本身（对齐官方 crypto.js，逐字一致）</h3>
 * <pre>
 *   secretKey = 16 个 base62 随机字符
 *   params    = base64(AES-CBC(base64(AES-CBC(json, presetKey, iv)), secretKey, iv))
 *   encSecKey = hex(RSA-NoPadding(secretKey 倒序, 官方公钥))
 *   body      = params=&lt;urlencode&gt;&amp;encSecKey=&lt;urlencode&gt;
 * </pre>
 * 两个反直觉的点，写错了只会表现为「服务端说参数错误」，不会有别的提示：
 * <ul>
 *   <li>第一层加密的结果是 <b>base64 字符串</b>，再拿去当第二层的明文（不是字节流）；</li>
 *   <li>RSA 用的是 <b>NoPadding</b>，输入必须自己左补零到密钥长度（1024 位 = 128 字节），
 *       否则 JCE 直接抛异常。secretKey 是倒序后再加密的。</li>
 * </ul>
 */
public final class NeteaseCrypto {

    /** AES IV，官方写死 */
    private static final String IV = "0102030405060708";
    /** 第一层 AES 的固定 key */
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 官方 RSA 公钥（正文抄自 crypto.js，去掉头尾标记） */
    private static final String PUBLIC_KEY_BODY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7cl"
                    + "FSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ"
                    + "MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile RSAPublicKey publicKey;

    private NeteaseCrypto() {
    }

    /** 拼出可直接当请求体的 {@code params=..&encSecKey=..} */
    public static String weapiBody(String json) {
        String secretKey = randomBase62(16);
        String encFirst = aesCbcBase64(json, PRESET_KEY);
        String encSecond = aesCbcBase64(encFirst, secretKey);
        String encSecKey = rsaNoPadding(secretKey);
        return "params=" + urlEncode(encSecond) + "&encSecKey=" + urlEncode(encSecKey);
    }

    /** base62 随机串。secretKey 必须是「字母数字」——RSA 那段要求它可逆序成字节 */
    public static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        byte[] buf = new byte[length];
        RANDOM.nextBytes(buf);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt((buf[i] & 0xff) % BASE62.length()));
        }
        return sb.toString();
    }

    /** 小写十六进制随机串，做设备指纹用 */
    public static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        byte[] buf = new byte[length];
        RANDOM.nextBytes(buf);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(buf[i] & 0x0f, 16));
        }
        return sb.toString();
    }

    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlEncode(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // ---------------------------------------------------------------- 内部

    /** AES-CBC/PKCS5，结果 base64。key 不足 16 字节时右补零（官方 key 都是正好 16） */
    private static String aesCbcBase64(String plain, String key) {
        try {
            byte[] keyBytes = new byte[16];
            byte[] raw = key.getBytes("UTF-8");
            System.arraycopy(raw, 0, keyBytes, 0, Math.min(raw.length, 16));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new IvParameterSpec(IV.getBytes("UTF-8")));
            byte[] out = cipher.doFinal(plain.getBytes("UTF-8"));
            return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("weapi AES 失败：" + e, e);
        }
    }

    /**
     * RSA/NoPadding（等价于 JS 里的 RSA_NO_PADDING）。
     *
     * <p>NoPadding 要求输入长度恰好等于模长，所以先把「倒序的 secretKey」左补零到 128 字节——
     * 这正好等价于 JS 里那个 128 字节全零 buffer + 从末尾写入的效果。
     */
    private static String rsaNoPadding(String secretKey) {
        try {
            RSAPublicKey key = key();
            int size = key.getModulus().bitLength() / 8;
            byte[] reversed = new StringBuilder(secretKey).reverse().toString().getBytes("UTF-8");
            byte[] input = new byte[size];
            System.arraycopy(reversed, 0, input, size - reversed.length, reversed.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] out = cipher.doFinal(input);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("weapi RSA 失败：" + e, e);
        }
    }

    private static RSAPublicKey key() throws Exception {
        if (publicKey == null) {
            synchronized (NeteaseCrypto.class) {
                if (publicKey == null) {
                    byte[] der = android.util.Base64.decode(PUBLIC_KEY_BODY, android.util.Base64.DEFAULT);
                    publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                            .generatePublic(new X509EncodedKeySpec(der));
                }
            }
        }
        return publicKey;
    }
}
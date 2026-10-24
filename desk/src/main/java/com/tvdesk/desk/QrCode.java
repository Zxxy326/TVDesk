package com.tvdesk.desk;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * 本地画二维码。B 站扫码登录和抖音那两个局域网表单弹窗都要它，而它们要的不是同一件事：
 * 一个把登录 url 转成码，一个把本机表单地址转成码。
 *
 * <p>按位图像素出图而不是交给 ImageView 缩放矢量：模块数够多才扫得准，
 * 512 像素缩到电视上 240dp 既不留锯齿也够清晰。
 */
final class QrCode {

    private QrCode() {
    }

    /**
     * @param size 边长（像素），二维码恒为正方形
     * @return 画失败返回 {@code null}——调用方通常只是让二维码区域空着，
     *         下面那行地址文本还在，用户照样能手打
     */
    static Bitmap of(String content, int size) {
        try {
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable th) {
            android.util.Log.w("QrCode", "qrcode render failed", th);
            return null;
        }
    }
}

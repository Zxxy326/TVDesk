package com.tvdesk.desk;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 机型规则：按 {@code ro.product.model} 匹配一条已知记录。
 *
 * <p>规则数据照搬 aerial-tv 的 {@code devices.json}（MIT），字段原样保留。它们解决的是
 * 「框架看不出来、只能靠人报告」的那类差异——比如某台电视开机后必须让原厂桌面先显示一会儿，
 * 抢早了它的电源灯会一直呼吸；某台机器注册了一个后面没有接线的 HDMI 口。
 *
 * <p>TVDesk 目前用得到的只有两条：{@code note}（给状态页/设置页解释用）和
 * {@code bootStockMs}（开机后先让原厂桌面待多久再接管）。其余字段先留着，
 * 等真做输入源管理时再说。
 *
 * <p>这些是<b>上游报告</b>，本项目没有逐台复验过，所以只作解释和建议，不改变接管判定。
 */
public final class DeviceProfile {

    public static final class Entry {
        public String model = "";
        public String note = "";
        /** 开机后先让原厂桌面显示这么久，再接管（0 表示不特殊处理） */
        public int bootStockMs;
        /** 上游对这台机器的接管结论：unknown / supported / guard */
        public String homeTakeover = "unknown";
        /** 命中的是哪条规则（模型名），没命中为空 */
        public boolean matched;
    }

    private static Entry cached;

    private DeviceProfile() {
    }

    public static Entry of(Context context) {
        if (cached != null) {
            return cached;
        }
        Entry entry = new Entry();
        entry.model = Build.MODEL == null ? "" : Build.MODEL;
        try {
            String json = read(context, "device-profiles.json");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray profiles = root.getAsJsonArray("profiles");
            for (JsonElement ele : profiles) {
                JsonObject obj = ele.getAsJsonObject();
                if (!matches(obj)) {
                    continue;
                }
                entry.matched = true;
                entry.note = optString(obj, "note");
                entry.bootStockMs = obj.has("bootStockMs") ? obj.get("bootStockMs").getAsInt() : 0;
                entry.homeTakeover = optString(obj, "homeTakeover");
                if (TextUtils.isEmpty(entry.homeTakeover)) {
                    entry.homeTakeover = "unknown";
                }
                break;
            }
        } catch (Throwable th) {
            // 规则读不到不影响任何功能：它就是一张备注表
        }
        cached = entry;
        return entry;
    }

    private static boolean matches(JsonObject obj) {
        JsonArray models = obj.getAsJsonArray("models");
        if (models == null || Build.MODEL == null) {
            return false;
        }
        for (JsonElement m : models) {
            if (Build.MODEL.equals(m.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String read(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }
}

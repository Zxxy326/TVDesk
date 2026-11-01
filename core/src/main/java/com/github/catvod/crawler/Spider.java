package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

public class Spider {

    public static JSONObject empty = new JSONObject();
    public String siteKey;

    protected static Context mContext;
    protected static SpiderApi api;

    public void init(Context context) {
        mContext = context;
    }

    public void init(Context context, String extend) {
        init(context);
    }

    /**
     * 注入宿主能力（本机服务地址、日志、多线程请求、网页解析）。
     *
     * <p>这个字段本身不能省：jar 里的爬虫是按「TVBox 家族」的基类编译的，
     * 有些直接读 {@code Spider.api} 这个静态字段（不是自己存一份）。
     * 基类里没有它，那些 jar 一碰就 {@link NoSuchFieldError}；
     * 而这个错误在爬虫内部被当成「宿主不支持」，回一句
     * 「这个壳没有弹窗」之类的提示——用户看到的就是点一下报错。
     */
    public void initApi(SpiderApi spiderApi) {
        api = spiderApi;
    }

    /** 给子类/爬虫取宿主能力用；没注入过返回 null，调用方要自己判空 */
    public static SpiderApi getApi() {
        return api;
    }

    /**
     * 首页数据内容
     *
     * @param filter 是否开启筛选
     * @return
     */
    public String homeContent(boolean filter) {
        return "";
    }

    /**
     * 首页最近更新数据 如果上面的homeContent中不包含首页最近更新视频的数据 可以使用这个接口返回
     *
     * @return
     */
    public String homeVideoContent() {
        return "";
    }

    /**
     * 分类数据
     *
     * @param tid
     * @param pg
     * @param filter
     * @param extend
     * @return
     */
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "";
    }

    /**
     * 详情数据
     *
     * @param ids
     * @return
     */
    public String detailContent(List<String> ids) {
        return "";
    }

    /**
     * 搜索数据内容
     *
     * @param key
     * @param quick
     * @return
     */
    public String searchContent(String key, boolean quick) {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) {
        return searchContent(key, quick);
    }

    /**
     * 播放信息
     *
     * @param flag
     * @param id
     * @return
     */
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    /**
     * webview解析时使用 可自定义判断当前加载的 url 是否是视频
     *
     * @param url
     * @return
     */
    public boolean isVideoFormat(String url) {
        return false;
    }

    /**
     * 是否手动检测webview中加载的url
     *
     * @return
     */
    public boolean manualVideoCheck() {
        return false;
    }

    /**
     * 直播list
     * @return
     */
    public String liveContent(String url) {
        return "";
    }

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client();
    }

    /**
     * 取消请求tag
     */
    public void cancelByTag() {

    }

    /**
     * 销毁
     */
    public void destroy() {}

    /**
     * 爬虫代理
     * @param params
     * @return
     */
    public Object[] proxyLocal(Map< String, String > params) {
        return null;
    }

    public Object[] proxy(Map<String, String> params) {
        return proxyLocal(params);
    }

    public String action(String action) {
        return null;
    }
}

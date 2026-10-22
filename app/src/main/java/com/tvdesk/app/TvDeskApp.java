package com.tvdesk.app;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.receiver.PushReceiver;
import com.github.tvbox.osc.receiver.SearchReceiver;
import com.github.tvbox.osc.util.ImgUtil;
import com.kingja.loadsir.core.LoadSir;
import com.tvdesk.core.bridge.NavBridge;

import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * TVDesk 的 Application。
 *
 * core 层的 App 只负责与 UI 无关的初始化（Hawk / OKGo / Web 服务 / 数据库 / 播放器 / QuickJS），
 * UI 相关的初始化集中在这里，避免 core 反向依赖 app 的资源与控件。
 */
public class TvDeskApp extends App {

    @Override
    public void onCreate() {
        super.onCreate();

        // 占位图资源由 UI 层注入给 core
        ImgUtil.placeholderRes = com.github.tvbox.osc.R.drawable.icon_img_placeholder;

        // core 需要发广播唤起搜索/推送，但 core 不能引用 app 的 Receiver 类，这里注入
        NavBridge.install(SearchReceiver.class, PushReceiver.class);

        // 不内置任何配置源。内容源是用户自己要选的，写死一个等于替用户做了决定，
        // 而且第三方源随时会挂——挂掉时用户会以为是软件坏了。
        // 首次启动由桌面引导到「设置 → 配置地址」。

        // 加载态视图
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                .commit();

        // 屏幕适配
        //
        // 这里过去是 setSupportDP(false) + setSupportSP(false)，等于把 AutoSize 的 density
        // 写入整个关掉（AutoSizeCompat.setDensity 里 `if (isSupportDP())` 才写）。
        // 后果是全局密度停在设备真实的 2.0，而桌面的布局是按 1280×720dp 的设计稿写的：
        // 所有 dp 都胖了 1/3，海报卡从 240×360px 撑到 320×480px，被屏幕底部切掉 60%
        // （实测卡片 y 891–1371，屏高 1080）。
        // dp 缩放必须开着；sp 仍关着，避免字号跟着变。
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(true)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
    }
}

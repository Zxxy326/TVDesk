package com.tvdesk.desk;

import android.os.Bundle;
import android.view.KeyEvent;

/**
 * 音乐模块的独立入口壳。
 *
 * <p>真正的实现在 {@link MusicPage}——桌面已经把音乐内嵌成页（见 DeskActivity
 * 的 module_host），这个 Activity 只剩两个用途：
 * <ul>
 *   <li>adb 直连调试：{@code am start -n com.tvdesk.app/com.tvdesk.desk.MusicActivity}；</li>
 *   <li>权限申请/按键转发/销毁回收这些「必须由 Activity 出面」的系统回调。</li>
 * </ul>
 * 所有业务代码一行都不写在这里。
 */
public class MusicActivity extends DeskAdaptActivity {

    private MusicPage page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 调试壳没有「桌面」可回，胶囊里的「返回桌面」直接结束自己（不能留成死路）
        page = new MusicPage(this, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        setContentView(page);
        page.onEnter();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (page != null && page.handleKeyDown(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (page != null) {
            page.onPermissionsResult(requestCode, grantResults);
        }
    }

    @Override
    protected void onDestroy() {
        if (page != null) {
            page.onRelease();
        }
        super.onDestroy();
    }
}

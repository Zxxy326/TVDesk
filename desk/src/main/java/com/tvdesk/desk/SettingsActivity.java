package com.tvdesk.desk;

import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.tvdesk.core.feed.SourceConfig;
import com.tvdesk.desk.motion.FocusScaler;
import com.tvdesk.desk.motion.PressFx;

/**
 * TVDesk 设置页。独立 Activity，不是 TVBox 那个设置页，也不是桌面里嵌的一块。
 *
 * <h3>为什么单开一个 Activity</h3>
 * <ul>
 *   <li><b>返回键就是导航。</b>按返回直接回桌面，不用在自绘页面栈里实现一套出栈逻辑；
 *       而且桌面在 {@code onResume} 里会比对配置源有没有变（见下），回来自动重扫。</li>
 *   <li><b>和桌面的生命周期解耦。</b>设置页里可能弹输入法、可能跳出去到系统设置，
 *       这些都会让桌面走 onPause/onStop。混在同一个 Activity 里，桌面那套
 *       「焦点引擎 + 页签防抖 + 配置超时」的状态机要额外处理这些中途状态。</li>
 *   <li><b>它本来就是一个独立入口。</b>桌面的「桌面 ▾ → 设置」和顶栏那条
 *       「还没配置内容源」的提示条都指向这里，和桌面是并列关系，不是桌面的子页面。</li>
 * </ul>
 *
 * <h3>层级</h3>
 * <pre>
 *   桌面（DeskActivity）
 *     ├─ 「桌面 ▾」→ 设置          ┐
 *     └─ 顶栏提示条「还没配置内容源」┴→ 本页 → 配置地址弹窗（输入 / 选历史）
 *                                     └→ 电视原生设置（另一个 App）
 * </pre>
 *
 * <h3>和 TVBox 的调用关系</h3>
 * 本页只通过 {@link SourceConfig} 读写 TVBox 的配置，写的是 {@code Hawk} 里的
 * {@code api_url}——就是 TVBox「设置 → 配置地址」那一项。所以：
 * <ul>
 *   <li>在这里配的源，TVBox 的影视页同样生效（不是桌面自己偷偷存一份）；</li>
 *   <li>反过来，用户以前在 TVBox 原版设置里填过的源，会出现在本页的历史列表里；</li>
 *   <li>本页<b>不跳转</b>到 TVBox 的设置页，也不依赖它的任何界面。</li>
 * </ul>
 *
 * <h3>「应用」之后为什么不用重启 App</h3>
 * TVBox 原版换源后会 toast 一句「配置已切换，即将重新加载」然后退回上一页，靠重新进
 * 首页来触发加载。桌面不需要这么粗暴：它 {@code onResume} 时会拿当前源和上次生效的比，
 * 发现变了就 {@code SourceFeed.invalidateConfig()} 再重扫一遍顶栏和内容。
 * 所以本页点完「应用」只要返回桌面就行，加载是桌面自己的事。
 */
public class SettingsActivity extends DeskAdaptActivity {

    private static final String TAG = "SettingsActivity";
    /** 系统「设为默认桌面」返回码，和别的页面错开即可 */
    private static final int REQ_HOME = 0x484F;

    private TextView sourceValue;
    private TextView sourceNote;
    private View rowEditSource;
    private TextView keepPlayingValue;
    private boolean keepPlaying;
    private TextView homeValue;
    private TextView clockValue;
    private TextView weatherValue;
    private boolean showClock;
    private boolean showWeather;
    private TextView updateValue;
    private TextView homeGuardValue;
    private TextView homeGuardNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        com.tvdesk.desk.motion.FocusScaler.disableSystemHighlightTree(getWindow().getDecorView());

        sourceValue = findViewById(R.id.source_value);
        sourceNote = findViewById(R.id.source_note);
        keepPlayingValue = findViewById(R.id.keep_playing_value);
        homeValue = findViewById(R.id.home_value);

        View editSource = findViewById(R.id.row_edit_source);
        View system = findViewById(R.id.row_system);
        View keepPlayingRow = findViewById(R.id.row_keep_playing);
        View homeRow = findViewById(R.id.row_home);
        rowEditSource = editSource;
        // 焦点放大给得比桌面的卡片小：设置行是整行宽的，1.08 会顶出屏幕边
        FocusScaler.attach(editSource, 1.03f);
        FocusScaler.attach(system, 1.03f);
        FocusScaler.attach(keepPlayingRow, 1.03f);
        FocusScaler.attach(homeRow, 1.03f);

        editSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSourceDialog();
            }
        });
        keepPlayingRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 压扁的是药丸自己，不是整行：整行的缩放归 FocusScaler 管，
                // 两个动画写同一个属性会互相把值钉回 1，聚焦中按一次就缩回去了
                PressFx.squish(keepPlayingValue);
                setKeepPlaying(!keepPlaying);
            }
        });
        system.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSystemSettings();
            }
        });
        homeRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestHome();
            }
        });

        View clockRow = findViewById(R.id.row_clock);
        View weatherRow = findViewById(R.id.row_weather);
        clockValue = findViewById(R.id.clock_value);
        weatherValue = findViewById(R.id.weather_value);
        FocusScaler.attach(clockRow, 1.03f);
        FocusScaler.attach(weatherRow, 1.03f);
        showClock = DeskPrefs.showClock(this);
        showWeather = DeskPrefs.showWeather(this);
        clockRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClock = !showClock;
                DeskPrefs.setShowClock(SettingsActivity.this, showClock);
                PressFx.squish(clockValue);
                renderClockWeather();
        renderHomeGuard();
            }
        });
        weatherRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWeather = !showWeather;
                DeskPrefs.setShowWeather(SettingsActivity.this, showWeather);
                PressFx.squish(weatherValue);
                renderClockWeather();
            }
        });

        View homeGuardRow = findViewById(R.id.row_home_guard);
        homeGuardValue = findViewById(R.id.home_guard_value);
        homeGuardNote = findViewById(R.id.home_guard_note);
        FocusScaler.attach(homeGuardRow, 1.03f);
        homeGuardRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!HomeGuardService.isEnabled(SettingsActivity.this)) {
                    // 没开：把他送到系统无障碍页去开
                    HomeGuardService.openSettings(SettingsActivity.this);
                } else {
                    // 已经开着：给一个「临时停用」的出口。
                    // 没有这个出口，用户想回系统桌面时按 HOME 会被一直拽回来，
                    // 只能跑到系统设置里关无障碍——那太远了。
                    askHomeGuard();
                }
            }
        });

        View updateRow = findViewById(R.id.row_update);
        updateValue = findViewById(R.id.update_value);
        FocusScaler.attach(updateRow, 1.03f);
        updateRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate();
            }
        });

        renderSource();
        renderKeepPlaying();
        renderHome();
        renderClockWeather();

        // onCreate 阶段 View 还没 layout，直接 requestFocus 会失败。
        // 初始焦点给「配置地址」：进设置页的人十有八九是来配源的。
        final View first = editSource;
        first.post(new Runnable() {
            @Override
            public void run() {
                first.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统的无障碍设置页回来，状态得重读一次
        renderHomeGuard();
        // 从系统设置回来、或者地址在别处被改过，回来重读一次
        renderSource();
        // 系统桌面选择框是另一个页面，回来时状态可能刚变
        renderHome();
        // 同上：从原生设置页回来时本页可能一个焦点都没有，遥控器第一下会没反应
        rowEditSource.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (getCurrentFocus() == null) {
                    rowEditSource.requestFocus();
                }
            }
        });
    }

    /** 换电视时用来比对尺寸：设置行高按设计稿是 68dp，1080p 上应解析成 102px */
    @Override
    protected String adaptProbe(Resources res) {
        return "设置行高 解析为 " + res.getDimensionPixelSize(R.dimen.settings_row_height) + "px";
    }

    // ---------------------------------------------------------------- 内容源

    /**
     * 把「当前源」那一行画出来。
     *
     * <p>没配源时不只是显示「未配置」，还要说清楚后果（桌面只出豆瓣榜单、顶栏没有类目）——
     * 否则用户看到一个空设置项，不知道是自己没配还是这功能坏的。
     */
    private void renderSource() {
        String url = SourceConfig.current();
        boolean configured = !TextUtils.isEmpty(url);
        if (configured) {
            String builtin = com.tvdesk.core.feed.BuiltinSources.displayName(url);
            sourceValue.setText(TextUtils.isEmpty(builtin) ? url : builtin);
            sourceValue.setTextColor(getResources().getColor(R.color.text_primary));
            sourceNote.setText(R.string.settings_source_in_use);
        } else {
            sourceValue.setText(R.string.settings_source_none);
            sourceValue.setTextColor(getResources().getColor(R.color.text_tertiary));
            sourceNote.setText(R.string.settings_source_none_note);
        }
    }

    private void openSourceDialog() {
        SourceDialog dialog = new SourceDialog(this, SourceConfig.current(), SourceConfig.history(),
                new SourceDialog.Listener() {
                    @Override
                    public void onApply(String url) {
                        applySource(url);
                    }

                    @Override
                    public void onClear() {
                        clearSource();
                    }
                });
        dialog.show();
    }

    /**
     * 应用一个源。这是整条链路里唯一写 TVBox 配置的地方。
     *
     * <p>写完只刷新本页显示，<b>不在这里触发加载</b>：加载归桌面（见类注释）。
     * 也正因为如此，这里不校验地址能不能用——那要把配置拉一遍，是几秒到几十秒的网络操作，
     * 而且和桌面即将做的那次加载完全重复。
     */
    private void applySource(String url) {
        boolean changed = SourceConfig.apply(url);
        Log.i(TAG, "应用内容源：" + url + "（" + (changed ? "有变化" : "和原地址相同") + "）");
        renderSource();
        Toast.makeText(this,
                changed ? R.string.settings_toast_applied : R.string.settings_toast_unchanged,
                Toast.LENGTH_LONG).show();
    }

    private void clearSource() {
        SourceConfig.clear();
        Log.i(TAG, "清空内容源");
        renderSource();
        Toast.makeText(this, R.string.settings_toast_cleared, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 画「切走后续播」这一项。
     *
     * <p>状态压在右侧药丸的 selected 态上，焦点框留给整行：遥控器按确定就是翻状态，
     * 不用像手机那样去点一颗小开关——电视上那颗点不到。
     */
    private void renderKeepPlaying() {
        keepPlaying = DeskPrefs.keepPlayingOnLeave(this);
        keepPlayingValue.setSelected(keepPlaying);
        keepPlayingValue.setText(keepPlaying
                ? R.string.settings_switch_on : R.string.settings_switch_off);
        keepPlayingValue.setTextColor(getResources().getColor(
                keepPlaying ? R.color.text_on_light : R.color.text_secondary));
    }

    private void setKeepPlaying(boolean keep) {
        DeskPrefs.setKeepPlayingOnLeave(this, keep);
        renderKeepPlaying();
    }

    // ---------------------------------------------------------------- 桌面

    /** 状态药丸：已接管就常亮，没接管就保持和其它开关一样的暗态 */
    private void renderHome() {
        boolean home = HomeRole.isDefault(this);
        homeValue.setSelected(home);
        homeValue.setText(home ? R.string.settings_home_on : R.string.settings_home_off);
        homeValue.setTextColor(getResources().getColor(
                home ? R.color.text_on_light : R.color.text_secondary));
    }

    /**
     * 弹系统自己的「设为默认桌面」。
     *
     * <p>已经是默认桌面时不再重复请求，直接打开系统的桌面设置页——用户这时按它
     * 多半是想换回电视自带的桌面，把人留在一个没有变化的确认框里没有意义。
     */
    /**
     * 检查更新：拉清单 → 有新版就问一句 → 下载 → 拉起安装。
     *
     * <p>更新地址没填时直接把话说清楚，不要点了没反应。
     */
    private void checkUpdate() {
        final String manifest = DeskPrefs.updateManifestUrl(this);
        if (manifest.isEmpty()) {
            android.widget.Toast.makeText(this,
                    "还没填更新地址（设置里的更新源），填了才能检查",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        updateValue.setText("检查中");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.tvdesk.desk.update.UpdateClient.Info info =
                        com.tvdesk.desk.update.UpdateClient.check(
                                getApplicationContext(), manifest);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateValue.setText(R.string.settings_update_check);
                        if (info == null) {
                            android.widget.Toast.makeText(SettingsActivity.this,
                                    com.tvdesk.desk.update.UpdateClient.lastError(),
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        String mine = com.tvdesk.desk.update.UpdateClient
                                .currentVersionName(SettingsActivity.this);
                        if (!info.newer) {
                            android.widget.Toast.makeText(SettingsActivity.this,
                                    "已是最新（" + mine + "）",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        askToUpdate(info, mine);
                    }
                });
            }
        }, "desk-update").start();
    }

    /** 有新版：问一句再下 */
    private void askToUpdate(final com.tvdesk.desk.update.UpdateClient.Info info, String mine) {
        String notes = info.notes == null || info.notes.isEmpty()
                ? "（这次没写更新说明）" : info.notes;
        new android.app.AlertDialog.Builder(this, R.style.DeskDialogTheme)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage("当前 " + mine + "\n\n" + notes)
                .setPositiveButton("下载并安装", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloadUpdate(info);
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void downloadUpdate(final com.tvdesk.desk.update.UpdateClient.Info info) {
        updateValue.setText("下载中");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.io.File apk = com.tvdesk.desk.update.UpdateClient
                        .download(getApplicationContext(), info);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateValue.setText(R.string.settings_update_check);
                        if (apk == null) {
                            android.widget.Toast.makeText(SettingsActivity.this,
                                    com.tvdesk.desk.update.UpdateClient.lastError(),
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (!com.tvdesk.desk.update.UpdateClient.install(
                                SettingsActivity.this, apk)) {
                            android.widget.Toast.makeText(SettingsActivity.this,
                                    "安装包下好了，但打不开安装界面",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "desk-update-dl").start();
    }

    /** 已经接管时点这一行：临时停用 / 去系统里关掉 */
    private void askHomeGuard() {
        boolean paused = !DeskPrefs.homeGuard(this);
        new android.app.AlertDialog.Builder(this, R.style.DeskDialogTheme)
                .setTitle("无障碍接管已开启")
                .setMessage(paused
                        ? "现在是暂停状态：服务还在，但按 HOME 不会拉回来了。"
                        : "按 HOME 会回到 TVDesk。想临时用回电视自带的桌面，就先停用。")
                .setPositiveButton(paused ? "恢复接管" : "临时停用",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                boolean now = !DeskPrefs.homeGuard(SettingsActivity.this);
                                DeskPrefs.setHomeGuard(SettingsActivity.this, now);
                                renderHomeGuard();
                            }
                        })
                .setNegativeButton("去系统设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        HomeGuardService.openSettings(SettingsActivity.this);
                    }
                })
                .show();
    }

    /**
     * 无障碍接管那一行：开关文字跟着系统里的授权状态走。
     *
     * <p>不看自己的 prefs —— 用户可能在系统设置里把无障碍关掉了，
     * 这里必须如实显示「没开」，否则他以为已经在接管，按 HOME 却发现回了系统桌面。
     */
    private void renderHomeGuard() {
        if (homeGuardValue == null) {
            return;
        }
        boolean enabled = HomeGuardService.isEnabled(this);
        boolean paused = enabled && !DeskPrefs.homeGuard(this);
        homeGuardValue.setText(enabled && !paused
                ? R.string.settings_switch_enabled : R.string.settings_switch_disabled);
        if (homeGuardNote != null) {
            if (!enabled) {
                homeGuardNote.setText("未启用 · 点这里去系统无障碍里打开 TVDesk");
            } else if (paused) {
                homeGuardNote.setText("已暂停 · 无障碍开着但不再拉回；点这里可以恢复");
            } else {
                homeGuardNote.setText("已启用 · 按 HOME 会回到 TVDesk；点这里可以临时停用");
            }
        }
    }

    /** 时钟 / 天气两个开关的文字 */
    private void renderClockWeather() {
        if (clockValue != null) {
            clockValue.setText(showClock
                    ? R.string.settings_switch_enabled : R.string.settings_switch_disabled);
        }
        if (weatherValue != null) {
            weatherValue.setText(showWeather
                    ? R.string.settings_switch_enabled : R.string.settings_switch_disabled);
        }
    }

    private void requestHome() {
        if (HomeRole.isDefault(this)) {
            if (!HomeRole.openHomeSettings(this)) {
                Toast.makeText(this, R.string.settings_toast_home_failed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!HomeRole.request(this, REQ_HOME)) {
            Toast.makeText(this, R.string.settings_toast_home_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME) {
            renderHome();
            Toast.makeText(this, HomeRole.isDefault(this)
                    ? "已设为电视桌面 · 按 HOME 键就能回来"
                    : "还没设为默认桌面", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- 系统设置

    /**
     * 跳电视原生的设置。
     *
     * <p>这就是需求里「打开电视系统本身设置」那一项。它和内容源没有任何关系——
     * 网络、显示、声音、HDMI 都在那边，本页不复制一份。
     *
     * <p>三级兜底的顺序（标准 action → 无线设置 → 厂商包名）在 {@link SystemSettings} 里，
     * 信号源页用的是同一条通路。
     */
    private void openSystemSettings() {
        if (SystemSettings.open(this)) {
            return;
        }
        Toast.makeText(this, R.string.settings_toast_no_system_settings, Toast.LENGTH_LONG).show();
    }
}

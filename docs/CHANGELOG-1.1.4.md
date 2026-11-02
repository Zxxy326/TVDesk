# TVDesk 1.1.4

版本：1.1.4（versionCode 15）
日期：2026-11-02

主题：**待机唤醒 / 关机重启都能自己回到 TVDesk**（对照 aerial-tv 逐项对齐后修掉的真问题）。

---

## 结论先行：之前一直失败的原因

不是「开机自启没写对」，而是**通知监听没被系统真正认领**。

我们原来用 `Settings.Secure.putString("enabled_notification_listeners", …)` 直接把组件名
写进设置。字符串是写进去了，但系统的通知管理服务并没有把这个组件当成「自己的监听」——
于是待机强停时，它把这一条一起擦掉了：

```
待机 25 秒后：
  ps -A | grep tvdesk        → 0        （进程被杀）
  dumpsys package … stopped=  → true     （force-stop 级）
  enabled_accessibility_services → null  （无障碍绑定被清）
  enabled_notification_listeners → 我们的条目被移除  ← 复活链断在这里
```

照 Aerial 的安装脚本换成**官方注册**（`cmd notification allow_listener`）之后，
条目活下来了，系统在绑定断开后自己重绑（约 0.5 秒）把进程拉起来，守护补回无障碍绑定，
一切才转起来。应用内现在也会调 `NotificationListenerService.requestRebind()` 主动让系统绑一次。

## 这条链路的完整时序（实机日志）

```
22:46:48.782  DeskRevival: 通知监听已连接（系统已运行 111864 秒）   ← 系统重绑，进程被拉起
22:46:48.862  HomeGuard  : 守护已连接：flags=32                     ← 补回无障碍绑定（82ms）
22:47:03.869  HomeGuard  : 连接后复查：前台=（空）强停前在桌面=true，拉回 TVDesk
```

## 另外两个判断逻辑的坑（都是「为什么还是停在小米桌面」的直接原因）

**1. 复活后主动放弃接管。**
`RevivalListener` 里有个 `BOOT_WINDOW_MS = 5 分钟` 的门槛，判的是**开机时长**。
这台电视的系统连续跑了几天不重启，`elapsedRealtime` 早就是几十万秒，唤醒时必然超出窗口，
于是日志写着「已经过了开机窗口期，只补绑定、不抢前台」——补完绑定就走了。

**2. 复查把「屏幕上什么都还没画」当成「用户在别的 App」。**
守护连接后按 1.5s/3s/7s/15s 复查前台，原来的判断是「前台必须是原桌面才拉回」。
可唤醒那几秒 `mCurrentFocus` 是空的（固件桌面还没画出来），于是连续三枪全部放弃，
一直要等到 15 秒后原桌面真正出现才靠窗口事件拉回。

现在的判断（对齐 Aerial 的 `stockHomes` 语义）：

- 前台是**原桌面** → 拉回；
- 前台是**空的**，并且我们有用量统计权限（能确认「真的什么都没有」）→ 拉回；
- 前台是**过渡窗口**（SystemUI、锁屏、固件启动页），而**强停前用户就停在 TVDesk 上** → 拉回；
- 前台是**用户能自己打开的 App** → 不打扰。

「强停前用户停在哪儿」是新加的持久标记（`was_home_before_kill`）：守护每看到一次窗口变化
就记一笔。待机强停时来不及判断，但那一笔已经写在磁盘上了。**只把「有启动入口的包」算作 App**——
过渡窗口没有启动入口，不能拿它清掉这个标记（第一版就是这么错的，标记被 SystemUI 清掉，
于是又白等 15 秒）。

## 顺手补的两件事

**保持 ADB。** 这台固件在唤醒/重启时会把 `global adb_enabled` 改成 0，之后网络调试直接断，
遥控器怎么点都连不回来。守护现在用 ContentObserver 盯着这个值，被关掉立刻写回 1
（Aerial 的 `KEEP_ADB=1`）。设置 → 桌面 → 「保持 ADB 调试」可以关掉。实测：
重启后日志 `GuardSetup: ADB 被系统关掉了，已写回开启`，adb 全程没断。

**HOME 键广播。** 按下 HOME 时系统会发 `CLOSE_SYSTEM_DIALOGS`（reason=homekey），比
「原桌面窗口出现」早 200–300ms。拿它当发令枪，在固件桌面画出来之前就动手。
注意 `adb shell input keyevent HOME` 注入的按键不发这条广播，测这条路径只能用实体遥控器。

## 实测结果（MiTV-MFTR0 / Android 14 / OS3.0.101）

| 场景 | 结果 |
| --- | --- |
| 待机 20 秒 → 唤醒 | 前台直接是 `com.tvdesk.app`，不用手点 |
| `adb reboot` 真重启 | 1.6 秒后自动拉回（日志：`前台=com.mitv.tvhome 强停前在桌面=true，拉回 TVDesk`）|
| 重启后授权状态 | 无障碍绑定、通知监听都还在；`stopped=false` |
| 重启后 ADB | 被系统关掉后 3 秒内写回，连接没断 |

## 关于包名（重要，但这次没改）

用户问「和应用包名有没有关系」——**有关系，但那条路不建议走**。

小米的 HOME 白名单在 `com.android.server.pm.MitvSettings.isThirdpartyHomeAllowed(pkg)`
→ `isAppInWhitelistWithFlag(pkg, 4)`。白名单来自云控下发的
`/data/system/mitv_security_center.xml`（system 0600，第三方读不到、也写不了），
开机时会被打到日志里。这台机器实测 **127 条**，其中带 HOME 位（4）的有 42 个，
`com.tvdesk.app` 不在里面 —— 这就是 `cmd package set-home-activity` 返回 Success、
`cmd role get-role-holders` 也返回我们的包名，但 `resolve-activity` 仍然解析到
`com.mitv.tvhome` 的原因。

Aerial 的作者试过这条路：把包名改成白名单里的（例如 `com.ts.launcher`），HOME 会变成
**桌面选择框**而不是直接进；必须同时 `pm disable-user com.mitv.tvhome` 才能真正接管。
代价是：① 改包名等于装一个新 App，配置和授权全部重来；② 一旦小米在云控里删掉那个包名，
电视会落到 `FallbackHome` 黑屏（FallbackHome 不认第三方 HOME）；③ 白名单条目属于别的公司。
所以他最后放弃了，改用桌面守护。我们同样不走这条路 —— 守护现在能把待机、重启、HOME 键
三条路都覆盖掉。

## 附：新装机器建议跑一遍（只有 adb 能给的授权）

```bash
P=com.tvdesk.app
adb shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
adb shell appops set $P GET_USAGE_STATS allow
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener $P/com.tvdesk.desk.RevivalListener
```

第一条让应用能自己写无障碍/ADB 设置，第二条让它在「屏幕刚亮、无障碍还没绑上」那 1 秒里
也能判断前台是谁，第四条是官方注册复活监听（**必须用 `cmd`，直接改设置字符串不算数**）。

# TVDesk 1.1.3

版本：1.1.3（versionCode 14）
日期：2026-11-02

## 1. 搜索：历史 + 拼音结论

**搜索链路本身是通的**。实测输入 `HBLD`：

```
DetailFeed: 搜索源「💓花卷┃4K💓」无结果（type=3）
DetailFeed: 搜索源「💓玩偶┃4K💓」无结果（type=3）
... 共 13 个源，全部无结果
```

这 13 个源都是 catvod 爬虫包的各站，站内搜索只认中文关键词，**不支持拼音首字母**。
TVBox 用同样的源也一样搜不到 —— 拼音能不能用取决于源，不是客户端。

本轮改动：
- 新增**搜索历史**（`SearchHistory`，最近 10 条，存在预置里）；
  进搜索页、或搜索无结果时显示「最近搜索」，遥控器一点就重搜；
- 搜索时**自动记一笔**，重复词挪到最前，不产生重复条目；
- 输入是纯字母又没结果时，空态直接说清楚「当前这批源不支持拼音首字母搜索」。

## 2. 播放记录续播：补一条不依赖数据库的兜底

内核本来就有 `vodRecord` 表 + `applyResume()`，但实机上不稳：数据库最后修改时间
停在昨天，今天播放完全没有写入，所以每次点进去都是第一集。

- 新增 `ResumeStore`：`源key|片id → 集数|集名|时间`，存 SharedPreferences，
  最多 60 部，按时间淘汰；
- 写：`recordResumePoint()` 除了写 Room，再写一份 prefs；
- 读：Room 读不到、或读出来是第 0 集时，用 prefs 那份兜底；
- 一集之内的秒数不用另存 —— 播放器按「源+id+线路+集数+集名」把进度存在缓存里，
  集数对上就会自动 seek 回上次退出的位置。

## 3. 待机唤醒（仍然无解，记录证据）

又试了三种手段，全部无效：

| 尝试 | 结果 |
| --- | --- |
| `dumpsys deviceidle whitelist +com.tvdesk.app` | 待机后仍 `stopped=true` |
| `appops set SYSTEM_ALERT_WINDOW allow`（Aerial 脚本里有这条） | 同上 |
| 前台服务保活 | 同上，且要在通知栏常驻一条，已移除 |

待机 25 秒后固定复现：进程 0、`stopped=true`、无障碍绑定 null、
`enabled_notification_listeners` 里我们的条目被移除、闹钟 0 条。
这是固件的 force-stop 级清理，应用层没有绕过手段。

唯一没验证过的路径：把 TVDesk 设为**默认桌面**后重启一次
（`cmd package set-home-activity` 返回 Success、`cmd role get-role-holders HOME`
也确实返回 com.tvdesk.app，但 `resolve-activity` 仍解析到 com.mitv.tvhome，
按 HOME 也仍去原生桌面 —— role 没有真正生效，可能需要重启系统）。

# TVDesk 1.1.1

版本：1.1.1（versionCode 12）
日期：2026-11-02

主题：**开机直接进 TVDesk**。

## 现象

电视重启后落在小米自带桌面（`com.mitv.tvhome`），不是 TVDesk；但开机之后再打开一次
TVDesk，HOME 键接管是正常的。

## 两个根因

**1. 开机广播被一个门槛拦死了。**

`BootReceiver` 里写着「只有 `HomeRole.isDefault()` 为真才拉起 TVDesk」，本意是
「没设成默认桌面却抢着弹出来，就成了关不掉的开机广告」。这个前提在国产固件上不成立：
小米电视的桌面是 `com.mitv.tvhome`，系统根本不提供「把第三方应用设为默认桌面」的入口
（`cmd package resolve-activity HOME` 永远返回 mitv），于是 `isDefault` 永远是 false，
**开机一次都没拉过**。

**2. 守护服务在开机时"看不见"桌面。**

`HomeGuardService` 靠窗口变化事件判断「原桌面起来了没有」。开机时序是：
固件桌面先显示 → 无障碍服务才连上。那些窗口事件在服务连上之前就发生完了，
服务这边一条都收不到，所以它一直不动，直到用户按下 HOME。

## 改法

**BootReceiver**
- 去掉 `isDefault` 门槛，改为只跟随「桌面守护」开关（关掉守护＝不接管＝不自启，语义一致）；
- 加 `MY_PACKAGE_REPLACED`：覆盖安装后也拉一次，免得升级完停在原生桌面让人以为坏了；
- `exported` 改为 true（部分固件只把 BOOT_COMPLETED 交给 exportable 的接收器；
  这些 action 都是系统受保护广播，外部发不出来）。

**HomeGuardService**
- 服务连上后按 3s / 8s / 15s 三次主动复查前台（不再只等窗口事件）；
- 复查用 `getRootInActiveWindow()` 读前台包名，所以无障碍配置打开 `canRetrieveWindowContent`
  （只取 packageName，不读内容）；
- 只有「前台是别的桌面」才拉回；前台是普通应用、是 TVDesk、或在放行期内，一律不动。

这样即使 BOOT_COMPLETED 被固件的自启动管理拦掉，服务连上后 3 秒也会把桌面拉回来。

## 验证

- 覆盖安装 → 自动拉起 TVDesk（`DeskBoot: 升级后拉起 TVDesk`）
- 守护重绑（versionCode 变化触发）→ `HomeGuard: 守护已连接：flags=32 按键过滤=true`
- 按 HOME → `HomeGuard: 接管 HOME：从 com.mitv.tvhome 回到桌面`，前台仍是 TVDesk

真正的「关机→开机」需要实机重启验证（adb shell 在这台机器上没有权限启动系统桌面/
发送受保护广播，无法完整模拟）。

# TVDesk 1.1.13

版本：1.1.13（versionCode 24）
日期：2026-11-07

主题：**安装与维护工具（方案 M3）+ 守护自愈的两个反作用修掉**。

---

## 一、工具

新增 `tools/tvdesk-setup.sh`（macOS / Linux / Git Bash）与 `tools/tvdesk-setup.ps1`
（Windows PowerShell 5.1+），行为一致，七个动作：

| 动作 | 作用 |
|---|---|
| `detect` | 只读探测：型号、系统、架构、已装版本与模式、默认桌面、各项授权 |
| `install` | 选包安装/覆盖，改之前存快照 |
| `activate` | 按需激活：标准版设默认桌面，守护版开无障碍 |
| `verify` | 回读状态，可选实测按 HOME |
| `restore` | 只撤回本工具改过的项 |
| `report` | 脱敏诊断（不带序列号与 IP） |
| `connect` | 连一台用户明确给出地址的电视 |

几条刻意的限制：不扫网段、多设备必须显式指定、失败当场报、还原不整表覆盖。

## 二、修了两个「自以为在帮用户」的行为

### 1. 通知监听重连会把授权绑回去

`RevivalListener.onListenerConnected()` 里 `ensureEnabled()` 排在开关检查**之前**：

```java
GuardSetup.ensureEnabled(this);        // 先补授权
if (!DeskPrefs.homeGuard(this)) return; // 再看用户开没开
```

系统重新绑定通知监听是常事（有通知就绑），于是用户在应用里关掉守护之后，
下一次通知进来它又把无障碍授权装回去。表现是「关了守护，过一会儿自己又开了」。
连工具的 `restore` 都删不掉：删完十几秒就长回来（实测 12 秒）。

改成先看开关，关着就什么都不做。

### 2. 停用守护没有撤掉复活闹钟

闹钟一分钟一次，`onReceive` 里虽然会看开关，但服务侧的自愈不只看它。
现在停用时一并 `cancelReviveAlarm()`，并且闹钟接收器本身也照旧先判开关。

## 三、给工具留的两个广播

`GuardCommandReceiver`，exported，只认三个 action：

- `com.tvdesk.app.action.DISABLE_GUARD`：关守护 + 撤闹钟
- `com.tvdesk.app.action.ENABLE_GUARD`：开守护 + 排闹钟
- `com.tvdesk.app.action.GUARD_STATE`：应用回执（日志可见）

`restore` 依赖第一条：先让应用停手，再删系统侧的授权，否则删不干净。
`activate` 依赖第二条：光写系统设置不够，应用只认自己的开关。

**为什么敢 exported**：这两条只改本应用自己的开关，不授予任何系统权限。
真正危险的是系统侧授权（无障碍名单），那一步始终由工具写、并且在用户
明确确认之后才写；应用自己不会去写它。

## 四、实机验证（MiTV-MFTR0 / Android 14）

| 场景 | 结果 |
|---|---|
| `detect` | 认到 1.1.13 / standard / 无障碍已授权 / 服务已连接 |
| `install`（同版本覆盖） | 快照写入、`Success`、回读版本一致 |
| `restore` | 先广播关守护，再删授权，20 秒后仍是 `null` |
| `restore` 保留第三方服务 | 通知监听里另一家的服务未被改动 |
| `activate --mode guard` | 写入授权 + 翻应用开关，服务 1 秒内连上 |
| `verify --test-home` | 从系统设置按 HOME → `com.tvdesk.app/com.tvdesk.desk.DeskActivity` |

`restore` 修好之前的表现（同一台机器）：删掉 → 4 秒后进程被拉起 → 12 秒后授权回来了。

## 五、没做的

- **M5 真机矩阵**：待机 10 轮、冷启动、直接卸载，需要多台机器轮跑，本轮未做。
- **机型能力探测**：`device-profiles.json` 仍是上游报告，未逐台复验，只作解释。
- **ADB 配对流程**（Android 11+ 无线调试）：工具支持 `connect IP:PORT`，
  没有做配对码输入界面。

## 六、升级注意

本版不改变包名与签名，standard / guard 均可直接覆盖安装。
如果之前用 `restore` 关过守护，升级后需要去「设置 → 桌面 → 桌面守护」重新打开
（工具 `activate --mode guard` 也可以）。

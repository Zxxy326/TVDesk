# TVDesk 安装与维护工具

两个脚本，行为完全一致：

- `tvdesk-setup.sh` —— macOS / Linux / Windows 的 Git Bash
- `tvdesk-setup.ps1` —— Windows PowerShell 5.1 及以上

它们只做一件事：**把「装上 TVDesk 并让它真的接管 HOME」这件事讲清楚、做干净，并且能退回来。**

## 七个动作

```
detect     只读探测：型号、系统、架构、已装版本与模式、默认桌面、各项授权
install    选包安装/覆盖更新，改之前先存快照
activate   按需激活：标准版设默认桌面，守护版开无障碍
verify     回读状态，可选实测按 HOME
restore    撤回本工具改过的东西，保留 APK 和用户数据
report     生成脱敏诊断（型号/系统/状态，不带序列号与 IP）
connect    连一台你明确给出地址的电视
```

```bash
# 1. 连电视（USB 也行，插上就自动认）
./tvdesk-setup.sh connect 192.168.1.20

# 2. 看看这台机器什么情况，以及建议装哪个模式
./tvdesk-setup.sh detect

# 3. 装
./tvdesk-setup.sh install

# 4. 激活
./tvdesk-setup.sh activate --mode standard    # 设默认桌面
./tvdesk-setup.sh activate --mode guard       # 开无障碍

# 5. 复核
./tvdesk-setup.sh verify --test-home

# 出问题就把改过的退回去
./tvdesk-setup.sh restore
```

PowerShell 版参数名一样，只是用 `-Mode`、`-Device`、`-TestHome`、`-Yes`：

```powershell
.\tvdesk-setup.ps1 detect -Device 192.168.1.20:5555
.\tvdesk-setup.ps1 install -Mode guard
.\tvdesk-setup.ps1 verify -TestHome
.\tvdesk-setup.ps1 restore
```

## 它会改什么、不会改什么

| 动作 | 会改 | 不会做 |
|---|---|---|
| `detect` | 什么都不改 | 不写设置、不装包 |
| `install` | 安装/覆盖 TVDesk | 不卸载、不清数据、不关系统校验 |
| `activate --mode standard` | 默认桌面 → TVDesk | 不碰无障碍 |
| `activate --mode guard` | 无障碍名单追加一项 | 不整表覆盖，其他服务原样保留 |
| `restore` | 从两项名单里**只移除 TVDesk** | 不卸载、不清数据、不恢复出厂设置 |

`restore` 的顺序是有讲究的：

1. 先让应用自己**关掉守护**（广播 `com.tvdesk.app.action.DISABLE_GUARD`），
   否则它会在十几秒内把删掉的授权绑回去——这一步不做，还原就是个假动作；
2. 再删名单里的本应用组件，**保留**名单里其他服务；
3. 最后恢复快照里记的默认桌面（那个组件还在才写）。
4. 空列表用 `settings delete` 而不是 `put ""`：部分 ROM 上后者直接报 `Bad arguments`。

## 快照

每次改动前在用户目录写一份快照，**不进仓库、不上传**：

- Windows：`%USERPROFILE%\.tvdesk-setup\session-*.env`
- macOS / Linux：`~/.tvdesk-setup/session-*.env`

里面记原默认桌面、两项授权名单、已装版本与模式、目标包名和结果。

## 几条刻意的限制

- **不扫网段**。要连哪台得你告诉它（`connect`）。扫描等于在别人的电视上敲门。
- **多设备必须指定**。没有 `--device` 又有两台连着时，直接停下来列清单，
  不替用户挑——挑错就是装到别人的电视上。
- **失败当场报**。安装失败会区分签名冲突、架构不匹配、版本降级、系统限制，
  不会忽略一堆错误再统一打印「全部完成」。
- **改了要能退**。`restore` 只动本工具写过的项；用户后来自己改过的设置，
  宁可标冲突也不强行回滚。

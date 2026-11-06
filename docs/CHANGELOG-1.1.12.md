# TVDesk 1.1.12

版本：1.1.12（versionCode 23）
日期：2026-11-07

主题：**standard / guard 双模式分包**（方案 M2）+ **机型规则**（M4）。

---

## 一、为什么要两个模式

同一份 APK，有些电视装得上、有些装不上。差别在清单里有没有 `HOME`：

- **standard**：声明了 HOME，用户可以把它设为默认桌面，按 HOME / 开机直接进来。
  这条路不需要无障碍，是最干净的接管方式。
- **guard**：**不声明 HOME**，装上去就是一个普通应用，靠无障碍守护接管 HOME 键。
  用于「固件拒绝安装桌面应用」或「装上了也不给设为默认桌面」的机器。

两个模式**同包名同签名**，只能装一个；切换就是覆盖安装，数据保留。

## 二、构建结构

`flavorDimensions "home", "mode"`，六种 release 组合：

| 维度 | 值 |
|---|---|
| home | standard、guard |
| mode | java（双 ABI）、java32、java64 |

任务名按维度顺序拼：`assembleStandardJava32Release`、`assembleGuardJava64Release`…

`build.sh` 也跟着改了：

```bash
./build.sh java32          # standard java32 debug
./build.sh guard java32    # guard java32 debug
./build.sh release         # 六个包，standard 沿用原文件名，guard 带 -guard-
```

minSdk 统一到 **21**（`java32` 原来是 19）。

## 三、清单

- `desk/src/main/AndroidManifest.xml`：只剩 LAUNCHER / LEANBACK_LAUNCHER 公共入口。
- `app/src/standard/AndroidManifest.xml`：给 DeskActivity 补 HOME + DEFAULT。
- `app/src/guard/AndroidManifest.xml`：只有 `tvdesk.home_mode=guard` 元数据，没有任何 HOME 声明。

实测合并后的清单：standard 的 `category.HOME` 计数 1，guard 0。

## 四、模式感知

- `HomeMode`：desk 从 `ApplicationInfo.metaData` 读 `tvdesk.home_mode`，不反向依赖 app 的 BuildConfig。
  读不到按 standard 处理——老版本升上来的都是 standard，判成 guard 会让入口凭空消失。
- `HomeRole.supported()`：guard 版直接 false，`isDefault` / `request` 都不再走系统流程。
- 设置页：guard 版「设为默认桌面」显示「守护版不支持」，点它给一句说明而不是静默无反应。
- `UpdateClient`：guard 读 `guardAssets`，**没有就报「这个版本没有守护版安装包」，绝不回落到 standard**——
  装回去 HOME 声明就回来了，在那些拦 HOME 应用的机器上等于把能用的版本搞成装不上。

## 五、机型规则（M4）

内置 `desk/src/main/assets/device-profiles.json`，照搬 aerial-tv 的 `devices.json`（MIT），
11 条，字段原样保留，追加 `homeTakeover` 和 `bootStockMs`。

`DeviceProfile` 按 `ro.product.model` 匹配第一条，目前只用于两件事：

- 设置页「桌面守护」卡片附一句机型备注；
- 给将来「开机先让原厂桌面显示一会儿」留字段。

**这些是上游报告，本项目没有逐台复验**，所以只作解释，不参与接管判定。

## 六、验证

| 项 | 结果 |
|---|---|
| standard 合并清单 | 含 `category.HOME` |
| guard 合并清单 | 不含 HOME，含 `tvdesk.home_mode=guard` |
| 六变体构建 | 通过，签名产物齐全 |

## 七、还没做（M3、M5）

- **M3 安装工具**：`tools/tvdesk-setup.ps1` / `.sh`（Detect / Install / Verify / Restore / Report）。
- **M5 全量验收**：真机矩阵（待机、冷启动、直接卸载）、P50/P95 记录。

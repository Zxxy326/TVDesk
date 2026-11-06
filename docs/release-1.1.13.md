## 本次更新：安装与维护工具

### 新增命令行工具
仓库里的 `tools/tvdesk-setup.sh`（macOS/Linux/Git Bash）和 `tools/tvdesk-setup.ps1`（Windows）：
探测、安装、激活、复核、还原、出诊断，一条命令一件事。

```bash
./tvdesk-setup.sh connect 192.168.1.20
./tvdesk-setup.sh detect
./tvdesk-setup.sh install
./tvdesk-setup.sh activate --mode guard
./tvdesk-setup.sh verify --test-home
./tvdesk-setup.sh restore
```

### 修了两个「守护关不掉」的毛病
- **关了守护过一会儿自己又开**：通知监听每次被系统重新绑定，都会把无障碍授权补回去。
  现在先看用户的开关，关着就不补。
- **还原删不掉**：删完十几秒就长回来。现在还原会先让应用停手，再删系统侧授权，删得干净了。
- 另外，停用守护时会一并撤掉一分钟一次的复活闹钟。

### 安装失败会说清楚原因
签名冲突、架构不匹配、版本降级、系统限制，分别报出来，不再统一显示「安装失败」。

### 升级提示
本版不换包名和签名，可直接覆盖安装。之前用工具还原过的，需要重新打开「桌面守护」。

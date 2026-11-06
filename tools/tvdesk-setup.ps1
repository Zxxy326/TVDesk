<#
.SYNOPSIS
  TVDesk 安装与维护工具（Windows PowerShell 5.1+）

.DESCRIPTION
  Detect / Install / Activate / Verify / Restore / Report / Connect 七个动作。
  说明与 bash 版 tools/tvdesk-setup.sh 完全一致，只是换了个壳。

  几条硬规则（来自《桌面守护安装与机型适配执行方案》第 6、7 节）：
    - Detect 只读，不写任何东西；
    - 不扫网段、不猜密码、多设备必须显式指定；
    - 每次改动前存快照，还原时只动本工具写过的项；
    - 任何一步失败都当场报出来，不攒到最后统一打「全部完成」。

.EXAMPLE
  .\tvdesk-setup.ps1 detect
  .\tvdesk-setup.ps1 connect 192.168.1.20
  .\tvdesk-setup.ps1 install -Mode standard
  .\tvdesk-setup.ps1 verify -TestHome
  .\tvdesk-setup.ps1 restore
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('detect', 'install', 'activate', 'verify', 'restore', 'report', 'connect')]
    [string]$Action = 'detect',

    [Parameter(Position = 1)]
    [string]$Target = '',

    [string]$Device = '',
    [string]$Apk = '',
    [ValidateSet('', 'standard', 'guard')]
    [string]$Mode = '',
    [string]$Dist = '',
    [string]$Adb = '',
    [string]$Out = '',
    [switch]$TestHome,
    [switch]$Yes
)

$ErrorActionPreference = 'Continue'
$script:Pkg = 'com.tvdesk.app'
$script:A11yComp = 'com.tvdesk.app/com.tvdesk.desk.HomeGuardService'
$script:NotifComp = 'com.tvdesk.app/com.tvdesk.desk.RevivalListener'
$script:HomeAct = 'com.tvdesk.app/com.tvdesk.desk.DeskActivity'
$script:FailCount = 0
$script:WarnCount = 0
$script:Snapshot = ''

if (-not $Dist) { $Dist = Join-Path (Split-Path -Parent $PSScriptRoot) 'dist' }
if (-not $env:TVDesk_STATE) { $script:StateDir = Join-Path $env:USERPROFILE '.tvdesk-setup' }
else { $script:StateDir = $env:TVDesk_STATE }
if (-not $Adb) { $Adb = 'adb' }
$script:AdbPath = $Adb

# ---------------- 输出 ----------------

function Say  { param([string]$m) Write-Host $m }
function Ok   { param([string]$m) Write-Host "[ OK ] $m" -ForegroundColor Green }
function Warn { param([string]$m) Write-Host "[警告] $m" -ForegroundColor Yellow; $script:WarnCount++ }
function Fail { param([string]$m) Write-Host "[失败] $m" -ForegroundColor Red; $script:FailCount++ }
function Step { param([string]$m) Write-Host "`r`n== $m" -ForegroundColor Cyan }

function Confirm-It {
    param([string]$Question)
    if ($Yes) { return $true }
    $ans = Read-Host "$Question [y/N]"
    if ($ans -match '^(y|Y|yes|YES)$') { return $true }
    return $false
}

function Mask-Id {
    param([string]$s)
    if (-not $s) { return '' }
    if ($s.Length -le 4) { return $s }
    return '***' + $s.Substring($s.Length - 4)
}

# ---------------- adb ----------------

function Invoke-A {
    param([string[]]$AdbArgs, [switch]$NoDevice)
    if ($NoDevice) { return & $script:AdbPath @AdbArgs 2>&1 }
    return & $script:AdbPath -s $script:Dev @AdbArgs 2>&1
}

function Invoke-S {
    param([string[]]$ShellArgs)
    $env:MSYS_NO_PATHCONV = '1'
    return & $script:AdbPath -s $script:Dev shell @ShellArgs 2>&1
}

function Get-Prop {
    param([string]$Name)
    $v = Invoke-S @('getprop', $Name)
    if ($null -eq $v) { return '' }
    return ($v -join "`n").Trim()
}

function Pick-Device {
    $out = & $script:AdbPath devices 2>&1
    $list = @()
    foreach ($line in $out) {
        if ($line -match '^(\S+)\s+device$') { $list += $Matches[1] }
    }
    if ($Device) {
        if ($list -contains $Device) { $script:Dev = $Device; return $true }
        Say "设备 $Device 不在已连接列表里。当前："
        if ($list.Count -eq 0) { Say '  （无）' } else { $list | ForEach-Object { Say "  $_" } }
        return $false
    }
    if ($list.Count -eq 0) {
        Say '没有已连接的设备。'
        Say '  USB：接好线后确认电视弹出「允许调试」并同意。'
        Say '  网络：先 .\tvdesk-setup.ps1 connect 电视IP'
        return $false
    }
    if ($list.Count -gt 1) {
        Say '同时连着多台设备，请用 -Device 指定目标：'
        $list | ForEach-Object { Say "  $_" }
        return $false
    }
    $script:Dev = $list[0]
    return $true
}

function Get-DeviceAbi {
    $list = Get-Prop 'ro.product.cpu.abilist'
    if (-not $list) { $list = Get-Prop 'ro.product.cpu.abi' }
    if ($list -match 'arm64-v8a') { return 'arm64-v8a' }
    if ($list -match 'armeabi-v7a') { return 'armeabi-v7a' }
    return ''
}

function Get-InstalledVersion {
    $txt = (Invoke-S @('dumpsys', 'package', $script:Pkg)) -join "`n"
    if ($txt -match '(?m)^\s*versionName=(\S+)') { return $Matches[1] }
    return ''
}

function Get-InstalledCode {
    $txt = (Invoke-S @('dumpsys', 'package', $script:Pkg)) -join "`n"
    if ($txt -match '(?m)^\s*versionCode=(\d+)') { return $Matches[1] }
    return ''
}

function Get-InstalledMode {
    if (-not (Get-InstalledVersion)) { return 'none' }
    $homes = (Invoke-S @('cmd', 'package', 'query-activities', '--brief',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) -join "`n"
    $pat = [regex]::Escape($script:Pkg) + '/'
    if ($homes -match $pat) { return 'standard' }
    return 'guard'
}

function Get-DefaultHome {
    $txt = (Invoke-S @('cmd', 'package', 'resolve-activity', '--brief',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')) -join "`n"
    $line = ($txt -split "`n" | Where-Object { $_ -match '/' } | Select-Object -Last 1)
    if (-not $line) { return '(读不到)' }
    return $line.Trim()
}

function Get-A11yList {
    $v = (Invoke-S @('settings', 'get', 'secure', 'enabled_accessibility_services')) -join ''
    $v = $v.Trim()
    if ($v -eq 'null') { return '' }
    return $v
}

function Get-NotifList {
    $v = (Invoke-S @('settings', 'get', 'secure', 'enabled_notification_listeners')) -join ''
    $v = $v.Trim()
    if ($v -eq 'null') { return '' }
    return $v
}

function Get-A11yGlobal {
    return ((Invoke-S @('settings', 'get', 'secure', 'accessibility_enabled')) -join '').Trim()
}

function Test-InList {
    param([string]$List, [string]$Item)
    if (-not $List) { return $false }
    return (($List -split ':') -contains $Item)
}

function Get-WssGranted {
    $txt = (Invoke-S @('dumpsys', 'package', $script:Pkg)) -join "`n"
    if ($txt -match 'WRITE_SECURE_SETTINGS') { return '1' }
    return '0'
}

function Get-A11yBound {
    $txt = (Invoke-S @('dumpsys', 'accessibility')) -join "`n"
    if ($txt -match [regex]::Escape($script:A11yComp)) { return '1' }
    return '0'
}

function Get-TopActivity {
    $txt = (Invoke-S @('dumpsys', 'activity', 'activities')) -join "`n"
    if ($txt -match 'topResumedActivity=.*?\s([^\s/]+/[^\s\}]+)') { return $Matches[1] }
    return ''
}

# ---------------- 快照 ----------------

function New-Snapshot {
    if (-not (Test-Path $script:StateDir)) {
        New-Item -ItemType Directory -Path $script:StateDir -Force | Out-Null
    }
    $id = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID
    $script:Snapshot = Join-Path $script:StateDir "session-$id.env"
    $lines = @(
        "SESSION_ID=$id",
        "CREATED=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "DEVICE_MASKED=$(Mask-Id $script:Dev)",
        "MODEL=$(Get-Prop 'ro.product.model')",
        "SDK=$(Get-Prop 'ro.build.version.sdk')",
        "ABI=$(Get-DeviceAbi)"
    )
    Set-Content -Path $script:Snapshot -Value $lines -Encoding UTF8
    Ok "变更前快照：$script:Snapshot"
}

function Set-SnapshotValue {
    param([string]$Key, [string]$Value)
    if (-not $script:Snapshot) { return }
    $lines = @()
    if (Test-Path $script:Snapshot) {
        $lines = Get-Content -Path $script:Snapshot -Encoding UTF8 |
                 Where-Object { $_ -notmatch "^$([regex]::Escape($Key))=" }
    }
    $lines += "$Key=$Value"
    Set-Content -Path $script:Snapshot -Value $lines -Encoding UTF8
}

function Get-SnapshotValue {
    param([string]$Key)
    if (-not $script:Snapshot -or -not (Test-Path $script:Snapshot)) { return '' }
    $hit = Get-Content -Path $script:Snapshot -Encoding UTF8 |
           Where-Object { $_ -match "^$([regex]::Escape($Key))=" } |
           Select-Object -Last 1
    if (-not $hit) { return '' }
    return $hit.Substring($Key.Length + 1)
}

function Get-LatestSnapshot {
    if (-not (Test-Path $script:StateDir)) { return '' }
    $f = Get-ChildItem -Path $script:StateDir -Filter 'session-*.env' -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($f) { return $f.FullName }
    return ''
}

# ---------------- 选包 ----------------

function Select-Apk {
    param([string]$WantMode, [string]$Abi)
    if ($Apk) { return $Apk }
    $pattern = ''
    if ($Abi -eq 'arm64-v8a') { $pattern = 'arm64-v8a' }
    elseif ($Abi -eq 'armeabi-v7a') { $pattern = 'armeabi-v7a' }
    else { return '' }

    $all = @()
    foreach ($f in (Get-ChildItem -Path $Dist -Filter 'TVDesk-*.apk' -ErrorAction SilentlyContinue)) {
        $v = Get-ApkVersion $f.Name
        $ver = $null
        if ($v -and [version]::TryParse($v, [ref]$ver)) {
            $all += [pscustomobject]@{ File = $f; Ver = $ver }
        }
    }
    if ($all.Count -eq 0) { return '' }
    # 按版本号排，不是按文件名字典序：字典序里 "1.1.9" 会排在 "1.1.12" 后面
    $all = $all | Sort-Object Ver
    $all = $all | ForEach-Object { $_.File }

    $filtered = @()
    foreach ($f in $all) {
        $isGuard = $f.Name -match '-guard-'
        if ($WantMode -eq 'guard') { if ($isGuard) { $filtered += $f } }
        else { if (-not $isGuard) { $filtered += $f } }
    }
    $exact = $filtered | Where-Object { $_.Name -match "-$([regex]::Escape($pattern))\.apk$" } |
             Select-Object -Last 1
    if ($exact) { return $exact.FullName }
    $univ = $filtered | Where-Object { $_.Name -match '-universal\.apk$' } | Select-Object -Last 1
    if ($univ) { return $univ.FullName }
    return ''
}

function Get-ApkVersion {
    param([string]$Path)
    $name = Split-Path -Leaf $Path
    if ($name -match '^TVDesk-([0-9.]+)') { return $Matches[1] }
    return ''
}

# ---------------- Detect ----------------

function Do-Detect {
    if (-not (Pick-Device)) { return }
    $sdk = Get-Prop 'ro.build.version.sdk'
    $abi = Get-DeviceAbi
    $ver = Get-InstalledVersion
    $code = Get-InstalledCode
    $curMode = Get-InstalledMode
    $homeNow = Get-DefaultHome
    $a11y = Get-A11yList
    $notif = Get-NotifList

    Step '设备'
    Say "  型号      $(Get-Prop 'ro.product.brand') $(Get-Prop 'ro.product.model')"
    Say "  Android   $(Get-Prop 'ro.build.version.release')（API $sdk）"
    Say "  ABI       $(if ($abi) { $abi } else { '不支持的架构' })"
    Say "  设备号    $(Mask-Id $script:Dev)"

    Step '安装'
    if ($ver) {
        Say "  版本      $ver（$code）"
        Say "  模式      $curMode"
    } else {
        Say '  未安装'
    }

    Step '接管状态'
    Say "  默认桌面  $homeNow"
    if ($homeNow -eq $script:HomeAct) { Ok '默认桌面就是 TVDesk' }
    if (Test-InList $a11y $script:A11yComp) {
        Say '  无障碍    已授权'
        if ((Get-A11yBound) -eq '1') { Ok '守护服务已连接' }
        else { Warn '无障碍配置里有 TVDesk，但服务还没连上（等系统重绑，或去设置里关掉再打开）' }
    } else {
        Say '  无障碍    未授权'
    }
    if (Test-InList $notif $script:NotifComp) { Say '  通知监听  已授权' } else { Say '  通知监听  未授权' }
    if ((Get-WssGranted) -eq '1') { Say '  写系统设置 已授予' } else { Say '  写系统设置 未授予' }

    if (-not $sdk) { Fail '读不到系统版本，先确认设备真的连上了'; return }
    if ([int]$sdk -lt 21) { Fail "Android $sdk 不在支持范围（最低 5.0 / API 21）"; return }
    if (-not $abi) { Fail "这台机器的架构（$(Get-Prop 'ro.product.cpu.abilist')）没有对应安装包"; return }

    $suggest = 'standard'
    $why = '新装默认标准版：能设为默认桌面时不需要无障碍'
    if ($curMode -eq 'guard') { $suggest = 'guard'; $why = '已装的是守护版，保持同一模式覆盖更新' }
    elseif ($curMode -eq 'standard') { $suggest = 'standard'; $why = '已装的是标准版，保持同一模式覆盖更新' }

    Step '建议'
    Say "  安装模式  $suggest（$why）"
    $pick = Select-Apk $suggest $abi
    if ($pick) { Say "  安装包    $pick" } else { Say '  安装包    （dist 里没找到，用 -Apk 指定）' }
}

# ---------------- Install ----------------

function Do-Install {
    if (-not (Pick-Device)) { return }
    $sdk = Get-Prop 'ro.build.version.sdk'
    $abi = Get-DeviceAbi
    $ver = Get-InstalledVersion
    $curMode = Get-InstalledMode

    if (-not $sdk) { Fail '读不到系统版本，先确认设备连上了'; return }
    if ([int]$sdk -lt 21) { Fail "Android $sdk 不在支持范围（最低 5.0）"; return }
    if (-not $abi) { Fail "架构 $(Get-Prop 'ro.product.cpu.abilist') 没有对应包"; return }

    $want = $Mode
    if (-not $want) {
        if ($curMode -eq 'standard' -or $curMode -eq 'guard') { $want = $curMode } else { $want = 'standard' }
    }
    if ($curMode -and $curMode -ne 'none' -and $curMode -ne $want) {
        Warn "现在装的是 $curMode 版，要覆盖成 $want 版。两个包同签名，数据保留，但"
        Warn '  切换后需要重新「设为默认桌面」（系统不会自动恢复上一个桌面）。'
    }

    $target = Select-Apk $want $abi
    if (-not $target -or -not (Test-Path $target)) { Fail "没找到 $want / $abi 的安装包，用 -Apk 指定"; return }

    Step '将要执行'
    Say "  设备      $(Get-Prop 'ro.product.model')（$(Mask-Id $script:Dev)）"
    Say "  安装包    $(Split-Path -Leaf $target)"
    $modeNote = ''
    if ($curMode -and $curMode -ne 'none' -and $curMode -ne $want) { $modeNote = "（从 $curMode 切换）" }
    Say "  模式      $want$modeNote"
    Say "  当前版本  $(if ($ver) { $ver } else { '未安装' })"
    Say "  回滚      .\tvdesk-setup.ps1 restore"
    if (-not (Confirm-It '继续安装？')) { Say '已取消，什么都没改。'; return }

    New-Snapshot
    Set-SnapshotValue 'PREV_VERSION' $ver
    Set-SnapshotValue 'PREV_CODE' (Get-InstalledCode)
    Set-SnapshotValue 'PREV_MODE' $curMode
    Set-SnapshotValue 'PREV_HOME' (Get-DefaultHome)
    Set-SnapshotValue 'PREV_A11Y' (Get-A11yList)
    Set-SnapshotValue 'PREV_NOTIF' (Get-NotifList)
    Set-SnapshotValue 'PREV_A11Y_GLOBAL' (Get-A11yGlobal)
    Set-SnapshotValue 'APK' (Split-Path -Leaf $target)
    Set-SnapshotValue 'TARGET_MODE' $want

    Step '安装'
    $out = (Invoke-A @('install', '-r', $target)) -join "`n"
    $out -split "`n" | ForEach-Object { Say "  $_" }
    if ($out -notmatch 'Success') {
        if ($out -match 'NO_MATCHING_ABIS') { Fail '架构不匹配（NO_MATCHING_ABIS）：换这台机器支持的 ABI 包，别硬装' }
        elseif ($out -match 'UPDATE_INCOMPATIBLE|signatures do not match') { Fail '签名冲突：机器上是别的签名的 TVDesk。已停止，没有卸载也没有清数据' }
        elseif ($out -match 'VERSION_DOWNGRADE') { Fail '版本降级：目标包比已装版本低。要降级得先卸载（会丢数据），请单独确认' }
        elseif ($out -match 'USER_RESTRICTED') { Fail '系统限制了安装（USER_RESTRICTED）：先查是不是固件禁止安装桌面类应用，再考虑 guard 版' }
        elseif ($out -match 'VERIFICATION') { Fail '安装校验失败：先查限制原因，不要关掉系统的应用验证' }
        else { Fail '安装失败，原文见上' }
        Say ""
        Say "快照留在 $script:Snapshot（没做任何改动）。"
        return
    }

    $nver = Get-InstalledVersion
    $nmode = Get-InstalledMode
    if ($nver -ne (Get-ApkVersion $target)) {
        Warn "回读版本是 $(if ($nver) { $nver } else { '空' })，和包名上的 $(Get-ApkVersion $target) 不一致"
    } else {
        Ok "已安装 $nver（$nmode）"
    }
    Set-SnapshotValue 'RESULT_INSTALLED' $nver
    Set-SnapshotValue 'RESULT_MODE' $nmode

    Step '下一步'
    if ($nmode -eq 'standard') {
        Say '  设为默认桌面：在电视上 设置 → 应用 → 默认应用 → 主屏幕，选 TVDesk。'
        Say '  也可以直接 .\tvdesk-setup.ps1 activate -Mode standard（需要你确认）'
        Say '  设好后执行 .\tvdesk-setup.ps1 verify -TestHome 复核。'
    } else {
        Say '  守护版需要开无障碍：电视上 设置 → 辅助功能 → TVDesk 桌面守护，打开。'
        Say '  没有入口的固件可以执行 .\tvdesk-setup.ps1 activate -Mode guard（需你确认后才会写设置）。'
    }
}

# ---------------- Activate ----------------

function Do-Activate {
    if (-not (Pick-Device)) { return }
    $curMode = Get-InstalledMode
    if ($curMode -eq 'none') { Fail '还没装 TVDesk，先执行 install'; return }
    $want = $Mode
    if (-not $want) { $want = $curMode }

    if ($want -eq 'standard') {
        if ($curMode -eq 'guard') { Fail '现在装的是守护版，没有桌面声明，先装标准版'; return }
        Step '设为默认桌面'
        Say "  目标  $script:HomeAct"
        Say "  现在  $(Get-DefaultHome)"
        if (-not (Confirm-It '把 TVDesk 设为默认桌面？')) { Say '已取消。'; return }
        New-Snapshot | Out-Null
        Set-SnapshotValue 'PREV_HOME' (Get-DefaultHome)
        Invoke-S @('cmd', 'package', 'set-home-activity', $script:HomeAct) | Out-Null
        Start-Sleep -Seconds 2
        $cur = Get-DefaultHome
        if ($cur -eq $script:HomeAct) { Ok '已设为默认桌面' }
        else {
            Warn "命令执行了，但读回来还是 $cur"
            Say '  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕'
            Say '  部分固件锁定了原厂桌面，这种情况只能靠守护版接管 HOME。'
        }
        return
    }

    Step '开启无障碍守护'
    $curList = Get-A11yList
    if (Test-InList $curList $script:A11yComp) {
        Say '  已经在列表里，不重复添加'
    } else {
        $newList = if ($curList) { "$curList`:$script:A11yComp" } else { $script:A11yComp }
        Say "  将写入：$newList"
        if (-not (Confirm-It '确认开启？（只追加本应用这一项，其他服务不动）')) { Say '已取消。'; return }
        New-Snapshot | Out-Null
        Set-SnapshotValue 'PREV_A11Y' $curList
        Set-SnapshotValue 'PREV_A11Y_GLOBAL' (Get-A11yGlobal)
        Invoke-S @('settings', 'put', 'secure', 'enabled_accessibility_services', $newList) | Out-Null
        if ((Get-A11yGlobal) -ne '1') {
            Invoke-S @('settings', 'put', 'secure', 'accessibility_enabled', '1') | Out-Null
        }
        if (-not (Test-InList (Get-A11yList) $script:A11yComp)) {
            Fail '写入没生效：这台机器可能不允许 ADB 改无障碍（Android 13+ 受限设置）'
            Say '  电视上手动开：设置 → 辅助功能 → TVDesk 桌面守护'
            return
        }
        Ok '已写入无障碍列表'
    }

    # 授权写完了，还得把应用里的开关也打开：应用只认自己的开关，
    # 光有系统侧授权它也不会接管（还原时被关掉的就是这个开关）
    Invoke-S @('am', 'broadcast', '-a', 'com.tvdesk.app.action.ENABLE_GUARD', '-p', $script:Pkg) | Out-Null

    Step '等服务连接'
    for ($i = 1; $i -le 10; $i++) {
        if ((Get-A11yBound) -eq '1') { Ok "服务已连接（$i 秒）"; return }
        Start-Sleep -Seconds 1
    }
    Warn '10 秒内没看到服务连接上'
    Say '  多数情况是系统还没重绑，去电视上把这一项关掉再打开即可'
}

# ---------------- Verify ----------------

function Do-Verify {
    if (-not (Pick-Device)) { return }
    $ver = Get-InstalledVersion
    $curMode = Get-InstalledMode
    $homeNow = Get-DefaultHome
    $a11y = Get-A11yList
    $notif = Get-NotifList

    Step '安装与模式'
    if (-not $ver) { Fail '未安装' } else { Ok "已安装 $ver，模式 $curMode" }

    Step '默认桌面'
    if ($curMode -eq 'guard') { Say '  守护版不声明 HOME，这一项不适用' }
    elseif ($homeNow -eq $script:HomeAct) { Ok '默认桌面是 TVDesk' }
    else { Warn "默认桌面还是 $homeNow" }

    Step '守护授权'
    if (Test-InList $a11y $script:A11yComp) {
        Ok '无障碍配置里有 TVDesk'
        if ((Get-A11yBound) -eq '1') { Ok '服务已连接' }
        else { Warn '服务未连接：系统还没重绑。电视上把它关掉再打开，或等十几秒' }
    } else {
        if ($curMode -eq 'standard') { Say '  无障碍未授权（标准版靠默认桌面接管，可以不授权）' }
        else { Say '  无障碍未授权' }
    }
    if (Test-InList $notif $script:NotifComp) { Say '  通知监听  已授权' }
    else { Say '  通知监听  未授权（只有需要自动恢复时才要）' }

    if ($TestHome) {
        Step 'HOME 实测'
        $before = Get-TopActivity
        if ($before -like "$($script:Pkg)/*") {
            Invoke-S @('am', 'start', '-a', 'android.settings.SETTINGS') | Out-Null
            Start-Sleep -Seconds 4
            $before = Get-TopActivity
        }
        Invoke-S @('input', 'keyevent', '3') | Out-Null
        Start-Sleep -Seconds 3
        $top = Get-TopActivity
        Say "  按 HOME 前 $before"
        Say "  按 HOME 后 $top"
        if (-not $top) { Warn '读不到前台，无法判断（不少电视对 dumpsys 做了限制）' }
        elseif ($top -like "$($script:Pkg)/*") { Ok 'HOME 进了 TVDesk' }
        else { Fail "HOME 没进 TVDesk：$top" }
    }

    Step '结论'
    if ($script:FailCount -eq 0 -and $script:WarnCount -eq 0) { Ok '全部通过' }
    elseif ($script:FailCount -eq 0) { Warn "有 $script:WarnCount 项需要注意，见上" }
    else { Fail "有 $script:FailCount 项未通过，见上" }
}

# ---------------- Restore ----------------

function Do-Restore {
    if (-not (Pick-Device)) { return }
    $script:Snapshot = Get-LatestSnapshot
    $curA11y = Get-A11yList
    $curNotif = Get-NotifList

    Step '还原前状态'
    Say "  无障碍    $(if ($curA11y) { $curA11y } else { '（空）' })"
    Say "  通知监听  $(if ($curNotif) { $curNotif } else { '（空）' })"
    Say "  默认桌面  $(Get-DefaultHome)"
    if ($script:Snapshot) {
        Say "  快照      $script:Snapshot"
        Say "  装之前    默认桌面 $(Get-SnapshotValue 'PREV_HOME')"
    } else {
        Warn '没有找到快照，只能按当前值做安全移除'
    }

    if (-not (Confirm-It '开始还原？（只移除本应用那一项，其他服务保持不动）')) { Say '已取消。'; return }
    if (-not $script:Snapshot) { New-Snapshot | Out-Null }

    Step '关闭应用内自动恢复'
    # 关键一步：应用自己会自愈（通知监听重连、闹钟到点都会把授权绑回去），
    # 不先把它关掉，下面删了也会在十几秒内长回来
    if (Get-InstalledVersion) {
        Invoke-S @('am', 'broadcast', '-a', 'com.tvdesk.app.action.DISABLE_GUARD', '-p', $script:Pkg) | Out-Null
        Start-Sleep -Seconds 1
        Say '  已通知 TVDesk 关掉桌面守护（它不再自动补授权）'
    } else {
        Say '  未安装，跳过'
    }

    Step '移除无障碍里的 TVDesk'
    if (Test-InList $curA11y $script:A11yComp) {
        # 再停一次进程：保证读取和写入之间没有自愈插进来
        Invoke-S @('am', 'force-stop', $script:Pkg) | Out-Null
        Start-Sleep -Milliseconds 800
        Say '  已先停掉 TVDesk 进程'
        $rest = (($curA11y -split ':') | Where-Object { $_ -ne $script:A11yComp }) -join ':'
        # 空列表用 delete：这台 ROM 上 put 空串会直接报 Bad arguments；
        # 而 PowerShell 传空参数又会被吃掉，两头都不好使
        if ($rest) {
            Invoke-S @('settings', 'put', 'secure', 'enabled_accessibility_services', $rest) | Out-Null
        } else {
            Invoke-S @('settings', 'delete', 'secure', 'enabled_accessibility_services') | Out-Null
        }
        # 等几秒再读：系统重绑/应用自检都是异步的，写完立刻读会给出假的「成功」
        Start-Sleep -Seconds 3
        if (Test-InList (Get-A11yList) $script:A11yComp) {
            Fail '写回了但列表里还有 TVDesk（应用又绑回去了，或这台机器不让改）'
        } else {
            Ok "已移除，剩下的：$(if ($rest) { $rest } else { '（无）' })"
        }
        Set-SnapshotValue 'RESTORE_A11Y_WRITTEN' $rest
    } else {
        Say '  本来就没有'
    }

    Step '移除通知监听里的 TVDesk'
    if (Test-InList $curNotif $script:NotifComp) {
        $rest2 = (($curNotif -split ':') | Where-Object { $_ -ne $script:NotifComp }) -join ':'
        if ($rest2) {
            Invoke-S @('settings', 'put', 'secure', 'enabled_notification_listeners', $rest2) | Out-Null
        } else {
            Invoke-S @('settings', 'delete', 'secure', 'enabled_notification_listeners') | Out-Null
        }
        if (Test-InList (Get-NotifList) $script:NotifComp) {
            Fail '写回了但列表里还有它'
        } else {
            Ok "已移除，剩下的：$(if ($rest2) { $rest2 } else { '（无）' })"
        }
    } else {
        Say '  本来就没有'
    }

    Step '恢复默认桌面'
    $prevHome = Get-SnapshotValue 'PREV_HOME'
    if (-not $prevHome -or $prevHome -eq '(读不到)') {
        Say '  快照里没有记录的默认桌面，跳过'
        Say '  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕'
    } elseif ((Get-DefaultHome) -eq $prevHome) {
        Say "  已经是 $prevHome"
    } else {
        $prevPkg = ($prevHome -split '/')[0]
        $pkgs = (Invoke-S @('pm', 'list', 'packages')) -join "`n"
        if ($pkgs -notmatch "package:$([regex]::Escape($prevPkg))`$") {
            Warn "原来那个桌面 $prevPkg 已经不在机器上，不盲写失效组件"
        } else {
            Invoke-S @('cmd', 'package', 'set-home-activity', $prevHome) | Out-Null
            Start-Sleep -Seconds 2
            if ((Get-DefaultHome) -eq $prevHome) { Ok "已恢复到 $prevHome" }
            else {
                Warn "命令返回了，但读回来还是 $(Get-DefaultHome)"
                Say '  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕'
            }
        }
    }

    Step '现在的状态'
    Say '  TVDesk 的「桌面守护」已经被指令关掉，打开应用也不会自己接管 HOME。'
    Say '  想恢复接管：打开 TVDesk → 设置 → 桌面 → 桌面守护，重新打开。'

    Step '结论'
    if ($script:FailCount -eq 0) { Ok '还原完成，APK 与用户数据都没动' }
    else { Fail "有 $script:FailCount 项没还原成功，见上" }
}

# ---------------- Report ----------------

function Do-Report {
    if (-not (Pick-Device)) { return }
    $lines = @(
        'TVDesk 诊断',
        "生成时间 $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "型号 $(Get-Prop 'ro.product.brand') $(Get-Prop 'ro.product.model')",
        "固件 $(Get-Prop 'ro.build.display.id')",
        "Android $(Get-Prop 'ro.build.version.release') API $(Get-Prop 'ro.build.version.sdk')",
        "ABI $(Get-DeviceAbi) / $(Get-Prop 'ro.product.cpu.abilist')",
        "已安装 $(Get-InstalledVersion) 模式 $(Get-InstalledMode)",
        "默认桌面 $(Get-DefaultHome)",
        "无障碍全局 $(Get-A11yGlobal)",
        "无障碍列表 $(Get-A11yList)",
        "通知监听 $(Get-NotifList)",
        "服务已连接 $(Get-A11yBound)",
        "设备号 $(Mask-Id $script:Dev)"
    )
    $lines | ForEach-Object { Say $_ }
    if ($Out) {
        Set-Content -Path $Out -Value $lines -Encoding UTF8
        Ok "已写入 $Out（未上传，位置由你决定）"
    }
}

# ---------------- Connect ----------------

function Do-Connect {
    $addr = $Target
    if (-not $addr) { $addr = $Device }
    if (-not $addr) { Say '用法：.\tvdesk-setup.ps1 connect 192.168.1.20[:5555]'; return }
    if ($addr -notmatch ':') { $addr = "$addr`:5555" }
    (& $script:AdbPath connect $addr 2>&1) | ForEach-Object { Say "  $_" }
    Start-Sleep -Seconds 1
    (& $script:AdbPath devices 2>&1) | ForEach-Object { Say "  $_" }
}

# ---------------- main ----------------

if (-not (Get-Command $script:AdbPath -ErrorAction SilentlyContinue)) {
    Say "找不到 adb：$script:AdbPath。用 -Adb C:\path\to\adb.exe 指定，或装 platform-tools。"
    exit 2
}

switch ($Action) {
    'detect'   { Do-Detect }
    'install'  { Do-Install }
    'activate' { Do-Activate }
    'verify'   { Do-Verify }
    'restore'  { Do-Restore }
    'report'   { Do-Report }
    'connect'  { Do-Connect }
}

if ($script:FailCount -gt 0) { exit 1 }
exit 0

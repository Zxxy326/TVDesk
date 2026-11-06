#!/usr/bin/env bash
# TVDesk 安装与维护工具（macOS / Linux / Git Bash 共用）
#
#   ./tvdesk-setup.sh detect  [--device IP:PORT]
#   ./tvdesk-setup.sh install [--device ...] [--apk 文件] [--mode standard|guard] [--yes]
#   ./tvdesk-setup.sh activate [--device ...] [--mode standard|guard]
#   ./tvdesk-setup.sh verify  [--device ...] [--test-home]
#   ./tvdesk-setup.sh restore [--device ...] [--yes]
#   ./tvdesk-setup.sh report  [--device ...] [--out 文件]
#   ./tvdesk-setup.sh connect 192.168.1.20[:5555]      # 用户明确给出地址时才连
#
# 几条硬规则（来自《桌面守护安装与机型适配执行方案》第 6、7 节）：
#   - 只读探测（detect）不写任何东西；
#   - 不扫网段、不猜密码、不对多设备做「随便挑一台」；
#   - 每次改动前先存快照，还原时只动本工具写过的项；
#   - 任何一步失败都显式报出来，不攒到最后统一打「全部完成」。

set -u

PKG="com.tvdesk.app"
A11Y="com.tvdesk.app/com.tvdesk.desk.HomeGuardService"
NOTIF="com.tvdesk.app/com.tvdesk.desk.RevivalListener"
HOME_ACT="com.tvdesk.app/com.tvdesk.desk.DeskActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
DIST_DEFAULT="$(cd "$HERE/.." && pwd)/dist"
STATE_DIR="${TVDesk_STATE:-$HOME/.tvdesk-setup}"

ADB="${ADB:-adb}"
DEV=""
MODE=""
APK=""
ASSUME_YES=0
TEST_HOME=0
OUT_FILE=""
ACTION=""

FAILS=0
WARNS=0

# ---------- 输出 ----------

say()  { printf '%s\n' "$*"; }
ok()   { printf '[ OK ] %s\n' "$*"; }
warn() { printf '[警告] %s\n' "$*"; WARNS=$((WARNS + 1)); }
fail() { printf '[失败] %s\n' "$*"; FAILS=$((FAILS + 1)); }
step() { printf '\n== %s\n' "$*"; }

usage() {
    sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

# ---------- 参数 ----------

while [ $# -gt 0 ]; do
    case "$1" in
        detect|install|verify|restore|report|activate)
            ACTION="$1"; shift ;;
        connect)
            ACTION="connect"; shift
            # 紧跟的裸参数就是地址，允许 connect 192.168.1.20:5555
            if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then
                DEV="$1"; shift
            fi ;;
        --device) DEV="${2:-}"; shift 2 ;;
        --apk) APK="${2:-}"; shift 2 ;;
        --out) OUT_FILE="${2:-}"; shift 2 ;;
        --mode) MODE="${2:-}"; shift 2 ;;
        --adb) ADB="${2:-}"; shift 2 ;;
        --dist) DIST_DEFAULT="${2:-}"; shift 2 ;;
        --yes|-y) ASSUME_YES=1; shift ;;
        --test-home) TEST_HOME=1; shift ;;
        -h|--help) usage ;;
        *) say "看不懂的参数：$1"; usage ;;
    esac
done

[ -n "$ACTION" ] || usage

command -v "$ADB" >/dev/null 2>&1 || { say "找不到 adb：$ADB。用 --adb /path/to/adb 指定，或装 platform-tools。"; exit 2; }

# ---------- 设备 ----------

# 多设备时必须指定，不替用户挑（挑错等于装到别人的电视上）
pick_device() {
    local list count
    list="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
    count=$(printf '%s' "$list" | grep -c . || true)
    if [ -n "$DEV" ]; then
        if printf '%s\n' "$list" | grep -qx "$DEV"; then
            return 0
        fi
        say "设备 $DEV 不在已连接列表里。当前："
        printf '%s\n' "${list:-（无）}" | sed 's/^/  /'
        return 1
    fi
    if [ "$count" -eq 0 ]; then
        say "没有已连接的设备。"
        say "  USB：接好线后确认电视弹出「允许调试」并同意。"
        say "  网络：先 ./tvdesk-setup.sh connect 电视IP"
        return 1
    fi
    if [ "$count" -gt 1 ]; then
        say "同时连着多台设备，请用 --device 指定目标："
        printf '%s\n' "$list" | sed 's/^/  /'
        return 1
    fi
    DEV="$list"
}

d() { "$ADB" -s "$DEV" "$@"; }
# shell 里带空格/引号麻烦，统一走单引号字符串
sh_() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell "$@"; }

getprop_() { sh_ getprop "$1" 2>/dev/null | tr -d '\r'; }

# 设备支持的 ABI 里挑一个我们能出的包
device_abi() {
    local list
    list=$(getprop_ ro.product.cpu.abilist)
    [ -n "$list" ] || list=$(getprop_ ro.product.cpu.abi)
    case "$list" in
        *arm64-v8a*) printf 'arm64-v8a' ;;
        *armeabi-v7a*) printf 'armeabi-v7a' ;;
        *) printf '' ;;
    esac
}

installed_version() {
    sh_ dumpsys package "$PKG" 2>/dev/null | tr -d '\r' |
        sed -n 's/^ *versionName=//p' | head -1
}

installed_code() {
    sh_ dumpsys package "$PKG" 2>/dev/null | tr -d '\r' |
        sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1
}

# 当前装的是哪个模式：看它有没有声明 HOME。没有 HOME 就是 guard
installed_mode() {
    [ -z "$(installed_version)" ] && { printf 'none'; return; }
    local homes
    homes=$(sh_ cmd package query-activities --brief -a android.intent.action.MAIN \
        -c android.intent.category.HOME 2>/dev/null | tr -d '\r')
    if printf '%s' "$homes" | grep -q "$PKG/"; then
        printf 'standard'
    else
        printf 'guard'
    fi
}

default_home() {
    # 输出是「一行 priority=… 元信息 + 一行 包名/类名」，只认带斜杠那行
    local r
    r=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN \
        -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | grep '/' | tail -1)
    [ -z "$r" ] && r="(读不到)"
    printf '%s' "$r"
}

a11y_list() {
    local v
    v=$(sh_ settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    [ "$v" = "null" ] && v=""
    printf '%s' "$v"
}

notif_list() {
    local v
    v=$(sh_ settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')
    [ "$v" = "null" ] && v=""
    printf '%s' "$v"
}

a11y_global() {
    sh_ settings get secure accessibility_enabled 2>/dev/null | tr -d '\r'
}

list_has() {
    # $1 列表（冒号分隔，可能带 /ClassName 后缀），$2 组件
    local item
    local IFS=':'
    for item in $1; do
        [ "$item" = "$2" ] && return 0
    done
    return 1
}

wss_granted() {
    sh_ dumpsys package "$PKG" 2>/dev/null | tr -d '\r' |
        grep -q 'WRITE_SECURE_SETTINGS' && printf '1' || printf '0'
}

top_activity() {
    sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' |
        sed -n 's/.*topResumedActivity=.* \([^ ]*\/[^ ]*\).*/\1/p' | head -1
}

# 无障碍服务是否真的连上了（配置里有 != 服务活着）
a11y_bound() {
    sh_ dumpsys accessibility 2>/dev/null | tr -d '\r' | grep -q "$A11Y" && printf '1' || printf '0'
}

# ---------- 快照 ----------

new_session() {
    local id
    id=$(date +%Y%m%d-%H%M%S)-$$
    mkdir -p "$STATE_DIR" || return 1
    SNAPSHOT="$STATE_DIR/session-$id.env"
    {
        echo "SESSION_ID=$id"
        echo "CREATED=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "DEVICE_MASKED=$(mask "$DEV")"
        echo "MODEL=$(getprop_ ro.product.model)"
        echo "SDK=$(getprop_ ro.build.version.sdk)"
        echo "ABI=$(device_abi)"
    } > "$SNAPSHOT"
    ok "变更前快照：$SNAPSHOT"
}

snapshot_set() {
    [ -n "${SNAPSHOT:-}" ] || return 0
    # 同一个键只保留最后一次
    grep -v "^$1=" "$SNAPSHOT" > "$SNAPSHOT.tmp" 2>/dev/null || true
    mv "$SNAPSHOT.tmp" "$SNAPSHOT" 2>/dev/null || true
    printf '%s=%s\n' "$1" "$2" >> "$SNAPSHOT"
}

snapshot_get() {
    [ -n "${SNAPSHOT:-}" ] && [ -f "$SNAPSHOT" ] || return 0
    sed -n "s/^$1=//p" "$SNAPSHOT" | tail -1
}

latest_snapshot() {
    ls -1t "$STATE_DIR"/session-*.env 2>/dev/null | head -1
}

mask() {
    # 序列号/IP 只留尾四位，报告里不该出现能定位设备的完整标识
    local s="$1"
    [ ${#s} -le 4 ] && printf '%s' "$s" && return
    printf '***%s' "$(printf '%s' "$s" | tail -c 4)"
}

confirm() {
    [ "$ASSUME_YES" -eq 1 ] && return 0
    printf '%s [y/N] ' "$1"
    local ans
    read -r ans
    case "$ans" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

# ---------- Detect ----------

do_detect() {
    pick_device || exit 1
    local sdk abi ver code mode home a11y notif wss bound suggest why
    sdk=$(getprop_ ro.build.version.sdk)
    abi=$(device_abi)
    ver=$(installed_version)
    code=$(installed_code)
    mode=$(installed_mode)
    home=$(default_home)
    a11y=$(a11y_list)
    notif=$(notif_list)
    wss=$(wss_granted)

    step "设备"
    say "  型号      $(getprop_ ro.product.brand) $(getprop_ ro.product.model)"
    say "  Android   $(getprop_ ro.build.version.release)（API $sdk）"
    say "  ABI       ${abi:-不支持的架构}"
    say "  设备号    $(mask "$DEV")"

    step "安装"
    if [ -n "$ver" ]; then
        say "  版本      $ver（$code）"
        say "  模式      $mode"
    else
        say "  未安装"
    fi

    step "接管状态"
    say "  默认桌面  $home"
    if [ "$home" = "$HOME_ACT" ]; then
        ok "默认桌面就是 TVDesk"
    fi
    if list_has "$a11y" "$A11Y"; then
        say "  无障碍    已授权"
        bound=$(a11y_bound)
        if [ "$bound" = "1" ]; then
            ok "守护服务已连接"
        else
            warn "无障碍配置里有 TVDesk，但服务还没连上（等系统重绑，或去设置里关掉再打开）"
        fi
    else
        say "  无障碍    未授权"
    fi
    if list_has "$notif" "$NOTIF"; then
        say "  通知监听  已授权"
    else
        say "  通知监听  未授权"
    fi
    say "  写系统设置 $( [ "$wss" = "1" ] && echo 已授予 || echo 未授予 )"

    # 建议
    if [ -z "$sdk" ]; then
        fail "读不到系统版本，先确认设备真的连上了"
        return
    fi
    if [ "$sdk" -lt 21 ] 2>/dev/null; then
        fail "Android $sdk 不在支持范围（最低 5.0 / API 21）"
        return
    fi
    if [ -z "$abi" ]; then
        fail "这台机器的架构（$(getprop_ ro.product.cpu.abilist)）没有对应安装包"
        return
    fi
    if [ "$mode" = "guard" ]; then
        suggest="guard"
        why="已装的是守护版，保持同一模式覆盖更新"
    elif [ "$mode" = "standard" ]; then
        suggest="standard"
        why="已装的是标准版，保持同一模式覆盖更新"
    else
        suggest="standard"
        why="新装默认标准版：能设为默认桌面时不需要无障碍"
    fi
    step "建议"
    say "  安装模式  $suggest（$why）"
    say "  安装包    $(pick_apk "$suggest" "$abi" 2>/dev/null || echo '（dist 里没找到，用 --apk 指定）')"
}

# ---------- 选包 ----------

pick_apk() {
    local mode="$1" abi="$2" pattern list
    [ -n "$APK" ] && { printf '%s' "$APK"; return 0; }
    case "$abi" in
        arm64-v8a) pattern="arm64-v8a" ;;
        armeabi-v7a) pattern="armeabi-v7a" ;;
        *) return 1 ;;
    esac
    list=$(ls -1 "$DIST_DEFAULT"/TVDesk-*.apk 2>/dev/null | sort -V)
    [ -n "$list" ] || return 1
    if [ "$mode" = "guard" ]; then
        list=$(printf '%s\n' "$list" | grep -- "-guard-")
    else
        list=$(printf '%s\n' "$list" | grep -v -- "-guard-")
    fi
    # 先找精确 ABI 的，没有再退通用包
    local hit
    hit=$(printf '%s\n' "$list" | grep -- "-$pattern\.apk$" | tail -1)
    if [ -n "$hit" ]; then
        printf '%s' "$hit"
        return 0
    fi
    hit=$(printf '%s\n' "$list" | grep -- "-universal\.apk$" | tail -1)
    [ -n "$hit" ] && printf '%s' "$hit"
}

apk_version() {
    basename "$1" | sed -n 's/^TVDesk-\([0-9.]*\).*/\1/p'
}

# ---------- Install ----------

do_install() {
    pick_device || exit 1
    local sdk abi ver code mode target want abi_sel
    sdk=$(getprop_ ro.build.version.sdk)
    abi=$(device_abi)
    ver=$(installed_version)
    code=$(installed_code)
    mode=$(installed_mode)

    [ -n "$sdk" ] || { fail "读不到系统版本，先确认设备连上了"; return; }
    [ "$sdk" -ge 21 ] 2>/dev/null || { fail "Android $sdk 不在支持范围（最低 5.0）"; return; }
    [ -n "$abi" ] || { fail "架构 $(getprop_ ro.product.cpu.abilist) 没有对应包"; return; }

    want="$MODE"
    if [ -z "$want" ]; then
        case "$mode" in
            standard|guard) want="$mode" ;;
            *) want="standard" ;;
        esac
    fi
    # 换模式要覆盖安装，先讲清楚代价
    if [ -n "$mode" ] && [ "$mode" != "none" ] && [ "$mode" != "$want" ]; then
        warn "现在装的是 $mode 版，要覆盖成 $want 版。两个包同签名，数据保留，但"
        warn "  切换后需要重新「设为默认桌面」（系统不会自动恢复上一个桌面）。"
    fi

    target=$(pick_apk "$want" "$abi")
    [ -n "$target" ] && [ -f "$target" ] || { fail "没找到 $want / $abi 的安装包，用 --apk 指定"; return; }

    step "将要执行"
    say "  设备      $(getprop_ ro.product.model)（$(mask "$DEV")）"
    say "  安装包    $(basename "$target")"
    say "  模式      $want$( [ -n "$mode" ] && [ "$mode" != "none" ] && [ "$mode" != "$want" ] && echo "（从 $mode 切换）" )"
    say "  当前版本  ${ver:-未安装}"
    say "  回滚      ./tvdesk-setup.sh restore"
    confirm "继续安装？" || { say "已取消，什么都没改。"; return; }

    new_session || { fail "建不了快照目录 $STATE_DIR，先别装"; return; }
    snapshot_set PREV_VERSION "$ver"
    snapshot_set PREV_CODE "$code"
    snapshot_set PREV_MODE "$mode"
    snapshot_set PREV_HOME "$(default_home)"
    snapshot_set PREV_A11Y "$(a11y_list)"
    snapshot_set PREV_NOTIF "$(notif_list)"
    snapshot_set PREV_A11Y_GLOBAL "$(a11y_global)"
    snapshot_set APK "$(basename "$target")"
    snapshot_set TARGET_MODE "$want"

    step "安装"
    local out rc
    out=$(d install -r "$target" 2>&1)
    rc=$?
    printf '%s\n' "$out" | sed 's/^/  /'
    if [ $rc -ne 0 ] || ! printf '%s' "$out" | grep -q Success; then
        classify_install_failure "$out"
        say ""
        say "快照留在 $SNAPSHOT（没做任何改动）。"
        return
    fi

    # 回读，不信「Success」两个字
    local nver nmode
    nver=$(installed_version)
    nmode=$(installed_mode)
    if [ "$nver" != "$(apk_version "$target")" ]; then
        warn "回读版本是 ${nver:-空}，和包名上的 $(apk_version "$target") 不一致"
    else
        ok "已安装 $nver（$nmode）"
    fi
    snapshot_set RESULT_INSTALLED "$nver"
    snapshot_set RESULT_MODE "$nmode"

    step "下一步"
    if [ "$nmode" = "standard" ]; then
        say "  设为默认桌面：在电视上 设置 → 应用 → 默认应用 → 主屏幕，选 TVDesk。"
        say "  设好后执行 ./tvdesk-setup.sh verify --test-home 复核。"
    else
        say "  守护版需要开无障碍：电视上 设置 → 辅助功能 → TVDesk 桌面守护，打开。"
        say "  没有入口的固件可以执行 ./tvdesk-setup.sh activate-guard（需你确认后才会写设置）。"
    fi
}

classify_install_failure() {
    local out="$1"
    case "$out" in
        *INSTALL_FAILED_NO_MATCHING_ABIS*)
            fail "架构不匹配（NO_MATCHING_ABIS）：换这台机器支持的 ABI 包，别硬装" ;;
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*signatures\ do\ not\ match*)
            fail "签名冲突：机器上是别的签名的 TVDesk。已停止，没有卸载也没有清数据" ;;
        *INSTALL_FAILED_VERSION_DOWNGRADE*)
            fail "版本降级：目标包比已装版本低。要降级得先卸载（会丢数据），请单独确认" ;;
        *INSTALL_FAILED_USER_RESTRICTED*)
            fail "系统限制了安装（USER_RESTRICTED）：先查是不是固件禁止安装桌面类应用，再考虑 guard 版" ;;
        *INSTALL_FAILED_VERIFICATION_FAILURE*|*VERIFICATION*)
            fail "安装校验失败：先查限制原因，不要关掉系统的应用验证" ;;
        *)
            fail "安装失败，原文见上" ;;
    esac
}

# ---------- Verify ----------

do_verify() {
    pick_device || exit 1
    local ver mode home a11y notif
    ver=$(installed_version)
    mode=$(installed_mode)
    home=$(default_home)
    a11y=$(a11y_list)
    notif=$(notif_list)

    step "安装与模式"
    if [ -z "$ver" ]; then
        fail "未安装"
    else
        ok "已安装 $ver，模式 $mode"
    fi

    step "默认桌面"
    if [ "$mode" = "guard" ]; then
        say "  守护版不声明 HOME，这一项不适用"
    elif [ "$home" = "$HOME_ACT" ]; then
        ok "默认桌面是 TVDesk"
    else
        warn "默认桌面还是 $home"
    fi

    step "守护授权"
    if list_has "$a11y" "$A11Y"; then
        ok "无障碍配置里有 TVDesk"
        if [ "$(a11y_bound)" = "1" ]; then
            ok "服务已连接"
        else
            warn "服务未连接：系统还没重绑。电视上把它关掉再打开，或等十几秒"
        fi
    else
        say "  无障碍未授权$( [ "$mode" = "standard" ] && echo "（标准版靠默认桌面接管，可以不授权）" )"
    fi
    list_has "$notif" "$NOTIF" && say "  通知监听  已授权" || say "  通知监听  未授权（只有需要自动恢复时才要）"

    if [ "$TEST_HOME" -eq 1 ]; then
        step "HOME 实测"
        local before top
        before=$(top_activity)
        # 已经停在 TVDesk 上按 HOME 等于没测：先切到一个中立的系统页面
        case "$before" in
            "$PKG"/*)
                sh_ am start -a android.settings.SETTINGS >/dev/null 2>&1
                sleep 4
                before=$(top_activity)
                ;;
        esac
        sh_ input keyevent 3 >/dev/null 2>&1
        sleep 3
        top=$(top_activity)
        say "  按 HOME 前 $before"
        say "  按 HOME 后 $top"
        case "$top" in
            "$PKG"/*) ok "HOME 进了 TVDesk" ;;
            "") warn "读不到前台，无法判断（不少电视对 dumpsys 做了限制）" ;;
            *) fail "HOME 没进 TVDesk：$top" ;;
        esac
    fi

    step "结论"
    if [ "$FAILS" -eq 0 ] && [ "$WARNS" -eq 0 ]; then
        ok "全部通过"
    elif [ "$FAILS" -eq 0 ]; then
        warn "有 $WARNS 项需要注意，见上"
    else
        fail "有 $FAILS 项未通过，见上"
    fi
}

# ---------- Activate ----------

# 按需激活：标准版设默认桌面，守护版开无障碍。
# 两件事都只在用户明确选了之后才做，装完包不等于接管成功。
do_activate() {
    pick_device || exit 1
    local mode want cur
    mode=$(installed_mode)
    [ "$mode" = "none" ] && { fail "还没装 TVDesk，先执行 install"; return; }
    want="$MODE"
    [ -n "$want" ] || want="$mode"

    if [ "$want" = "standard" ]; then
        [ "$mode" = "guard" ] && { fail "现在装的是守护版，没有桌面声明，先装标准版"; return; }
        step "设为默认桌面"
        say "  目标  $HOME_ACT"
        say "  现在  $(default_home)"
        confirm "把 TVDesk 设为默认桌面？" || { say "已取消。"; return; }
        new_session >/dev/null
        snapshot_set PREV_HOME "$(default_home)"
        sh_ cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1
        sleep 2
        cur=$(default_home)
        if [ "$cur" = "$HOME_ACT" ]; then
            ok "已设为默认桌面"
        else
            warn "命令执行了，但读回来还是 $cur"
            say "  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕"
        fi
        say "  需要的话再加 --test-home 跑一次 verify 实测 HOME 键"
        return
    fi

    step "开启无障碍守护"
    local list cur_list rest
    cur_list=$(a11y_list)
    if list_has "$cur_list" "$A11Y"; then
        say "  已经在列表里，不重复添加"
    else
        say "  将写入：${cur_list:+$cur_list:}$A11Y"
        confirm "确认开启？（只追加本应用这一项，其他服务不动）" || { say "已取消。"; return; }
        new_session >/dev/null
        snapshot_set PREV_A11Y "$cur_list"
        snapshot_set PREV_A11Y_GLOBAL "$(a11y_global)"
        if [ -n "$cur_list" ]; then
            rest="$cur_list:$A11Y"
        else
            rest="$A11Y"
        fi
        sh_ settings put secure enabled_accessibility_services "'$rest'" >/dev/null 2>&1
        # 全局开关没开的话列表写了也不会生效
        if [ "$(a11y_global)" != "1" ]; then
            sh_ settings put secure accessibility_enabled 1 >/dev/null 2>&1
        fi
        if ! list_has "$(a11y_list)" "$A11Y"; then
            fail "写入没生效：这台机器可能不允许 ADB 改无障碍（Android 13+ 受限设置）"
            say "  电视上手动开：设置 → 辅助功能 → TVDesk 桌面守护"
            return
        fi
        ok "已写入无障碍列表"
    fi

    # 授权写完了，还得把应用里的开关也打开：应用只认自己的开关，
    # 光有系统侧授权它也不会接管（还原时被关掉的就是这个开关）
    sh_ am broadcast -a com.tvdesk.app.action.ENABLE_GUARD -p "$PKG" >/dev/null 2>&1

    step "等服务连接"
    local i
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if [ "$(a11y_bound)" = "1" ]; then
            ok "服务已连接（${i} 秒）"
            return
        fi
        sleep 1
    done
    warn "10 秒内没看到服务连接上"
    say "  多数情况是系统还没重绑，去电视上把这一项关掉再打开即可"
}

# ---------- Restore ----------
do_restore() {
    pick_device || exit 1
    SNAPSHOT=$(latest_snapshot)
    local cur_a11y cur_notif prev_home
    cur_a11y=$(a11y_list)
    cur_notif=$(notif_list)

    step "还原前状态"
    say "  无障碍    ${cur_a11y:-（空）}"
    say "  通知监听  ${cur_notif:-（空）}"
    say "  默认桌面  $(default_home)"
    if [ -n "$SNAPSHOT" ]; then
        say "  快照      $SNAPSHOT"
        say "  装之前    默认桌面 $(snapshot_get PREV_HOME)"
    else
        warn "没有找到快照，只能按当前值做安全移除"
    fi

    confirm "开始还原？（只移除本应用那一项，其他服务保持不动）" || { say "已取消。"; return; }
    [ -n "$SNAPSHOT" ] || new_session

    step "关闭应用内自动恢复"
    # 关键一步：应用自己会自愈（通知监听重连、闹钟到点都会把授权绑回去），
    # 不先把它关掉，下面删了也会在十几秒内长回来
    if [ -n "$(installed_version)" ]; then
        sh_ am broadcast -a com.tvdesk.app.action.DISABLE_GUARD -p "$PKG" >/dev/null 2>&1
        sleep 1
        say "  已通知 TVDesk 关掉桌面守护（它不再自动补授权）"
    else
        say "  未安装，跳过"
    fi

    step "移除无障碍里的 TVDesk"
    if list_has "$cur_a11y" "$A11Y"; then
        # 再停一次进程：保证下面这次读取和写入之间没有自愈插进来
        sh_ am force-stop "$PKG" >/dev/null 2>&1
        sleep 1
        say "  已先停掉 TVDesk 进程"
        local rest
        rest=$(printf '%s' "$cur_a11y" | tr ':' '\n' | grep -vxF "$A11Y" | paste -sd ':' -)
        # 空列表用 delete：这台 ROM 上 put 空串会直接报 Bad arguments
        if [ -n "$rest" ]; then
            sh_ settings put secure enabled_accessibility_services "'$rest'" >/dev/null 2>&1
        else
            sh_ settings delete secure enabled_accessibility_services >/dev/null 2>&1
        fi
        # 等两秒再读：系统重绑是异步的，写完立刻读会给出假的「成功」
        sleep 2
        local back
        back=$(a11y_list)
        if list_has "$back" "$A11Y"; then
            fail "写回了但列表里还有 TVDesk（权限不够？）"
        else
            ok "已移除，剩下的：${back:-（无）}"
        fi
        snapshot_set RESTORE_A11Y_WRITTEN "$rest"
    else
        say "  本来就没有"
    fi

    step "移除通知监听里的 TVDesk"
    if list_has "$cur_notif" "$NOTIF"; then
        local rest2
        rest2=$(printf '%s' "$cur_notif" | tr ':' '\n' | grep -vxF "$NOTIF" | paste -sd ':' -)
        if [ -n "$rest2" ]; then
            sh_ settings put secure enabled_notification_listeners "'$rest2'" >/dev/null 2>&1
        else
            sh_ settings delete secure enabled_notification_listeners >/dev/null 2>&1
        fi
        if list_has "$(notif_list)" "$NOTIF"; then
            fail "写回了但列表里还有它"
        else
            ok "已移除，剩下的：${rest2:-（无）}"
        fi
    else
        say "  本来就没有"
    fi

    step "恢复默认桌面"
    prev_home=$(snapshot_get PREV_HOME)
    if [ -z "$prev_home" ] || [ "$prev_home" = "(读不到)" ]; then
        say "  快照里没有记录的默认桌面，跳过"
        say "  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕"
    elif [ "$(default_home)" = "$prev_home" ]; then
        say "  已经是 $prev_home"
    elif ! sh_ pm list packages 2>/dev/null | grep -q "^package:${prev_home%%/*}$"; then
        warn "原来那个桌面 ${prev_home%%/*} 已经不在机器上，不盲写失效组件"
    else
        sh_ cmd package set-home-activity "$prev_home" >/dev/null 2>&1
        sleep 2
        if [ "$(default_home)" = "$prev_home" ]; then
            ok "已恢复到 $prev_home"
        else
            warn "命令返回了，但读回来还是 $(default_home)"
            say "  电视上手动选：设置 → 应用 → 默认应用 → 主屏幕"
        fi
    fi

    step "现在的状态"
    say "  TVDesk 的「桌面守护」已经被指令关掉，打开应用也不会自己接管 HOME。"
    say "  想恢复接管：打开 TVDesk → 设置 → 桌面 → 桌面守护，重新打开。"

    step "结论"
    [ "$FAILS" -eq 0 ] && ok "还原完成，APK 与用户数据都没动" || fail "有 $FAILS 项没还原成功，见上"
}

# ---------- Report ----------

do_report() {
    pick_device || exit 1
    local text
    text=$(
        echo "TVDesk 诊断"
        echo "生成时间 $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "型号 $(getprop_ ro.product.brand) $(getprop_ ro.product.model)"
        echo "固件 $(getprop_ ro.build.display.id)"
        echo "Android $(getprop_ ro.build.version.release) API $(getprop_ ro.build.version.sdk)"
        echo "ABI $(device_abi) / $(getprop_ ro.product.cpu.abilist)"
        echo "已安装 $(installed_version) 模式 $(installed_mode)"
        echo "默认桌面 $(default_home)"
        echo "无障碍全局 $(a11y_global)"
        echo "无障碍列表 $(a11y_list)"
        echo "通知监听 $(notif_list)"
        echo "服务已连接 $(a11y_bound)"
        echo "设备号 $(mask "$DEV")"
    )
    say "$text"
    if [ -n "$OUT_FILE" ]; then
        printf '%s\n' "$text" > "$OUT_FILE" && ok "已写入 $OUT_FILE（未上传，位置由你决定）"
    fi
}

# ---------- connect ----------

do_connect() {
    local addr="$DEV"
    [ -n "$addr" ] || { say "用法：./tvdesk-setup.sh connect 192.168.1.20[:5555]"; exit 2; }
    case "$addr" in *:*) ;; *) addr="$addr:5555" ;; esac
    "$ADB" connect "$addr" 2>&1 | sed 's/^/  /'
    sleep 1
    "$ADB" devices | sed 's/^/  /'
}

# ---------- main ----------

case "$ACTION" in
    detect) do_detect ;;
    install) do_install ;;
    verify) do_verify ;;
    restore) do_restore ;;
    activate) do_activate ;;
    report) do_report ;;
    connect) DEV="${DEV:-${1:-}}"; do_connect ;;
esac

[ "$FAILS" -eq 0 ] || exit 1
exit 0

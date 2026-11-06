#!/usr/bin/env bash
# TVDesk 构建脚本（沙箱内可直接运行，不走 gradle wrapper 下载）
#
# 用法：
#   ./build.sh                    编译 standard-java32 debug（armeabi-v7a，电视用）
#   ./build.sh java32             同上
#   ./build.sh java               双 ABI
#   ./build.sh guard java32       守护版（清单里没有 HOME）
#   ./build.sh install            编译并安装 standard-java32 到已连接的设备
#   ./build.sh release            打六个发行包（standard/guard × 三个 ABI）并收进 dist/
#
# 变体是 home × mode 两个维度：home = standard|guard，mode = java|java32|java64。
# 任务名按维度顺序拼，比如 assembleStandardJava32Release。

set -e

export JAVA_HOME="${JAVA_HOME:-D:/dev/jdk17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/dev/gradle-home}"
ADB="D:/dev/android-sdk/platform-tools/adb.exe"

GRADLE="$(find "$GRADLE_USER_HOME/wrapper/dists" -maxdepth 6 -name gradle -type f -path "*gradle-8.14.3*/bin/*" 2>/dev/null | head -1)"
if [ -z "$GRADLE" ]; then
  echo "未找到 Gradle 8.14.3，请检查 $GRADLE_USER_HOME/wrapper/dists"
  exit 1
fi

cap() { echo "$1" | sed 's/^\(.\)/\U\1/'; }

MODE="standard"
VARIANT=""
case "${1:-}" in
  install|release) VARIANT="$1" ;;
  standard|guard)  MODE="$1"; VARIANT="${2:-java32}" ;;
  *)               VARIANT="${1:-java32}" ;;
esac

case "$VARIANT" in
  install)
    "$GRADLE" :app:assembleStandardJava32Debug --console=plain
    APK="app/build/outputs/apk/standardJava32/debug/app-standard-java32-debug.apk"
    "$ADB" install -r "$APK"
    ;;
  java|java32|java64)
    TASK=":app:assemble$(cap "$MODE")$(cap "$VARIANT")Debug"
    echo "== $TASK"
    "$GRADLE" "$TASK" --console=plain
    ;;
  release)
    # 六个发行包：standard 沿用原来的文件名，guard 带模式标识，避免和已有包混淆
    VERSION="$(sed -n "s/^[[:space:]]*versionName '\\([^']*\\)'.*/\\1/p" app/build.gradle | head -1)"
    TASKS=""
    for m in standard guard; do
      for v in java java32 java64; do
        TASKS="$TASKS :app:assemble$(cap "$m")$(cap "$v")Release"
      done
    done
    "$GRADLE" $TASKS --console=plain

    mkdir -p dist
    for m in standard guard; do
      for pair in "java universal" "java32 armeabi-v7a" "java64 arm64-v8a"; do
        FLAVOR="${pair% *}"; TAG="${pair#* }"
        # 只认签名产物的确切名字：find 会捞到上一轮留下的 -unsigned，
        # 那种包装上才发现没签名
        # AGP 把两个维度的产物放在「变体名」目录下：standardJava32/、guardJava64/
        VARIANT_DIR="$m$(cap "$FLAVOR")"
        SRC="app/build/outputs/apk/$VARIANT_DIR/release/app-$m-$FLAVOR-release.apk"
        if [ ! -f "$SRC" ]; then
          echo "没找到签好名的 $m-$FLAVOR release 产物（$SRC）。"
          echo "只有 -unsigned 的话说明 local.properties 里的 tvdesk.keystore 没生效。"
          exit 1
        fi
        if [ "$m" = "guard" ]; then
          cp "$SRC" "dist/TVDesk-$VERSION-guard-$TAG.apk"
        else
          cp "$SRC" "dist/TVDesk-$VERSION-$TAG.apk"
        fi
      done
    done
    ls -lh dist/*.apk
    ;;
  *)
    echo "未知参数：$VARIANT（可选 java / java32 / java64 / install / release；"
    echo "         也可以在前面加 standard / guard，例如 ./build.sh guard java64）"
    exit 1
    ;;
esac

find app/build/outputs -name "*.apk" -exec ls -lh {} \;

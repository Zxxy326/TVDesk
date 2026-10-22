#!/usr/bin/env bash
# TVDesk 构建脚本（沙箱内可直接运行，不走 gradle wrapper 下载）
# 用法：
#   ./build.sh              编译 java32Debug（armeabi-v7a，电视用）
#   ./build.sh java32       同上
#   ./build.sh java          双 ABI
#   ./build.sh install       编译并安装到已连接的设备
#   ./build.sh release       打三个发行包（v7a / v8a / 全机型）并收进 dist/

set -e

export JAVA_HOME="${JAVA_HOME:-D:/dev/jdk17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/dev/gradle-home}"
ADB="D:/dev/android-sdk/platform-tools/adb.exe"

GRADLE="$(find "$GRADLE_USER_HOME/wrapper/dists" -maxdepth 6 -name gradle -type f -path "*gradle-8.14.3*/bin/*" 2>/dev/null | head -1)"
if [ -z "$GRADLE" ]; then
  echo "未找到 Gradle 8.14.3，请检查 $GRADLE_USER_HOME/wrapper/dists"
  exit 1
fi

VARIANT="${1:-java32}"

case "$VARIANT" in
  install)
    "$GRADLE" :app:assembleJava32Debug --console=plain
    APK="app/build/outputs/apk/java32/debug/app-java32-debug.apk"
    "$ADB" install -r "$APK"
    ;;
  java|java32|java64)
    CAP="$(echo "${VARIANT:0:1}" | tr 'a-z' 'A-Z')${VARIANT:1}"
    "$GRADLE" ":app:assemble${CAP}Debug" --console=plain
    ;;
  release)
    # 发行包：三个 ABI 各一份，签名走 local.properties 里那把钥匙（见 app/build.gradle）
    VERSION="$(sed -n "s/^[[:space:]]*versionName '\\([^']*\\)'.*/\\1/p" app/build.gradle | head -1)"
    "$GRADLE" :app:assembleJava32Release :app:assembleJava64Release :app:assembleJavaRelease --console=plain
    mkdir -p dist
    for pair in "java32 armeabi-v7a" "java64 arm64-v8a" "java universal"; do
      FLAVOR="${pair% *}"; TAG="${pair#* }"
      # 只认签名产物的确切名字：find 会捞到上一轮留下的 -unsigned，那种包装上才发现没签名
      SRC="app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk"
      if [ ! -f "$SRC" ]; then
        echo "没找到签好名的 $FLAVOR release 产物（$SRC）。只有 -unsigned 的话说明 local.properties 里的 tvdesk.keystore 没生效。"
        exit 1
      fi
      cp "$SRC" "dist/TVDesk-$VERSION-$TAG.apk"
    done
    ls -lh dist/*.apk
    ;;
  *)
    echo "未知参数：$VARIANT（可选 java / java32 / java64 / install / release）"
    exit 1
    ;;
esac

find app/build/outputs -name "*.apk" -exec ls -lh {} \;

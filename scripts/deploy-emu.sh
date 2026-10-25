#!/usr/bin/env bash
# 构建 debug APK → 装进本机模拟器 → 启动 → 截一张图。
# 不接触电视，纯本机回路。用法：scripts/deploy-emu.sh
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
ADB="$ANDROID_HOME/platform-tools/adb"

SERIAL=$("$ADB" devices | awk '/emulator-.*device/{print $1; exit}')
if [ -z "${SERIAL:-}" ]; then echo "模拟器没起，先跑 scripts/dev-emu.sh" >&2; exit 1; fi

./gradlew assembleDebug -q
APK=app/build/outputs/apk/debug/app-debug.apk
echo "[deploy-emu] 安装到 $SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop com.dpadtv.client
"$ADB" -s "$SERIAL" shell monkey -p com.dpadtv.client -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
mkdir -p docs/shots
OUT="docs/shots/emu-$(date +%H%M%S).png"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$OUT"
echo "[deploy-emu] 截图：$OUT"

#!/usr/bin/env bash
# 启动本机 Android TV 形态模拟器（1920x1080 @320dpi），并等到完全开机。
# 用法：scripts/dev-emu.sh [--no-window]
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
AVD=${AVD:-dpadtv-tv}

if "$ADB" devices | grep -q "emulator-.*device"; then
  echo "[dev-emu] 模拟器已在运行"
  "$ADB" devices | grep emulator
  exit 0
fi

WINDOW_FLAG=${1:--no-window}
echo "[dev-emu] 启动 $AVD ($WINDOW_FLAG)…"
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" "$WINDOW_FLAG" -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -memory 2048 > /tmp/emulator.log 2>&1 &

for i in $(seq 1 60); do
  sleep 5
  SERIAL=$("$ADB" devices | awk '/emulator-.*device/{print $1; exit}')
  [ -n "${SERIAL:-}" ] || continue
  BOOT=$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$BOOT" = "1" ]; then echo "[dev-emu] 就绪：$SERIAL"; exit 0; fi
done
echo "[dev-emu] 超时，检查 /tmp/emulator.log" >&2; exit 1

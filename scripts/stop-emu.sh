#!/usr/bin/env bash
set -euo pipefail
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
"$ANDROID_HOME/platform-tools/adb" emu kill 2>/dev/null || true
echo "[stop-emu] 已关闭模拟器"

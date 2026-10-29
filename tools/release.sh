#!/usr/bin/env bash
set -euo pipefail
# 只产出「可对外分发」的发行包。
#
#   ./tools/release.sh            # 构建 + 自检 + 产出 dist/LibrePro-<ver>-public.apk
#
# 设计约束（勿改回命令行开关）：
#   * 凭据是否注入由**构建类型**决定：debug=自用（注入），release=发行（恒空）
#   * assembleRelease 之后自动跑 verifyNoSecrets，一旦 APK 里有本机地址/密码就构建失败
#   * 本脚本是 dist/ 下唯一被允许写 *-public.apk 的入口

cd "$(dirname "$0")/.."
ROOT=$(pwd)

VER=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
[ -n "$VER" ] || { echo "✗ 读不到 versionName"; exit 1; }

echo "▶ 构建发行包 v${VER}（release 类型，凭据恒为空）"
./gradlew :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "✗ 没有产物：$APK"; exit 1; }

# 二次自检：即便 Gradle 任务被跳过，这里也不放过（双闸）
python3 - "$APK" <<'PY'
import sys, zipfile, re
apk = sys.argv[1]
bad = ("http://192.168.", "https://192.168.", "http://10.", "http://127.0.0.1:8899", "seal:private")
hits = []
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if not (n.endswith((".dex", ".arsc")) or n.startswith(("res/", "assets/"))):
            continue
        b = z.read(n)
        for t in bad:
            if t.encode() in b:
                hits.append(f"{t} @ {n}")
    dex = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))
if not b"seal:public" in dex:
    hits.append("缺少 seal:public 封条")
if hits:
    print("✗ 发行包自检失败：\n  " + "\n  ".join(hits))
    sys.exit(1)
print("✓ 二次自检通过：无内网地址、封条 seal:public")
PY

mkdir -p dist
OUT="dist/LibrePro-$VER-public.apk"
cp "$APK" "$OUT"
SUM=$(shasum -a 256 "$OUT" | awk '{print $1}')
printf '%s  %s\n' "$SUM" "$(basename "$OUT")" > "$OUT.sha256"

COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
cat > "dist/LibrePro-$VER-public.build-info.json" <<JSON
{
  "channel": "public",
  "app": "Libre Pro",
  "versionName": "$VER",
  "artifact": "$(basename "$OUT")",
  "bytes": $(wc -c < "$OUT" | tr -d ' '),
  "sha256": "$SUM",
  "commit": "$COMMIT",
  "dirtyFiles": $DIRTY,
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "note": "public 渠道：DEFAULT_URL / DEFAULT_PASSWORD 恒为空，构建期自检已通过"
}
JSON

echo
echo "✓ 完成：$OUT  ($(wc -c < "$OUT" | tr -d ' ') B)"
echo "  sha256: $SUM"
echo "  上传只允许这一个文件：gh release upload v$VER $OUT"

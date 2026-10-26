# 本机构建与测试回路（M0 实测通过）

> 全部在本机完成，**不接触电视**。电视只在功能验收阶段才装包。

## 已验证工具链（2026-10-25）

| 项 | 值 |
|---|---|
| JDK | Temurin **21.0.12.1**（`/usr/libexec/java_home -v 21`；AGP 9 注释写 JDK 25，21 实测可用） |
| Gradle | wrapper **9.5.0**（首次会自动下载，约 1–2 min） |
| AGP | **9.3.0**（自带 Kotlin 支持，**不要再加** `org.jetbrains.kotlin.android`，否则 apply plugin 失败） |
| compileSdk / targetSdk | 37（SDK 里目录名 `android-37.0`） |
| minSdk | 23 |
| build-tools | 37.0.0 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（≈6.0 MB） |
| 首次构建耗时 | **1m46s**（含下载，36 tasks） |

## 本地配置

`local.properties`（**已在 .gitignore**，不入库）：

```
sdk.dir=/Users/11169285/android-sdk
dpadtv.defaultUrl=http://http://<你的服务器>:8899
dpadtv.defaultPassword=<LibreTV 密码，见 Keychain: libretv-istoreos>
```

## 模拟器

- AVD：`dpadtv-tv`，镜像 `system-images;android-34;google_apis;arm64-v8a`
- 已改成电视形态：**1920×1080 @320dpi**、`hw.dPad=yes`、`hw.keyboard=yes`
- 起停：`scripts/dev-emu.sh`（默认 headless，加 `--window` 可看画面）/ `scripts/stop-emu.sh`
- 一键回路：`scripts/deploy-emu.sh` → 构建 + 安装 + 启动 + 截图到 `docs/shots/`

## M0 实测记录

| 步骤 | 结果 |
|---|---|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL in 1m46s，仅 2 条 deprecated 警告 |
| 模拟器安装 | ✅ Success（arm64，与电视 armeabi-v7a 无关，本机只验证逻辑与 UI） |
| 首次启动 | 走完「服务器地址 → 访问密码」两步，地址已由 `local.properties` 预填 |
| 连接结果 | ✅ 「已连接：http://http://<你的服务器>:8899 · 已配置 16 个片源」 |
| 首页渲染 | ✅ 「豆瓣·热门·电影·第1批，共 48 条」，**海报图正常加载**（说明 UA/Referer 在模拟器同样成立） |

截图：`docs/shots/emu-01-launch.png` … `emu-05-home.png`

## 注意事项

- 模拟器是 **arm64**，电视是 **armeabi-v7a**：本机验证「逻辑 / UI / 网络契约」，最终仍需在电视上装一次做真机确认。
- 模拟器访问 `http://<你的服务器>:8899` 走 NAT 直连局域网，实测通。
- Gradle 守护进程关掉（`--no-daemon`）更省内存；连续开发可去掉。
- 签名：debug 用系统默认；release 需自备 keystore（`apksigner`），keystore 放 `~/.hermes/keys/` 不入库。

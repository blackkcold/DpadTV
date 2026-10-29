# 安全与脱敏设计

这份文档说明「凭据只留在本机」是怎么被**结构性保证**的，而不是靠人记得加参数。

## 结论

| 场景 | 凭据是否进包 | 谁来保证 |
|---|---|---|
| `./gradlew assembleDebug`（默认构建） | ✅ 进（自用包） | 从 `local.properties` 注入 |
| `./gradlew assembleRelease` | ❌ **恒不进** | `release` 构建类型里 `DEFAULT_URL` / `DEFAULT_PASSWORD` 写死为空串，代码中不存在读取 `local.properties` 的分支 |
| `./tools/release.sh` | ❌ 恒不进 | 上一条 + 二次独立扫描 |
| 有人在 CI 里构建 | ❌ 恒不进 | 仓库里根本没有 `local.properties` / `signing.properties` |

## 三层闸门

1. **类型隔离（结构性）**
   `app/build.gradle.kts` 里凭据字段只在 `buildTypes.debug` 中赋值；`buildTypes.release` 赋空串。
   想给 release 注入凭据只能显式 `-Pdpadtv.forcePrivate=true`，且会打印警告。

2. **构建期自检（Gradle）**
   `verifyNoSecrets` 任务在 `assembleRelease` 之后自动执行：解压 APK，扫描 `classes*.dex` /
   `resources.arsc` / `res/` / `assets/`，比对 `local.properties` 里的**真实值**，
   并检查内网 URL 字面量与渠道封条 `seal:public`。命中即 `GradleException`，产物不可用。
   已实测：加 `-Pdpadtv.forcePrivate=true` 会被拒绝构建。

3. **发版脚本自检（独立实现）**
   `tools/release.sh` 用另一段 Python 再扫一遍（不依赖 Gradle 任务），只有通过才写入
   `dist/`，并同时产出 `*.sha256` 与 `build-info.json`（渠道 / commit / dirty 数 / 构建时间）。

CI 在 tag 推送时复跑同样的检查（`.github/workflows/verify-release.yml`），供第三方核对。

## 凭据存放位置

| 内容 | 位置 | 权限 | 是否入库 |
|---|---|---|---|
| 服务器地址、访问密码 | `local.properties` | 本机默认 | ❌（`.gitignore`） |
| 发行签名口令 | `signing.properties` | 600 | ❌（`.gitignore`） |
| 发行 keystore | `~/.hermes/keys/librepro-release.jks` | 600 | ❌（仓库外） |
| 口令副本 | `~/.hermes/keys/librepro-release.pass` | 600 | ❌（仓库外） |

> 建议把 keystore 与口令也存进 1Password（`API Credential` 类型，字段：`keystore` / `storePassword` /
> `keyAlias` / `keyPassword`）。当前 1Password Service Account 对目标 Vault 只有读权限，
> 因此写入需要人工在桌面端完成。

## 发行包不做什么

- 不含任何默认服务器地址或密码（首次启动停在「服务器设置」）
- 不含 `debuggable=true`（可用 `adb run-as` 读私有数据的口子已被关掉）
- 不用 debug keystore 签名（发行签名是独立的 `CN=Libre Pro` 证书，SHA-256 指纹见 Release 说明）

## 已知边界

- **git 历史**里存在早期提交的内网地址（当时未脱敏）。按决定**不重写历史**，只保证新提交与产物干净。
- 自用包（`debug`）确实把地址与密码作为字符串常量放在 DEX 里。它的风险完全等价于
  “把 `local.properties` 发给别人”，所以**自用包绝不能上传或分享**；设置页页脚会显示「自用构建」。
- 私有包与发行包签名不同，互相覆盖安装需要先卸载。

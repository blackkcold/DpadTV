# UI 主题系统（已落地）

> 目标：把只有一套深色的 `Theme.kt` 升级成可在**深色 / 浅色 / 按时间**之间切换的主题系统，浅色符合 WCAG AA，切换不重启、不闪屏、不漏弹窗。
>
> 状态：**已实现并装机**（`dist/DpadTV-Pro-1.2.0-20261025b-theme-debug.apk`）。基线 `feature/collections@bda7c63`。

---

## 1. 为什么没有「跟随系统」

实测这台小米电视（`192.168.0.21:5555`，Android 14）：

```
$ adb shell cmd uimode night          →  Night mode: no
$ adb shell dumpsys uimode            →  mNightModeLocked=true
```

系统**恒为浅色且用户改不了**。所以：

- 「跟随系统」在这台机器上等于「永远浅色」，不可预期也没法验证 —— 直接删掉，只留三种模式。
- 系统夜间模式是锁的，`UiModeManager.setNightMode()` 会失败，**时间模式必须 app 自己算**。
- 默认值写死 **深色**：选「跟随系统」会让用户一更新就发现界面变白。

---

## 2. 令牌三分法（整个系统的骨架）

| 域 | 是什么 | 跟随主题 | 成员 |
|---|---|---|---|
| `surfaces` | 页面、玻璃、描边、文字、强调 | 是 | `bg` `bgDeep` `glass1` `glass2` `hairline` `ink` `muted` `accent` `onAccent` `danger` `posterBg` |
| `onImage` | 永远压在内容图/视频上 | **否** | `ON_IMAGE_INK` `ON_IMAGE_MUTED` `PLAYER_BAR` `PLAYER_TRACK` `PLAYER_PROGRESS` `SCRIM_SOLID` `ringHalo` |
| `status` | 语义状态色 | 是（两套明度） | `ST_WATCHING` `ST_WATCHED` `ST_WISH` `ST_DROPPED` |

`onImage` 这层是浅色主题能成立的关键：hero 幕布、压在海报上的字、播放器控制条、焦点环 halo、二维码，**它们的对比度基准是内容图，不是页面底**，跟着主题变就会塌。

---

## 3. 两套调色板（对比度全部实测）

| 令牌 | 深色 | 浅色 | 说明 |
|---|---|---|---|
| `bg` | `#07090C` | `#F2F3F5` | 浅色不用纯白：纯白 + 海报 = 眩光 |
| `bgDeep` | `#030406` | `#E8E9EB` | hero 幕布底 |
| `glass1` | 白 6% | 黑 4% | 深色「提亮」/ 浅色「压暗」是同一件事的两面 |
| `glass2` | 白 10% | 黑 8% | 只到二档，第三层必糊 |
| `hairline` | 白 12% | 黑 12% | 1dp |
| `ink` | `#F5F7FA` (18.57) | `#0B0D10` (17.52) | |
| `muted` | 62% 白 (7.33) | **66% 黑** (6.12) | 浅色 62% 时在 glass2 上只有 4.48，差 0.02 不达 AA |
| `accent` | `#A7C5FF` (11.46) | `#2C5FC4` (5.34) | 浅色候选 `#3A6FE0` 只有 4.18，已否决 |
| `onAccent` | `#0A1220` | `#FFFFFF` | |
| `danger` | `#FF8A80` (8.73) | `#B3261E` (5.89) | |
| 状态·在看 | `#34D399` (10.37) | `#0F7B4F` (4.77) | 浅底上 `#34D399` 只有 1.73，必须压深 |
| 状态·看过 | `#CBD5E1` | `#4B5563` (6.81) | |
| 状态·想看 | `#FBBF24` | `#8A5800` (5.44) | |
| 状态·弃剧 | `#F87171` | `#B3261E` (5.89) | |

跑 `python3 tools/contrast.py` 可复现上表；脚本从 `Theme.kt` 抠值，任何一次改色都会立刻被判。

### 3.1 焦点环必须是双环

环画在**内容图**上，所以单色环在极端海报上必失明：

| 场景 | 单环 | 双环（accent + halo） |
|---|---|---|
| `#A7C5FF` 压亮海报 `#C8C8C8` | **1.04:1（隐形）** | halo 黑 60% → 5.74:1 |
| 旧 `#38BDF8` 压亮海报 | 1.28:1 | — |

结构（从外到内）：`accent 2dp → halo 2dp → 内容`。halo 取对向色（深色主题黑 60%，浅色主题白 92%），实测中灰海报上也有 3.2 / 3.6:1。40% 黑在中灰上只有 2.64，已加到 60%。

---

## 4. 代码结构

| 文件 | 职责 |
|---|---|
| `Theme.kt` | `Palette` 数据类 + `DARK`/`LIGHT` + `bind(isLight)`；**旧 API 名全部保留成 getter**，109 处引用零改动；工厂 `fill/rounded/glass/scrimTop/scrimBottom/edgeFade/focusRing` |
| `ThemeResolver.kt` | 纯函数：`isLight(mode, hour, window)`、`isLightHour`、`currentHour`、`millisUntilNextFlip`。不碰 Context，JVM 单测直接跑 |
| `Prefs.kt` | `themeMode`（`dark`/`light`/`time`，默认 dark）、`themeLightFrom`（7）、`themeLightTo`（19）、`isLightNow()` |
| `MainActivity.kt` | `syncTheme()`、`themeTick`+`scheduleNextThemeFlip()`、时间/时区广播、`rerenderForTheme()`、`dlg()`、`themeGroup()` |
| `PlayerActivity / BrowseScreen / LibraryScreen / ShelvesScreen / PosterActions` | 快照字段改 getter、`ring()` 走双环、状态色走令牌、弹窗走 `dlg()` |
| `res/values/themes.xml` | `AppTheme` + `AppDialogDark` + `AppDialogLight`（light 的 parent 必须是 `Theme.Material.Light.Dialog.Alert`） |
| `res/drawable/dialog_bg*.xml` | 深色 `#202224` / 浅色 `#FCFCFD`，各 14dp 圆角 + hairline |
| `tools/contrast.py` | 对比度回归，CI 友好（失败退出码 1） |

### 4.1 为什么保留旧 API 名

109 处 `Theme.INK` / `Theme.ACCENT` 不动，只把 `val` 改成 `get()`。`SURFACE`/`PANEL` 直接映射到 `glass1`/`glass2`（语义本来就等价：「实色面板」→「一层玻璃」）。这样 P0 只改一个文件就能全站换皮。

### 4.2 18 处「颜色快照」

`private val ACCENT = Theme.ACCENT` 这种写法会在类构造时把颜色定死，换肤对已建界面无效。全部改成 `private val ACCENT get() = Theme.ACCENT`：`MainActivity` 6 个、`ShelvesScreen`/`BrowseScreen`/`LibraryScreen` 各 4 个。

---

## 5. 切换机制

| 触发 | 位置 | 行为 |
|---|---|---|
| 用户在设置页选模式 | 「外观」分组 → `setThemeMode()` | 写 Prefs → `bind` → **就地重画设置页**，焦点回到「外观」 |
| 时间到点 | `Handler.postDelayed(millisUntilNextFlip + 1s)` | 见下表的延后策略 |
| 系统时间/时区变更 | 动态 `BroadcastReceiver`（onStart 注册 / onStop 注销） | 重排下一次翻转 + 立即纠正 |
| 回前台 | `onResume()` | 补一次 `syncTheme()`（跨天、后台放几天都能纠正） |

### 5.1 三个「不换肤」的时刻

| 时刻 | 处置 | 理由 |
|---|---|---|
| 播放器在前台 | `themePending`，回 `onResume` 再应用 | 看片看到一半整屏变白，是这个功能唯一的事故形态 |
| 有 `AlertDialog` 打开 | 延后，等下一次 tick | 弹窗是独立 window，主题变了它不会跟着变 |
| 正在输入（`input`/`manualadd`） | 延后 | 重画会清掉输入 |

### 5.2 为什么不需要通用路由

换肤入口只在设置页，所以「重画」目标几乎恒为 `showSetup()`。只有时间到点且此刻在别的页时才需要重放：`rerenderForTheme()` 用缓存入参（`lastDetailItem`/`lastDetailData`/`lastShelf`/`lastResults`）重放 detail / results / browse，其余屏直接调各自的 `show*()`。

### 5.3 时间模式

- 默认浅色 07:00–19:00，其余深色；`start == end` 视为非法 → 恒深色。
- 跨午夜窗口（如 20–06）由 `isLightHour` 的 `||` 分支支持。
- 排程用 `Calendar` 而不是 `java.time`：minSdk 23 且没开 core library desugaring，`java.time` 在 API 26 以下会崩。
- 不做「跟随日出日落」：要定位权限，电视上没有合理授权入口。

---

## 6. 系统资源层

- `windowBackground` 统一 `#07090C`：浅色模式下最多闪一帧深色（电视启动本来就先黑一下），比白闪柔和。彻底解决要自定义 Splash，不值得。
- 对话框**不用** `-night` 限定符，而是按解析结果显式选样式：

```kotlin
private fun dlg(): AlertDialog.Builder =
    AlertDialog.Builder(this, if (Theme.isLight) R.style.AppDialogLight else R.style.AppDialogDark)
```

共 **27 处**调用点：`MainActivity` 18、`PlayerActivity` 3、`BrowseScreen` 2、**`PosterActions` 1（第一次验收就是它漏了，浅色下弹出深色长按菜单）**、`PlayerActivity`/`BrowseScreen` 各含定义。

> 教训：统计弹窗调用点不能只 grep 你记得的几个文件。`PosterActions` 那个文件里只有一行 `AlertDialog.Builder(host)`，藏在 61 行的小文件里，靠肉眼过一遍很容易漏。

---

## 7. 逐屏落点

| 屏 | 关键点 |
|---|---|
| 首页 `ShelvesScreen` | 页面底 `Theme.BG`；海报占位 `POSTER_BG`（浅色下是浅灰）；骨架屏同色；状态角标走 `ST_*`；焦点双环 |
| 设置 | 顶部「外观」组：三个等宽按钮 + 两块**真调色板**预览（生效中的描强调色边）+ 「按时间」时多一行时段（5 个预设窗口，不让遥控器点 24 小时） |
| 详情 | 主按钮 = accent 底 + `onAccent` 字，两种主题都是全屏唯一高对比块 |
| 播放器 | 控制条**不跟随主题**：恒用 `PLAYER_BAR` 深色玻璃压视频。浅色下白玻璃在亮场景里会彻底消失。只有设置弹窗跟随 |
| 二维码 | 永远深码浅底（`0xFF04121B` / 白），反色二维码在中低端手机扫码器上会识别失败 |

---

## 8. 验证结果

| 项 | 结果 |
|---|---|
| `./gradlew testDebugUnitTest` | **28 例全绿**（新增 `ThemeResolverTest` 11 例：三模式、07:00/19:00 边界、跨午夜、非法窗口、06:59→≤60s、07:01→≈12h、时区、脏值回落） |
| `python3 tools/contrast.py` | 两套调色板共 30 项全过（主文字 ≥7、正文 ≥4.5、焦点环相邻对 ≥3、halo 最坏海报 ≥2.5） |
| 模拟器（1920×1080 @320dpi） | 深色首页 → 设置页点「浅色」**就地变 #F2F3F5 不重启** → 浅色首页/详情/长按菜单 → 切「按时间」在 20:58 立刻翻深色并长出时段行 |
| 电视实机 | **仅安装，未做实机测试**（按用户要求）。旧 pro 已卸载、配置已回填：`shared_prefs/dpadtv_native.xml` 5124 B 恢复成功，`server_url` / `password` / 片源清单不需要重走向导 |

截图存 `docs/shots/theme/`：`01-home-dark` `02-home-light` `03-settings-light` `04-dialog-light` `05-settings-time` `06-detail-light`。

---

## 9. 已知边界

1. **首帧**：`windowBackground` 由系统选，app 内模式它不知道 → 显式浅色 + 深色窗口底会闪一帧深色。接受。
2. **系统键盘**：输入法是系统的，跟随系统主题（这台电视恒浅），深色主题下调出浅色键盘会突兀。无解，除非自带输入法。
3. **亮色下的层级手段变了**：深色靠「提亮」（玻璃），浅色靠「压暗 + 投影」。`Palette.cardShadow` 已留字段（深色 0 / 浅色 6dp），目前只用在焦点卡片，其余屏没有逐卡投影（电视 SoC 上逐卡 elevation 不划算）。
4. **Aerial 的其余部分没动**：三级字号、hero 幕布、edgeFade 只加了工厂，逐屏改造仍按 `AERIAL-PLAN.md` 的 P1/P2 推进。

---

## 10. 怎么扩展

新增一个令牌：

1. 加进 `Palette` 的构造函数（编译期强制两套调色板都填）。
2. 在 `Theme` 里加 getter（沿用旧名就不改调用方）。
3. 若是半透明色，`tools/contrast.py` 会自动按「叠到 bg 上」算；若是压在图上的，加进 `onImage` 域并写死值。

新增一个模式（例如「跟随日出」）：只在 `ThemeMode` 加枚举 + `ThemeResolver.isLight` 加分支 + `scheduleNextThemeFlip` 支持；UI 的按钮组是数据驱动的，加一项即可。

---

## 11. Aerial 逐屏改造（本轮已落地）

| 屏 | 做了什么 | 关键常量 |
|---|---|---|
| 首页 | 顶部 38%→**40% 屏高 hero**：当前焦点内容的封面当幕布，220ms 交叉淡入换图；标题 40sp 压暗幕，元信息 13sp；导航 ghost 叠在图上；行标题降为 13sp 加字距、计数右对齐；行左右 80dp 渐变只在真的还能滚的那一侧出现 | `Theme.HERO` `HERO_FEATHER` `Theme.edgeFade` |
| 详情 | 封面放大成**整屏 backdrop**（深色 55% 当幕布，浅色 16% 当水印），片名 40sp，正文压在幕布上 | `detailRoot()` |
| 设置 | 每个分组套一层玻璃容器（白/黑 6% + hairline），组内按钮透明，聚焦只亮描边 | `Theme.glass` |
| 播放器 | 控制条改用 `PLAYER_BAR` 深色玻璃、进度 `PLAYER_PROGRESS`；正文全部走 `ON_IMAGE_*`，不再跟主题 | onImage 域 |
| 全局 | 缩放统一 1.05、动效 160ms；字号收敛到 40 / 20 / 15 / 13（+10 角标） | `Theme.ANIM` `FOCUS_SCALE` |

### 11.1 改造里踩到的三个真问题

1. **浅色主题的白字必须有底**：hero 最初只把图渐变到底色，结果 40sp 白字压在浅色页上等于没有字。改成「暗幕跟着文字块走」：文字块自己带 `scrimBottom(0xE6000000)`，最底部再叠 56dp 的页面色渐变换掉接缝。
2. **暗幕不能整块盖**：先做成 240dp 全宽暗幕，205dp 的 hero 直接被压成一块灰带。正确做法是让幕布只覆盖文字所在的那一段。
3. **详情页 backdrop 在浅色下必须退成水印**：0.55 透明度会让深色正文压在深色封面上。按主题分档（深 0.55 / 浅 0.16）后，浅色下文字对比由页面底负责，图只是氛围。

### 11.2 仍未做（已评估，收益/风险不划算）

- 直播频道列表的行高 64 + 左侧 3dp 强调竖条：频道行是 `ListView` + selector，改行高会影响 500+ 行的一次性渲染，留给下一轮。
- 选集格子去实色块改纯 hairline：现在 2 个格子的场景看不出差别，等选集多的剧再改。
- 逐卡投影（浅色）：`Palette.cardShadow` 字段已留，电视 SoC 上逐卡 elevation 不划算，只在焦点卡用。

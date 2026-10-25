# 缺陷审计报告 · DpadTV Pro `20261025d`

> 只读审计，未改任何代码。电视已装 `1.2.0 / 20261025d`（22:14:31），未启动、未做实机测试。

- **范围**：`MainActivity.kt`(3431) / `ShelvesScreen.kt`(596) / `BrowseScreen.kt`(461) / `PlayerActivity.kt`(793) / `CollectionRepo.kt`(282) / `LibraryScreen.kt`(206) / `LocalIndex.kt`(145) + `Library` / `Prefs` / `Merger` / `Theme` / `res/drawable`
- **方法**：静态阅读 + 实测（索引数据用 python 复算、对比度按 WCAG 公式现算）+ 3 个独立审计通道交叉比对，关键条目回到源码逐行复核
- **结论**：**4 × P0 / 17 × P1 / 9 × P2**。没有必崩点（全仓 `!!` 0 处、协程与 Handler 都在 `onDestroy` 收尾），问题集中在「本地索引的语义没对齐」「焦点可达性」「返回栈」。

## P0（会毁掉主功能或数据）
### P0-1 内置索引的 year 字段 100% 为空，年份维度整体失效

- 位置：`tools/build_index.py:126-127 · LocalIndex.kt:138`
- 证据：
```kotlin
# 只认标题里的括号年份
m = re.search(r"[(（](\d{4})[)）]", title)   # j/new_search_subjects 的 title 不带年份
# 实测：1452 条里 year 非空 0 条，标题含 2025 的 0 条
```
- 影响：年份 chip 永远 0 命中；movie_2025 / tv_* 等 4 个货架每次启动都回落联网（2.5s 节流起，还可能吃 60 分钟风控）——「本地优先 0 请求」的前提被打掉一半。
- 修法：用已抓到的 2025/2024/2023 标签反推年份（实测可覆盖 300/1452 = 20.7%），剩余需抓详情或豆列；在覆盖到 100% 之前，年份 chip 旁标注「联网查」。
### P0-2 浅色主题下所有列表/网格的焦点是常量白环，实测 1.11:1 —— 等于没有焦点

- 位置：`MainActivity.kt:708-718（listSelector）`
- 证据：
```kotlin
val ring = InsetDrawable(GradientDrawable().apply {
    setColor(Color.TRANSPARENT); setStroke(dp(5), BRIGHT)   // BRIGHT = Theme.ON_IMAGE_INK = #FFFFFF
}, dp(3))
```
- 影响：选集网格、继续观看、搜索结果这三处子项本身不可聚焦，靠 GridView 持焦 + 这个 selector 显示焦点；浅色底 #F2F3F5 上 1.11:1，遥控器看不出选到第几集。
- 修法：selector 换成 Theme.focusRing 的双环（accent 2dp + halo 2dp），并在 newList/newGrid 建时按当前主题取色。
### P0-3 详情页提示「长按片名可加入片单」，但片名永远拿不到焦点

- 位置：`MainActivity.kt:2673（提示）· 2698（attach）· titleText :578`
- 证据：
```kotlin
text = "长按片名可加入片单"
val titleView = titleText(detail.info.title…, Theme.HERO)
PosterActions.attach(this, titleView, libItem, library)   // titleText 未设 isFocusable
```
- 影响：电视上长按只发给持焦控件；片名不可聚焦 → 手势 100% 无效，用户照着屏幕提示按完全没反应。
- 修法：给 titleView 补 isFocusable=true / isFocusableInTouchMode=false + 焦点样式；或改成显式「＋ 加入片单」按钮；或删掉这行提示。
### P0-4 只要开始播放就无条件把条目写成「在看」，覆盖用户手动标的「看过/弃剧」

- 位置：`MainActivity.kt:2845-2849（playFromList）`
- 证据：
```kotlin
runCatching {
    library.put(MovieItem(title = title, cover = cover), LibState.WATCHING)
    library.event(title, 1)
}
```
- 影响：片单状态机被自动写入污染：用户标了「看过」，回看一次就变回「在看」；直播频道、历史单集也会被塞进片单（标题含「· 第 N/M 集」时主键还会分叉）。
- 修法：仅当 stateOf(title) 为空时才写 WATCHING；已有显式状态只记 event 不动 state；play() 给直播/历史单独传一个「不写片单」的开关。

## P1（明显影响使用，按主题分组）

| # | 问题 | 位置 | 为什么 | 怎么修 |
|---|---|---|---|---|
| 1 | 联网兜底后永远只有第一页 | `CollectionRepo.kt:104-106` | `if (local.size >= 6 || (q.start > 0 && local.isEmpty() && index.available))` → 联网给了 20 条后，loadMore 的 start=20 本地必空、直接返回空并 `done=true`。 | 本地空且已判定要联网时，照旧打网络 start=q.start；本地只有「整页填满」才算命中。 |
| 2 | 切换筛选无请求令牌 | `BrowseScreen.kt:330 / 303 / 362` | 三处都是 `scope.launch { … items.clear(); addAll(page) }`，先发的慢响应回来会覆盖后发的结果并改写 status。 | 加自增 reqId 或持有 Job cancel 前一个，回来先比 token；launch 外层 try/finally 复位 loading 与进度条。 |
| 3 | 「按 OK 重试」不可聚焦 | `BrowseScreen.kt:88 / 346` | status 是 TextView，无 isFocusable；`status.setOnClickListener { reload() }` 在电视上永远按不到。 | 失败态换成可聚焦按钮（或给 status 设 focusable + 焦点环）。 |
| 4 | 筛选器焦点归还靠 tag，"全部" 三行重名 → 焦点跳回年份行 | `BrowseScreen.kt:257 / 231` | `tag = v`，`findViewWithTag<View>(v)?.requestFocus()`；年份/地区/类型都有「全部」。 | tag 带维度前缀 `"$name:$v"`，或记录（行,列）索引后按索引 requestFocus。 |
| 5 | loadRef 仍是「清空 + 加载中」白屏，且文案还在说侧车 | `BrowseScreen.kt:297-315` | `items.clear(); adapter.notifyDataSetChanged(); status.text = "加载中"`；OF astate 文案「缓存还没准备好，等侧车预热」——sidecar 已删。 | 与 reload() 统一：不清空、走 3dp 进度条、失败可重试；把侧车文案换成索引口径。 |
| 6 | ttlMs 形参没接线，所有「重试/强刷」都是假的 | `CollectionRepo.kt:67 / 102 / 142` | `fetch(ref, ttlMs)`、`fetchQuery(q, ttlMs)` 都收了参数，但 `cached(key, block)` 没有 ttlMs，恒用 7 天 TTL。 | ttlMs 透传到 cached/readCache；ttlMs==0 跳过读缓存，成功后覆盖。 |
| 7 | 「快进/快退步长」设置不生效 | `PlayerActivity.kt:742-749` | 播放器硬编码 `seekBy(if (event.repeatCount > 0) 15_000L else 10_000L)`；Prefs.seekStepSeconds 只在设置页被读写。 | seekBy(prefs.seekStepSeconds * 1000L)，长按 ×1.5。 |
| 8 | 本地索引把四种 ref 抹平成同一批，首页电影行 = 评分榜 | `CollectionRepo.kt:70-71（fetch 本地分支）` | 本地命中不看 ref.kind/sort：HOT / TOP250 / 9分+ / 新片都变成「电影按评分前 60」，去重后整行归最高权重榜，dominant 角标一抑制就一个角标都不剩。 | 本地按 ref.kind/sort 分派（HOT 用扰动、LATEST 用年份、DOULIST 走白名单）；补位循环同样校验 40% 配额。 |
| 9 | CollectionRepo 每进一屏新建一次 | `MainActivity.kt:3187 / 3215` | `CollectionRepo(this)` 在 ShelvesScreen 与 openShelf 各建一个 → 45KB gzip 索引重复解析、Mutex 节流与 60 分钟 blockedUntil 只活在一个实例里，返回再进立刻又能打一轮豆瓣。 | 提到 Application 级单例（含 LocalIndex）。 |
| 10 | 返回栈丢来源：从筛选页/片单进详情，返回一律回首页 | `MainActivity.kt:481-491 / 2668` | `"browse", "library" -> showShelves()`；`showRecommend()` 首行又 `if (prefs.shelvesEnabled()){ showShelves(); return }`。 | detail 记住 origin（results/browse/library/history）并回原样重建，筛选与滚动位置一起恢复。 |
| 11 | 「查看更多」带入的 initial 与页面 base 不是同一个查询 | `BrowseScreen.kt:147-153 / MainActivity.kt:3218` | 4 个榜合并（含 7.x 分）塞进 base=电影+9分+ 的页面，页头却写 9 分+；续页从 start=18 接，9 分+ 真正前 18 条永不出现。 | initial 的 Query 与 defaultQuery 不一致就置空走 reload；或记录 Query 后按 (query, offset) 对齐。 |
| 12 | 海报标题 maxLines=1 却没有 ellipsize，长片名被硬切 | `ShelvesScreen.kt:509 / BrowseScreen.kt:423 / LibraryScreen.kt:169` | 146dp / 13sp 只放得下 9~10 个汉字，标题里还拼了「★评分」，日韩片名几乎必被裁且没有省略号。 | 三处统一 `ellipsize = TextUtils.TruncateAt.END`，评分拆成独立一行。 |
| 13 | 详情页头部无高度上限，会把选集网格挤没 | `MainActivity.kt:2691 / 2719 / 2822` | 40sp 片名无 maxLines、简介 5 行，而只有 epGrid 吃 weight=1（col 不滚动）→ 540dp 屏上选集区可能只剩 <40dp。 | 片名 maxLines=2 + ellipsize，简介 3 行；或头部进 ScrollView 并给 epGrid 设 minHeight。 |
| 14 | hero 的 40sp 白标题压在自己渐变的透明段上（亮图 1.28:1） | `ShelvesScreen.kt:293 / 286` | 幕布 `scrimBottom(0xE6000000)` 顶部全透明、顶部那层只盖 150dp，216dp hero 的标题正好落在透明区。 | 文字块用自定义渐变（顶部就 0x66000000 起），或把顶部幕布抬到 hero 一半以上。 |
| 15 | 「继续观看 / 直播」进播放器：无上下集、无自动连播，错误文案却让用户「按返回键换源」 | `MainActivity.kt:3034 / 3138 · PlayerActivity.kt:377` | `play(h.playUrl, h.title)` = 单 URL 列表；错误面板 `"…按返回键换源"`，但这条路径没有 alternatives，BACK 只是 finish。 | 历史存整季 URL 列表；错误面板直接给「换源」按钮，没有备选就改成「返回后重新搜索」。 |
| 16 | 片单 source 被 CONFLICT_REPLACE 覆盖成空，推荐画像被抹掉 | `Library.kt:93-104 · LibraryScreen.kt:188` | `put("source", item.from)` + REPLACE；片单页 `e.toItem()` 未带 from → from 恒为 ""，改一次状态就把来源清空。 | put 时 item.from 为空则沿用库里旧值；或把 from 补进 LibEntry.toItem()。 |
| 17 | GridView 子项可聚焦，与 newGrid() 的「让列表自己持焦」约定相反 | `BrowseScreen.kt:416 · LibraryScreen.kt:162` | 格子里 `isFocusable = true`，AbsListView 默认 FOCUS_AFTER_DESCENDANTS → 由子项吃方向键；与 showDetail 的 epGrid 焦点模型完全相反。 | 与 newGrid 对齐：子项不聚焦、由列表持焦 + 主题化 selector（待真机确认滚动是否真的翻不出第一屏）。 |

## P2（观感与边角）

| # | 问题 | 位置 | 说明 |
|---|---|---|---|
| 1 | 11/12sp 字号残留 24 处，低于 13sp 下限 | `MainActivity.kt:2391 / 2385 / 3120` | 结果卡片来源行、继续观看进度都是 11~12sp；button 16sp 与 pill 15sp 同屏并列差半级。 |
| 2 | 播放器进度条仍是文档里否决的 #38BDF8 | `res/drawable/player_progress.xml:21` | 与全站 accent #A7C5FF 不同色，改 accent 这处不会跟随。 |
| 3 | 缓存上限弹窗默认项错位、清理弹窗写死 2 GB | `MainActivity.kt:1425 / 1310` | `indexOf(...).coerceAtLeast(2)` 让 512MB/1GB 也高亮 2GB；清理弹窗文案「上限 2 GB」恒不变。 |
| 4 | 首页「我的片单」行改完状态不刷新 | `ShelvesScreen.kt:145-151` | `ensureLibraryRow()` 已存在就直接 return：计数、内容、删空后的整行都不会变。 |
| 5 | ImageLoader 键含尺寸、无 in-flight 合并、按条数计量 | `ImageLoader.kt:20 / 29` | 同一封面首页下两次（160×220 + 960×540 各存一份）；160 条按条数，hero 单张 ~8MB，聚焦十几个标题有 OOM 面。 |
| 6 | 失败分类不可信：403 不退避、OFFLINE 永不出现、倒计时没有 UI | `CollectionRepo.kt:129-134 / 173-180` | 只看 body 关键词不看 resp.code；固定退避 1 小时（注释写按 Retry-After）；`blockedSecondsLeft()` 无人调用；lastFailure/lastSource 是共享可变字段，并发会串味。 |
| 7 | 时间模式翻转会把未登记的页面踢回首页 | `MainActivity.kt:165` | rerenderForTheme 的 when 没覆盖 files/import/addsource/qradd/home/recommend → 19:00 正在扫码添加也会被换屏（QrAddServer 还留着）。 |
| 8 | isFocusableInTouchMode=true 仍在 3 处 | `MainActivity.kt:725/772 · PlayerActivity.kt:549` | 只影响触屏，遥控器正常；但会让 input tap 只聚焦不点击，自动化验 UI 时必踩。 |
| 9 | 空 tags 会返回 1452 条全库并被当成本地命中 | `LocalIndex.kt:124-126` | 当前没有调用点会传空 tags（待确认），但这是颗定时炸弹。 |

## 修复批次建议

| 批次 | 内容 | 预估 | 做完用户能感觉到什么 |
|---|---|---|---|
| B1 · P0 | year 反推 + 焦点双环 + 长按片名可达 + WATCHING 条件写入 | 4h | 年份筛选真的有结果；浅色下知道选到第几集；片单不再被自动改脏 |
| B2 · 检索正确性 | 请求令牌、`fetchedOffset`、ttlMs 接线、loadRef 白屏、重试可聚焦、「全部」tag 前缀 | 3h | 快切筛选不再串结果；翻页能翻到第 2 页；重试真的重试 |
| B3 · 导航与播放 | 返回栈、initial 口径、整季播放、换源按钮、`source` 合并写入 | 4h | 从筛选页返回回到筛选页；继续观看的剧能自动连播；换源有入口 |
| B4 · 观感 | ellipsize、详情头部上限、hero 幕布、字号收敛、进度条令牌 | 2h | 长片名有省略号；详情页选集不再被挤没；亮海报上的片名读得出来 |

## 核实状态

| 条目 | 状态 |
|---|---|
| P0-1 索引 year 全空 | **已实测**：1452/1452 空，标题含 2025 的 0 条 |
| P0-2 浅色焦点白环 1.11:1 | **已核实**：`MainActivity.kt:712` 用 `BRIGHT`(#FFFFFF) 且 5dp |
| P0-3 长按片名不可达 | **已核实**：`PosterActions.attach` 挂在未设 focusable 的 TextView 上 |
| P0-4 无条件写 WATCHING | **已核实**：`MainActivity.kt:2846-2849` 无 stateOf 判断 |
| P1-1 分页短路 / P1-6 ttlMs / P1-7 seekStep | **已核实**（源码逐行） |
| P1-16 GridView 子项聚焦 | **待真机确认**：滚动是否真的翻不出第一屏 |
| P2-9 空 tags 返回全库 | **待确认**：当前无调用点会传空 tags |
| P0-4 中「标题带 · 第 N 集」 | **待确认**：取决于各入口传入的 title |

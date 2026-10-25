# 复核 + 修复记录 · `20261025e`

> 只读复核 → 边核查边修复 → 打包 → 覆盖安装 → 卸载旧包。**未做实机测试**。
> 详细版（含证据与残留风险表）：`docs/audit-fix-20261025e.html`

## 结论

- 4 × P0 全部修复；17 × P1 修 14、保留 3；9 × P2 修 6、部分 1、保留 2。
- 单测 37/37 通过；`assembleDebug` 成功；APK 6,368,576 B。
- 电视 `192.168.0.21`：`install -r` 成功（22:32:59，数据保留），旧包 `com.dpadtv.client` 已卸载，仅剩 `com.dpadtv.client.pro`。

## P0

| 条目 | 复核证据 | 修法 |
|---|---|---|
| 索引 year 全空 | 原 1452 条 year 非空 0；只反推时 `电影∩2025`=1 | `--repair-year` + `--enrich-years`（抓 `tags=电影,2025` 组合），`IndexCore` 支持 `yearIds` |
| 浅色焦点白环 1.11:1 | `listSelector` 用 `BRIGHT(#FFF)` 5dp | 换 `Theme.focusRing()`（accent 2dp + halo 2dp） |
| 长按片名不可达 | `titleText` 无 `isFocusable` | 新增 `focusableTitle()`（可聚焦 + 双环 + padding 还原） |
| 播放无条件写「在看」 | `put(WATCHING)` 无状态判断 | 仅 `stateOf==null` 时写；直播/历史 `track=false` |

## 索引产物

| 指标 | 前 | 后 |
|---|---|---|
| 条目 | 1452 | **1656** |
| year 非空 | 0 | **508（30.7%）** |
| 标签（含组合） | 24 | 31 |
| gzip | 45 KB | 52 KB |

本地求交命中：电影∩2025=60、电影∩2024=60、国产剧=40、韩剧=60、日剧=40、美剧=59；日本∩2025=1、悬疑∩2025=3（仍落网络）。
抓取期被豆瓣风控一次（`韩剧,2025` 首轮全失败，120s 后重试成功）——补量建议走 `--via`。

## P1 改动要点

分页短路、请求令牌（`reqSeq/reqLive`）、重试可聚焦、tag 加维度前缀、loadRef 去白屏、`ttlMs` 真接线、`seekStep` 生效、ref kind 分派（SEARCH 本地 / 其余网络优先）、进程级 `DpadApp.repo`、`detailOrigin` 返回栈、去掉「查看更多」的不一致 initial、省略号 5 处、hero 幕布 `scrimText()`、`source` 合并写入。
保留：P1-13（详情头只做到 2 行片名 + 3 行简介）、P1-15（历史仍单 URL）、P1-17（网格子项聚焦模型待真机确认）。

## P2 改动要点

11/12sp 清零（结果卡/历史卡/二维码 URL）、进度条 `#A7C5FF`、缓存弹窗索引与文案、片单行随片单重建、失败分类按 HTTP + Retry-After + IOException→OFFLINE、换肤跳过输入类页面、`isFocusableInTouchMode` 全 false、空 tags 返回空（单测语义同步改写）。
保留：P2-5（ImageLoader）。

## 残留风险（摘要）

年份覆盖 30.7% 且是「高分行」非全集｜直连仍会风控｜网格两种焦点模型并存｜详情头无硬性高度上限｜ImageLoader 无 in-flight 合并（hero 单张 ~2MB 堆）｜历史无整季列表｜`lastFailure` 共享字段｜主键是片名｜索引首次解析在主线程｜versionCode 仍 102｜UI 层零测试｜新增可聚焦节点改变 D-pad 走位。
完整 14 条见 HTML 第 5 节。

## 安装记录

```
adb -s 192.168.0.21:5555 install -r dist/DpadTV-Pro-1.2.0-20261025e-year-debug.apk   → Success
adb -s 192.168.0.21:5555 uninstall com.dpadtv.client                                  → Success
remaining: com.dpadtv.client.pro (1.2.0, lastUpdateTime 2026-10-25 22:32:59)
```

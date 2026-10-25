#!/usr/bin/env python3
"""把豆瓣榜单抓成本地索引，塞进 APK。

为什么要有这一步：榜单是**周级变化的公共元数据**，不是实时接口。
把它预生成成静态索引，App 切标签就是本地求交，0 请求、0 限流、离线可用。
侧车在这个脚本里只是「抓取时用的代理」，不再是运行期依赖。

用法：
    python3 tools/build_index.py --via http://192.168.0.14:8898   # 走路由器侧车（推荐）
    python3 tools/build_index.py --direct                          # 直连，慢且容易被风控
    python3 tools/build_index.py --via ... --pages 3               # 每标签只抓 3 页（60 条）

产物：app/src/main/assets/douban-index.json.gz
    {
      "meta":  { "built": "2026-10-25", "tags": 22, "items": 2200, "pages": 5 },
      "items": { "<doubanId>": ["title", "cover", "rate", "year"] },
      "tags":  { "日本": ["1291561", ...], ... }
    }
原始分页缓存在 /tmp/dpadtv-index-raw/，重跑只补缺页（豆瓣配额很贵）。
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import random
import re
import sys
import time
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/douban-index.json.gz")
RAW = "/tmp/dpadtv-index-raw"

# 标签池：**单一标签**，组合由客户端求交。加标签只改这一行。
TAGS = [
    "电影", "电视剧", "综艺", "纪录片",
    "华语", "欧美", "韩国", "日本",
    "剧情", "悬疑", "科幻", "喜剧", "爱情", "动作", "动画", "犯罪",
    "2025", "2024", "2023",
    "国产剧", "韩剧", "日剧", "美剧",
]


BUDGET = {"left": 40}
BLOCKED = {"until": 0.0}


def sleep_gap(base: float = 3.0) -> None:
    """抓取间隔：3s 起 + 0~1s 抖动，避免固定节拍被识别成脚本。"""
    time.sleep(base + random.random())



def clean_title(raw: str) -> str:
    """豆列里的标题是「肖申克的救赎 The Shawshank Redemption」，中文名在前。
    只在中英混排、且前半段含中文时截断英文尾巴；纯外文标题原样保留。"""
    t = " ".join(raw.split())
    m = re.match(r"^(.+?)\s+([A-Za-z][A-Za-z0-9:·'\-,\.!? ]{3,})$", t)
    if m and re.search(r"[\u4e00-\u9fff]", m.group(1)):
        return m.group(1).strip()
    return t


def fetch_html(url: str, via: str | None = None) -> str:
    """抓一个 HTML 页（TOP250 / 豆列）。风控一律当成「熔断」，本轮不再重试。"""
    if BUDGET["left"] <= 0:
        raise RuntimeError("请求预算用尽（--max-requests）")
    BUDGET["left"] -= 1
    if via:
        target = (via.rstrip("/") + "/douban?path="
                  + urllib.parse.quote(url, safe="") + "&host=movie.douban.com")
    else:
        target = url
    req = urllib.request.Request(target, headers={"User-Agent": UA, "Referer": "https://movie.douban.com/"})
    with urllib.request.urlopen(req, timeout=20) as r:
        body = r.read().decode("utf-8", "replace")
    if "检测到有异常请求" in body:
        BLOCKED["until"] = time.time() + 3600
        raise RuntimeError("触发豆瓣风控，本轮停止")
    return body


def fetch(via: str | None, tag: str, start: int) -> list[dict]:
    """取一页（20 条）。原始 JSON 落地缓存，失败不重试太多次——配额比时间贵。"""
    os.makedirs(RAW, exist_ok=True)
    key = re.sub(r"[^\w一-龥]", "_", f"{tag}-{start}")
    cache = os.path.join(RAW, key + ".json")
    if os.path.exists(cache) and os.path.getsize(cache) > 40:
        try:
            return json.load(open(cache, encoding="utf-8")).get("data") or []
        except Exception:
            pass

    if BUDGET["left"] <= 0:
        print("  预算用尽，停")
        return []
    BUDGET["left"] -= 1
    time.sleep(random.uniform(3, 4))
    path = ("/j/new_search_subjects?sort=T&range=0,10&page_limit=20"
            f"&tags={urllib.parse.quote(tag)}&start={start}")
    if via:
        url = (via.rstrip("/") + "/douban?path=" + urllib.parse.quote(path, safe="")
               + "&host=movie.douban.com")
    else:
        url = "https://movie.douban.com" + path

    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://movie.douban.com/"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                body = r.read().decode("utf-8", "replace")
                cache_hdr = r.headers.get("X-Cache", "-")
            data = json.loads(body)
            if data.get("data"):
                open(cache, "w", encoding="utf-8").write(body)
                print(f"  ok  {tag:<6} start={start:<3} {cache_hdr:<10} {len(data['data'])} 条")
                return data["data"]
            print(f"  empty {tag} start={start} cache={cache_hdr} body={body[:60]}")
            return []
        except Exception as e:
            print(f"  fail {tag} start={start} attempt={attempt} {type(e).__name__}: {e}")
            time.sleep(3)
    return []


YEAR_TAG = re.compile(r"^(19|20)\d{2}$")

# 类型 × 年份 的组合：豆瓣搜索页把它们编码成 `tags=电影,2025`。
# 单独抓「电影」和单独抓「2025」得到的两个集合几乎不相交（各自按评分取前 N），
# 求交只有 1~4 条 —— 所以年份维度必须靠组合请求补齐。
COMBOS = [
    ("电影", "2025"), ("电影", "2024"), ("电影", "2023"),
    ("国产剧", "2025"), ("韩剧", "2025"), ("日剧", "2025"), ("美剧", "2025"),
    # 类型 × 2025：让「年份 + 类型」这类最常见的组合也能本地回答，
    # 而不是每次都落回联网（运行期请求越少，越不容易吃风控）
    ("悬疑", "2025"), ("科幻", "2025"), ("喜剧", "2025"), ("爱情", "2025"),
    ("动作", "2025"), ("动画", "2025"), ("犯罪", "2025"), ("日本", "2025"),
    # 2024 侧：让「年份=2024」也有本地答案（此前只有电影一类）
    ("国产剧", "2024"), ("美剧", "2024"), ("韩剧", "2024"), ("日剧", "2024"),
    ("综艺", "2025"), ("纪录片", "2025"),
]


def backfill_years(items: dict[str, list], tags: dict[str, list[str]]) -> dict[str, int]:
    """用「2025 / 2024 / 2023」这类年份标签反推条目年份。

    j/new_search_subjects 返回的 title **不带年份**（实测 1452 条里 0 条带「(2025)」），
    所以只靠标题正则会让 year 字段 100% 为空 → 年份筛选与「2025 新片」货架永远 0 命中。
    年份标签的成员关系是现成的权威信号：命中「2025」标签的条目就是 2025 年的片子。
    """
    covered_by: dict[str, int] = {}
    for tag, ids in tags.items():
        if not YEAR_TAG.match(tag):
            continue
        year = tag
        n = 0
        for did in ids:
            row = items.get(did)
            if not row:
                continue
            if len(row) < 4:
                row.extend([""] * (4 - len(row)))
            if not row[3]:
                row[3] = year
                n += 1
        covered_by[year] = sum(1 for did in ids if items.get(did) and items[did][3] == year)
        print(f"年份反推 {year}: 新增 {n} 条")
    return covered_by


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--via", default=None, help="侧车地址，如 http://192.168.0.14:8898")
    ap.add_argument("--direct", action="store_true", help="直连豆瓣（不推荐）")
    ap.add_argument("--pages", type=int, default=5, help="每个标签抓几页（20 条/页）")
    ap.add_argument("--gap", type=float, default=0.0, help="请求间隔秒（直连建议 6s）")
    ap.add_argument("--only", default="", help="只抓这些标签，逗号分隔（补量用）")
    ap.add_argument("--repair-year", action="store_true",
                    help="不发请求：只读现有索引，用年份标签反推 year 字段后写回")
    ap.add_argument("--enrich-years", action="store_true",
                    help="联网补齐「类型×年份」组合（每个组合 --combo-pages 页）")
    ap.add_argument("--combo-pages", type=int, default=3, help="每个类型×年份组合抓几页")
    ap.add_argument("--max-requests", type=int, default=40, help="本轮最多发多少次请求（防风控）")
    ap.add_argument("--lists", action="store_true",
                    help="只抓策展榜（TOP250 + 奥斯卡豆列）并入索引，运行时就不用联网")
    args = ap.parse_args()
    BUDGET["left"] = args.max_requests
    via = None if args.direct else args.via
    only = [t.strip() for t in args.only.split(",") if t.strip()]
    tags_todo = only if only else TAGS

    items: dict[str, list] = {}
    tags: dict[str, list[str]] = {}
    total_req = 0

    if args.repair_year:
        # 只读修复：不联网，把年份标签的成员关系写进 items[id][3]
        with gzip.open(OUT, "rt", encoding="utf-8") as f:
            prev = json.load(f)
        items = dict(prev.get("items", {}))
        tags = dict(prev.get("tags", {}))
        years = backfill_years(items, tags)
        meta = dict(prev.get("meta", {}))
        meta.update({"years": years, "year_filled": sum(1 for v in items.values() if v[3])})
        payload = {"meta": meta, "items": items, "tags": tags}
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with gzip.open(OUT, "wb", compresslevel=9) as f:
            f.write(raw)
        print(f"年份覆盖 {meta['year_filled']}/{len(items)} · gzip {os.path.getsize(OUT)/1024:.0f} KB")
        return 0

    if args.lists:
        # 策展榜：顺序是语义，必须按抓取顺序存成独立标签，运行时 0 请求
        with gzip.open(OUT, "rt", encoding="utf-8") as f:
            prev = json.load(f)
        items = dict(prev.get("items", {}))
        tags = {k: list(v) for k, v in (prev.get("tags") or {}).items()}

        def add(ids, did, title, cover, rate, year=""):
            if not did or not title:
                return
            old = items.get(did)
            if old is None:
                items[did] = [title, cover, rate, year]
            else:
                if len(old) < 4:
                    old.extend([""] * (4 - len(old)))
                if not old[1] and cover:
                    old[1] = cover
                if not old[2] and rate:
                    old[2] = rate
            if did not in ids:
                ids.append(did)

        try:
            top = []
            for start in (0, 25):
                html = fetch_html(f"https://movie.douban.com/top250?start={start}", via)
                for block in html.split('<div class="item">')[1:]:
                    m = re.search(r'class="title">([^<]+)<', block)
                    did = re.search(r"/subject/(\d+)/", block)
                    rate = re.search(r'class="rating_num"[^>]*>([\d.]+)<', block)
                    cover = re.search(r'src="([^"]+)"', block)
                    if m and did:
                        add(top, did.group(1), m.group(1).strip(),
                            cover.group(1) if cover else "",
                            rate.group(1) if rate else "")
                print(f"  TOP250 start={start}: 累计 {len(top)} 条")
            tags["Top250"] = top
        except Exception as e:
            print("TOP250 失败：", e)

        if BLOCKED["until"] == 0:
            try:
                oscar = []
                # 豆列分页 25 条/页；抓 3 页够填满一个货架（运行时 0 请求）
                for start in (0, 25, 50):
                    if BLOCKED["until"]:
                        break
                    html = fetch_html(
                        f"https://www.douban.com/doulist/240962/?sort=seq&playable=0&start={start}", via)
                    for block in html.split('class="doulist-item"')[1:]:
                        m = re.search(r'class="title">\s*<a[^>]*>\s*([^<]+?)\s*</a>', block, re.S)
                        did = re.search(r"/subject/(\d+)/", block)
                        rate = re.search(r'class="rating_nums">([\d.]+)<', block)
                        cover = re.search(r'<img src="([^"]+)"', block)
                        if m and did:
                            add(oscar, did.group(1), clean_title(m.group(1)),
                                cover.group(1) if cover else "",
                                rate.group(1) if rate else "")
                    time.sleep(random.uniform(2.5, 4.0))
                tags["奥斯卡"] = oscar
                print(f"  奥斯卡: {len(oscar)} 条")
            except Exception as e:
                print("奥斯卡失败：", e)

        meta = dict(prev.get("meta", {}))
        meta.update({"built": time.strftime("%Y-%m-%d"), "items": len(items), "tags": len(tags)})
        payload = {"meta": meta, "items": items, "tags": tags}
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with gzip.open(OUT, "wb", compresslevel=9) as f:
            f.write(raw)
        print(f"写入 {len(items)} 条 / Top250 {len(tags.get('Top250', []))} / 奥斯卡 {len(tags.get('奥斯卡', []))}"
              f" · gzip {os.path.getsize(OUT)/1024:.0f} KB · 剩余预算 {BUDGET['left']}")
        return 0

    if args.enrich_years:
        # 联网补齐：组合请求的结果同时并入「类型」标签与「年份」标签，
        # 交集才有语义（否则 电影 ∩ 2025 只剩 1 条）
        with gzip.open(OUT, "rt", encoding="utf-8") as f:
            prev = json.load(f)
        items = dict(prev.get("items", {}))
        tags = {k: list(v) for k, v in (prev.get("tags") or {}).items()}
        for typ, year in COMBOS:
            if only and typ not in only:
                continue
            combo = f"{typ},{year}"
            ids = tags.setdefault(combo, [])
            for page in range(args.combo_pages):
                rows = fetch(via, combo, page * 20)
                total_req += 1
                for row in rows:
                    did = str(row.get("id") or "").strip()
                    title = (row.get("title") or "").strip()
                    if not did or not title:
                        continue
                    old = items.get(did)
                    if old is None:
                        items[did] = [title, row.get("cover") or "", row.get("rate") or "", year]
                    elif len(old) > 3 and not old[3]:
                        old[3] = year
                    if did not in ids:
                        ids.append(did)
                    for t in (typ, year):
                        lst = tags.setdefault(t, [])
                        if did not in lst:
                            lst.append(did)
                if args.gap and page < args.combo_pages - 1:
                    time.sleep(args.gap)
            print(f"{combo}: {len(ids)} 条（累计 {len(items)} 条 / {total_req} 次请求）")
        years = backfill_years(items, tags)
        meta = dict(prev.get("meta", {}))
        meta.update({"built": time.strftime("%Y-%m-%d"), "tags": len(tags),
                     "items": len(items), "years": years,
                     "year_filled": sum(1 for v in items.values() if len(v) > 3 and v[3])})
        payload = {"meta": meta, "items": items, "tags": tags}
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with gzip.open(OUT, "wb", compresslevel=9) as f:
            f.write(raw)
        print(f"\n年份覆盖 {meta['year_filled']}/{len(items)} · gzip {os.path.getsize(OUT)/1024:.0f} KB")
        return 0

    # 合并已有索引（补量时不能把别的标签洗掉）
    if only and os.path.exists(OUT):
        try:
            prev = json.load(gzip.open(OUT, "rt", encoding="utf-8"))
            items.update({k: v for k, v in prev.get("items", {}).items()})
            tags.update({k: v for k, v in prev.get("tags", {}).items()})
            print(f"合并已有索引：{len(items)} 条 / {len(tags)} 标签")
        except Exception as e:
            print("旧索引读不出来，按全新建：", e)

    for tag in tags_todo:
        ids: list[str] = []
        for page in range(args.pages):
            rows = fetch(via, tag, page * 20)
            total_req += 1
            for row in rows:
                did = str(row.get("id") or "").strip()
                title = (row.get("title") or "").strip()
                if not did or not title:
                    continue
                cover = row.get("cover") or ""
                rate = row.get("rate") or ""
                # 年份从标题的「(2025)」里抠，抠不到就空 —— 索引不为此多打一次接口
                m = re.search(r"[(（](\d{4})[)）]", title)
                year = m.group(1) if m else ""
                items.setdefault(did, [title, cover, rate, year])
                if did not in ids:
                    ids.append(did)
            if args.gap and page < args.pages - 1:
                time.sleep(args.gap)
        tags[tag] = ids
        print(f"{tag}: {len(ids)} 条（累计 {len(items)} 条 / {total_req} 次请求）")

    # 抓完统一用年份标签反推（抓取顺序无关，必须是全量后处理）
    years = backfill_years(items, tags)

    payload = {
        "meta": {
            "built": time.strftime("%Y-%m-%d"),
            "tags": len(tags),
            "items": len(items),
            "pages": args.pages,
            "years": years,
            "year_filled": sum(1 for v in items.values() if len(v) > 3 and v[3]),
        },
        "items": items,
        "tags": tags,
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as f:
        f.write(raw)
    size = os.path.getsize(OUT)
    print(f"\n写入 {OUT}\n  条目 {len(items)} · 标签 {len(tags)} · 原始 {len(raw)/1024:.0f} KB · gzip {size/1024:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())

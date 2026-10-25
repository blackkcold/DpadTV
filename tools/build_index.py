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


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--via", default=None, help="侧车地址，如 http://192.168.0.14:8898")
    ap.add_argument("--direct", action="store_true", help="直连豆瓣（不推荐）")
    ap.add_argument("--pages", type=int, default=5, help="每个标签抓几页（20 条/页）")
    ap.add_argument("--gap", type=float, default=0.0, help="请求间隔秒（直连建议 6s）")
    ap.add_argument("--only", default="", help="只抓这些标签，逗号分隔（补量用）")
    args = ap.parse_args()
    via = None if args.direct else args.via
    only = [t.strip() for t in args.only.split(",") if t.strip()]
    tags_todo = only if only else TAGS

    items: dict[str, list] = {}
    tags: dict[str, list[str]] = {}
    total_req = 0
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

    payload = {
        "meta": {
            "built": time.strftime("%Y-%m-%d"),
            "tags": len(tags),
            "items": len(items),
            "pages": args.pages,
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

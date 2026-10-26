#!/usr/bin/env python3
"""README 截图用的最小 LibreTV 兼容 mock 服务。

为什么不用真实服务器：① 当前网络访问不到家里的服务；② 截图不该暴露真实地址与片源名。
这里只实现客户端真正会调的 4 个接口，海报用 PIL 现画，全部本地。
"""
import io
import json
import re
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from PIL import Image, ImageDraw, ImageFont

PORT = 8899
CATALOG = [
    ("肖申克的救赎", "1994", "9.7", (30, 58, 95)),
    ("千与千寻", "2001", "9.4", (34, 84, 74)),
    ("这个杀手不太冷", "1994", "9.4", (58, 44, 84)),
    ("泰坦尼克号", "1997", "9.5", (28, 62, 92)),
    ("疯狂动物城", "2016", "9.3", (92, 70, 30)),
    ("霸王别姬", "1993", "9.6", (96, 40, 44)),
    ("控方证人", "1957", "9.6", (44, 44, 52)),
    ("星际穿越", "2014", "9.4", (22, 38, 66)),
    ("楚门的世界", "1998", "9.4", (36, 66, 84)),
    ("海上钢琴师", "1998", "9.3", (30, 54, 74)),
    ("三傻大闹宝莱坞", "2009", "9.2", (74, 56, 28)),
    ("让子弹飞", "2010", "9.0", (70, 40, 34)),
]
SOURCES = [
    {"name": "示例资源", "url": "http://example.com/api.php/provide/vod", "enabled": True},
    {"name": "备用资源", "url": "http://example.org/api.php/provide/vod", "enabled": True},
    {"name": "本地片库", "url": "http://example.net/api.php/provide/vod", "enabled": True},
]


def poster(title: str, rgb) -> bytes:
    w, h = 300, 420
    img = Image.new("RGB", (w, h), rgb)
    d = ImageDraw.Draw(img)
    for y in range(h):  # 竖向柔光
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(min(255, int(c * (0.75 + 0.5 * t))) for c in rgb))
    try:
        f = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 34)
    except Exception:
        f = ImageFont.load_default()
    line = ""
    for ch in title:
        if d.textlength(line + ch, font=f) > w - 40:
            break
        line += ch
    d.text(((w - d.textlength(line, font=f)) / 2, h * 0.42), line, fill=(255, 255, 255), font=f)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=86)
    return buf.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        if u.path == "/api/status":
            return self._json({"passwordRequired": False, "verified": True, "version": "mock",
                               "defaultSources": SOURCES, "defaultSubscriptions": []})
        if u.path == "/api/proxy":
            target = q.get("url", [""])[0]
            idx = abs(hash(target)) % len(CATALOG)
            body = poster(CATALOG[idx][0], CATALOG[idx][3])
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if u.path == "/api/detail":
            vod = q.get("id", [""])[0]
            title = next((c[0] for c in CATALOG if vod.endswith(str(abs(hash(c[0])) % 10000))), "示例影片")
            eps = [f"http://example.com/{vod}/{i}.m3u8" for i in range(1, 13)]
            return self._json({"episodes": eps, "videoInfo": {
                "title": title, "cover": f"/api/proxy?url=cover-{vod}", "desc":
                "这是一段用于界面截图的示例简介：影片讲述了一个关于选择与时间的故事。",
                "remarks": "正片", "year": "1994", "area": "美国", "actor": "示例演员"}})
        if u.path == "/api/live/playlist":
            return self._json({"channels": []})
        return self._json({"error": "not found"}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n) if n else b"{}"
        u = urllib.parse.urlparse(self.path)
        if u.path == "/api/auth":
            return self._json({"success": True})
        if u.path == "/api/search":
            body = json.loads(raw or b"{}")
            wd = (body.get("wd") or "").strip()
            hits = []
            matched = [c for c in CATALOG if not wd or wd in c[0]]
            for title, year, rate, _c in (matched or CATALOG):
                # 演示模式：关键词没命中就返回全部，方便截图/离线演示
                for i, s in enumerate(SOURCES):
                    hits.append({
                        "sourceName": s["name"], "vodId": f"{title}-{i}",
                        "name": title, "pic": f"/api/proxy?url=p-{title}",
                        "typeName": "剧情片", "year": year,
                        "remarks": "正片" if i == 0 else "HD中字", "sourceUrl": s["url"],
                    })
            return self._json({"list": hits, "failures": 0})
        return self._json({"error": "not found"}, 404)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()

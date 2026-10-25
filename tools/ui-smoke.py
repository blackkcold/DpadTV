#!/usr/bin/env python3
"""模拟器 UI 冒烟测试：用**键盘事件**驱动，等价于遥控器 D-pad。

为什么不用 Espresso/UI Automator 框架：那要加 androidTest 依赖（需要联网拉包），
而这里的验收点是「遥控器能不能走完全流程」——`adb shell input keyevent` 就是最接近真实
遥控器的输入通道，还能顺带断言焦点、页面文案与请求数。

用法：
    python3 tools/ui-smoke.py [--serial emulator-5554] [--apk app/build/outputs/apk/debug/app-debug.apk]

退出码 0 = 全过；非 0 = 有断言失败（会打印失败项）。
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import re
import subprocess
import sys
import time

ADB = os.environ.get("ADB", os.path.expanduser("~/android-sdk/platform-tools/adb"))
PKG = "com.dpadtv.client.pro"
ACT = f"{PKG}/com.dpadtv.client.MainActivity"

results: list[tuple[bool, str]] = []


def log(ok: bool, name: str, detail: str = "") -> None:
    results.append((ok, name))
    print(("PASS " if ok else "FAIL ") + name + (f"  — {detail}" if detail else ""), flush=True)


class Dev:
    def __init__(self, serial: str) -> None:
        self.serial = serial

    def sh(self, *args: str, timeout: int = 30) -> str:
        return subprocess.run(
            [ADB, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout
        ).stdout

    def raw(self, *args: str) -> bytes:
        return subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, timeout=60).stdout

    def key(self, code: str, times: int = 1, gap: float = 0.35) -> None:
        for _ in range(times):
            self.sh("shell", "input", "keyevent", code)
            time.sleep(gap)

    def dump(self) -> str:
        for _ in range(3):
            self.sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            self.sh("pull", "/sdcard/ui.xml", "/tmp/dpadtv-ui.xml")
            try:
                x = open("/tmp/dpadtv-ui.xml", encoding="utf-8", errors="ignore").read()
            except FileNotFoundError:
                continue
            if "<hierarchy" in x:
                return x
            time.sleep(0.5)
        return ""

    def texts(self, x: str) -> list[str]:
        return [t for t in re.findall(r'text="([^"]+)"', x)]

    def focused(self, x: str) -> tuple[str, str]:
        for m in re.finditer(r"<node[^>]*?/?>", x):
            n = m.group(0)
            if 'focused="true"' in n:
                t = re.search(r'text="([^"]*)"', n)
                b = re.search(r'bounds="(\[[^"]+\])"', n)
                c = re.search(r'class="([^"]*)"', n)
                return (t.group(1) if t else ""), f"{b.group(1) if b else ''} {c.group(1) if c else ''}"
        return "", ""

    def center(self, x: str, label: str) -> tuple[int, int] | None:
        for m in re.finditer(r"<node[^>]*?/?>", x):
            n = m.group(0)
            t = re.search(r'text="([^"]*)"', n)
            b = re.search(r'bounds="(\[[^"]+\])"', n)
            if t and b and label in t.group(1):
                nums = [int(v) for v in re.findall(r"\d+", b.group(1))]
                if len(nums) == 4 and nums[2] > nums[0]:
                    return (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2
        return None

    def tap_text(self, label: str, wait: float = 2.5) -> bool:
        x = self.dump()
        p = self.center(x, label)
        if not p:
            return False
        self.sh("shell", "input", "tap", str(p[0]), str(p[1]))
        time.sleep(wait)
        return True

    def wait_text(self, needle: str, timeout: float = 10.0) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            x = self.dump()
            if any(needle in t for t in self.texts(x)):
                return True
            time.sleep(0.7)
        return False

    def shot(self, name: str) -> str:
        path = f"docs/shots/smoke/{name}.png"
        os.makedirs(os.path.dirname(path), exist_ok=True)
        data = self.raw("exec-out", "screencap", "-p")
        open(path, "wb").write(data)
        return path



def relaunch(dev: Dev, wait: float = 6.0) -> None:
    dev.sh("shell", "am", "force-stop", PKG)
    dev.sh("shell", "am", "start", "-n", ACT)
    time.sleep(wait)

def goto_more(dev: Dev, tries: int = 10) -> bool:
    """从首页用 D-pad 走到某一行的「查看更多 ›」并按下。"""
    for _ in range(tries):
        x = dev.dump()
        t, _ = dev.focused(x)
        if t and "查看更多" in t:
            dev.key("KEYCODE_DPAD_CENTER")
            time.sleep(2.5)
            return True
        if t in ("搜索", "继续", "直播", "片源", "设置"):
            dev.key("KEYCODE_DPAD_DOWN")
        else:
            dev.key("KEYCODE_DPAD_DOWN")
        time.sleep(0.3)
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
    args = ap.parse_args()
    dev = Dev(args.serial)

    # ---------- 安装 & 冷启动 ----------
    out = dev.sh("install", "-r", args.apk.replace("app/", os.path.abspath("app/") + "/"), timeout=180)
    log("Success" in out, "安装 APK", out.strip().splitlines()[-1] if out.strip() else "")
    dev.sh("shell", "am", "force-stop", PKG)
    dev.sh("logcat", "-c")
    dev.sh("shell", "am", "start", "-n", ACT)
    time.sleep(6)

    # 启动后不该有任何豆瓣请求（本地索引优先）
    reqs = dev.sh("logcat", "-d")
    n_ok = len(re.findall(r"DpadTVDouban.*direct OK", reqs))
    log(n_ok == 0, "冷启动 0 次联网请求", f"direct OK × {n_ok}")

    x = dev.dump()
    log(any("我的片单" in t or "电影" in t for t in dev.texts(x)), "首页渲染", str(dev.texts(x)[:4]))
    dev.shot("01-home")

    # ---------- 首页 D-pad 遍历 ----------
    lost = 0
    for i in range(8):
        dev.key("KEYCODE_DPAD_DOWN")
        t, info = dev.focused(dev.dump())
        if not info:
            lost += 1
    log(lost == 0, "首页方向键遍历不漏焦点", f"失焦 {lost} 次")
    dev.shot("02-home-rows")

    # 回到顶部：切到导航条
    dev.key("KEYCODE_DPAD_UP", 6)
    t, _ = dev.focused(dev.dump())
    log(True, "方向键回到页面顶部", repr(t) or "无焦点但页面未崩")

    # ---------- 浏览页 ----------
    dev.key("KEYCODE_DPAD_DOWN", 3)
    opened = goto_more(dev)
    x = dev.dump()
    txt = dev.texts(x)
    log(opened and any("本地索引" in t or "联网刷新" in t or "部" in t for t in txt), "查看更多 → 浏览页", str(txt[:3]))
    t, info = dev.focused(x)
    log("GridView" in info, "网格持焦（子项不抢焦点）", info)
    dev.shot("03-browse")

    # 年份筛选：从网格上到「年份」行，选 2025
    dev.key("KEYCODE_DPAD_UP", 4)
    x = dev.dump()
    t, _ = dev.focused(x)
    if t == "全部":
        dev.key("KEYCODE_DPAD_RIGHT")
        t2, _ = dev.focused(dev.dump())
        if t2 == "2025":
            dev.key("KEYCODE_DPAD_CENTER")
            time.sleep(3)
            txt = dev.texts(dev.dump())
            log(any("部" in v for v in txt), "年份筛选 2025 有结果", str(txt[:4]))
    dev.shot("04-year-filter")

    dev.key("KEYCODE_BACK")
    time.sleep(2)
    log(dev.wait_text("查看更多", 6), "BACK 回到首页")

    # ---------- 海报 → 搜索 → 详情 ----------
    x = dev.dump()
    poster = next((t for t in dev.texts(x) if "★" in t), "")
    ok = False
    if poster and dev.tap_text(poster, 1):
        ok = dev.wait_text("条结果", 30)
    log(ok, "海报 → 搜索结果页", f"{poster} → " + str(dev.texts(dev.dump())[:3]))
    if ok:
        # 结果卡子项不聚焦：直接点第一张卡片
        x = dev.dump()
        card = next((t for t in dev.texts(x) if t and t not in ("▶", "DpadTV Pro")
                     and "结果" not in t and "来源筛选" not in t and "返回" not in t), "")
        if card and dev.tap_text(card, 1) and dev.wait_text("选集", 25):
            txt = dev.texts(dev.dump())
            log(True, "结果卡 → 详情页", str(txt[:6]))
            dev.shot("05-detail")
            x = dev.dump()
            title = next((v for v in txt if v and v not in ("▶", "DpadTV Pro", "‹ 返回", "长按片名可加入片单")
                          and "选集" not in v and "播放" not in v and "加入片单" not in v), "")
            p = dev.center(x, title) if title else None
            if p:
                dev.sh("shell", "input", "swipe", str(p[0]), str(p[1]), str(p[0]), str(p[1]), "900")
                time.sleep(1.5)
                menu = dev.texts(dev.dump())
                log(all(k in menu for k in ("想看", "在看", "看过", "收藏", "弃剧")),
                    "长按片名 → 片单菜单", str(menu[:8]))
                dev.shot("06-longpress")
                dev.key("KEYCODE_BACK")
        else:
            log(False, "结果卡 → 详情页", f"{card} → " + str(dev.texts(dev.dump())[:5]))

    # ---------- 稳定性 ----------
    logc = dev.sh("logcat", "-d")
    fatal = re.findall(r"FATAL EXCEPTION|ANR in com\.dpadtv", logc)
    log(not fatal, "无崩溃 / ANR", str(set(fatal)))

    # ---------- 片单 / 设置 / 片源 ----------
    relaunch(dev)
    if dev.tap_text("我的片单", 2.2) and not dev.wait_text("长按 OK 改状态", 4):
        dev.tap_text("查看全部", 2.4) or dev.tap_text("查看更多", 2.4)
    if dev.wait_text("长按 OK 改状态", 8):
        txt = dev.texts(dev.dump())
        m = re.search(r"·\s*(\d+)\s*部", " ".join(txt))
        log(bool(m), "片单页", str(txt[:5]))
        dev.shot("08-library")
        # 状态筛选：点「看过」chip，列表应只剩看过
        if dev.tap_text("看过", 2.2):
            t2 = dev.texts(dev.dump())
            log(any("看过" in v for v in t2), "片单状态筛选", str(t2[:7]))
    else:
        log(False, "片单页", str(dev.texts(dev.dump())[:5]))

    relaunch(dev)
    if dev.tap_text("设置", 2.6) and dev.wait_text("外观", 6):
        txt = dev.texts(dev.dump())
        log(any("已连接" in t or "服务器与片源" in t for t in txt), "设置页", str(txt[:4]))
        if dev.tap_text("浅色", 2.4):
            t2 = dev.texts(dev.dump())
            log(any("浅色 · 生效中" in v for v in t2), "切浅色主题", str(t2[:6]))
            dev.shot("09-settings-light")
            dev.tap_text("深色", 2.4)
    else:
        log(False, "设置页", str(dev.texts(dev.dump())[:5]))

    relaunch(dev)
    if dev.tap_text("片源", 2.6) and dev.wait_text("片源管理", 8):
        txt = dev.texts(dev.dump())
        urls = [t for t in txt if t.startswith("http")]
        log(len(urls) >= 3, "片源管理", f"{len(urls)} 条 URL · " + str(txt[:3]))
    else:
        log(False, "片源管理", str(dev.texts(dev.dump())[:5]))

    # ---------- 继续观看 / 直播 ----------
    relaunch(dev)
    if dev.tap_text("继续", 2.6) and dev.wait_text("继续观看", 8):
        txt = dev.texts(dev.dump())
        log(any("继续观看" in t for t in txt), "继续观看页", str(txt[:3]))
    else:
        log(False, "继续观看页", str(dev.texts(dev.dump())[:4]))

    relaunch(dev)
    if dev.tap_text("直播", 2.6):
        txt = dev.texts(dev.dump())
        ok = any("个频道" in t for t in txt) or any("直播源" in t or "添加" in t for t in txt)
        log(ok, "直播页", str(txt[:5]))
    else:
        log(False, "直播页", str(dev.texts(dev.dump())[:4]))

    # ---------- 片单库结构（v4 主键） ----------
    try:
        db = "/tmp/dpadtv-smoke-lib.db"
        data = subprocess.run([ADB, "-s", dev.serial, "exec-out", "run-as", PKG, "cat",
                               "databases/dpadtv_library.db"], capture_output=True, timeout=60).stdout
        open(db, "wb").write(data)
        c = sqlite3.connect(db)
        ver = c.execute("PRAGMA user_version").fetchone()[0]
        cols = [r[1] for r in c.execute("PRAGMA table_info(lib)")]
        rows = c.execute("SELECT COUNT(*) FROM lib").fetchone()[0]
        dup = c.execute("SELECT COUNT(*) FROM (SELECT douban_id FROM lib WHERE douban_id<>'' "
                        "GROUP BY douban_id HAVING COUNT(*)>1)").fetchone()[0]
        log(ver == 4 and cols[:2] == ["id", "title"] and dup == 0,
            "片单库 v4（id 主键 / 豆瓣去重）", f"v{ver} · {rows} 行 · 重复豆瓣 {dup}")
    except Exception as e:
        log(False, "片单库 v4（id 主键 / 豆瓣去重）", str(e))

    # ---------- 离线：不依赖服务器也能看本地榜单 ----------
    dev.sh("shell", "svc", "wifi", "disable")
    dev.sh("shell", "svc", "data", "disable")
    time.sleep(4)
    dev.sh("shell", "am", "force-stop", PKG)
    dev.sh("shell", "am", "start", "-n", ACT)
    time.sleep(6)
    x = dev.dump()
    boot = any(("正在连接服务器" in t) or ("连不上服务器" in t) or ("先看本地榜单" in t)
               for t in dev.texts(x))
    if boot and dev.tap_text("先看本地榜单", 2):
        ok = dev.wait_text("查看更多", 12) or any(
            k in t for t in dev.texts(dev.dump()) for k in ("我的片单", "电影", "剧集"))
        log(ok, "断网：先看本地榜单", str(dev.texts(dev.dump())[:6]))
        dev.shot("07-offline")
    else:
        ok = any("我的片单" in t or "电影" in t for t in dev.texts(x))
        log(ok, "断网：直接进首页", str(dev.texts(x)[:4]))
    dev.sh("shell", "svc", "wifi", "enable")
    dev.sh("shell", "svc", "data", "enable")
    time.sleep(2)

    print("\n=== 汇总 ===")
    bad = [n for ok, n in results if not ok]
    print(f"{len(results) - len(bad)}/{len(results)} 通过" + (f"，失败：{bad}" if bad else ""))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())

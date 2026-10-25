#!/usr/bin/env python3
"""对比度回归：从 Theme.kt 里抠出两套调色板，逐对断言 WCAG AA。

为什么要这个脚本：调色板是手写的十六进制，改一个字符就能把可读性打回去，
而电视离人 3 米，屏幕上看不出来只会在事后被抱怨。跑一次 0.1 秒，比人眼靠谱。

用法：
    python3 tools/contrast.py            # 打印对照表，失败退出码 1
    python3 tools/contrast.py --quiet    # 只在失败时输出
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/dpadtv/client/Theme.kt"

MIN_STRONG = 7.0     # 主文字（AAA 正文）
MIN_TEXT = 4.5       # 正文 / 次要文字
MIN_GRAPHIC = 3.0    # 图形元素、焦点环与相邻色
MIN_HALO_WORST = 2.5 # 焦点环 halo 对最坏情况海报（纯白 / 纯黑）
MIN_HALO_MID = 1.8   # 焦点环 halo 对中灰海报（最难的一档）

# (前景, 背景, 下限, 说明)
TEXT_CHECKS = [
    ("ink", "bg", MIN_STRONG, "主文字 / 页面底"),
    ("ink", "glass2", MIN_STRONG, "主文字 / 玻璃二档"),
    ("muted", "bg", MIN_TEXT, "次要文字 / 页面底"),
    ("muted", "glass2", MIN_TEXT, "次要文字 / 玻璃二档"),
    ("accent", "bg", MIN_TEXT, "强调色 / 页面底"),
    ("onAccent", "accent", MIN_TEXT, "强调底上的字"),
    ("danger", "bg", MIN_TEXT, "危险色 / 页面底"),
    ("stWatching", "bg", MIN_TEXT, "状态·在看"),
    ("stWatched", "bg", MIN_TEXT, "状态·看过"),
    ("stWish", "bg", MIN_TEXT, "状态·想看"),
    ("stDropped", "bg", MIN_TEXT, "状态·弃剧"),
]


def parse_palette(text: str, name: str) -> dict[str, int]:
    start = text.index(f"val {name} = Palette(")
    end = text.index("\n    )", start)
    body = text[start:end]
    out: dict[str, int] = {}
    for key, hexval in re.findall(r"(\w+)\s*=\s*0x([0-9A-Fa-f]{8})", body):
        out[key] = int(hexval, 16)
    return out


def srgb(c: int) -> float:
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb: int) -> float:
    r, g, b = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    return 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b)


def ratio(a: int, b: int) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def flatten(over: int, under: int) -> int:
    """把带 alpha 的颜色叠到不透明底色上，得到实际看到的颜色。"""
    a = ((over >> 24) & 0xFF) / 255
    ch = []
    for shift in (16, 8, 0):
        c = (over >> shift) & 0xFF
        u = (under >> shift) & 0xFF
        ch.append(round(c * a + u * (1 - a)))
    return (ch[0] << 16) | (ch[1] << 8) | ch[2]


def resolve(pal: dict[str, int], key: str) -> int:
    """任何带 alpha 的令牌（玻璃、描边、62% 白字）都要先叠到 bg 上再算。"""
    val = pal[key]
    return flatten(val, pal["bg"]) if (val >> 24) & 0xFF < 0xFF else val


def ring_checks(pal: dict[str, int], is_light: bool) -> list[tuple[str, float, float]]:
    """
    焦点环要按「相邻对」验，不能按「halo vs 页面底」验。

    结构：页面底 → [accent 2dp] → [halo 2dp] → 海报。
    halo 本来就是压在海报上的，跟页面底低对比是设计意图。
    真正要保证的是：环形整体在任何亮度的海报上都不会"消失"。
    """
    accent = resolve(pal, "accent")
    bg = resolve(pal, "bg")
    halo_on_bg = flatten(pal["ringHalo"], bg)
    worst = 0x000000 if is_light else 0xFFFFFF
    mid = 0xFF808080
    return [
        ("accent / 页面底", ratio(accent, bg), MIN_GRAPHIC),
        ("accent / halo（相邻）", ratio(accent, halo_on_bg), MIN_GRAPHIC),
        ("halo / 最坏海报", ratio(flatten(pal["ringHalo"], worst), worst), MIN_HALO_WORST),
        ("halo / 中灰海报", ratio(flatten(pal["ringHalo"], mid), mid), MIN_HALO_MID),
    ]


def main() -> int:
    quiet = "--quiet" in sys.argv
    text = SRC.read_text(encoding="utf-8")
    palettes = {"深色": parse_palette(text, "DARK"), "浅色": parse_palette(text, "LIGHT")}

    failures: list[str] = []
    for label, pal in palettes.items():
        rows = []
        for fg, bg, minimum, note in TEXT_CHECKS:
            if fg not in pal or bg not in pal:
                failures.append(f"{label}: 缺少令牌 {fg} / {bg}")
                continue
            got = ratio(resolve(pal, fg), resolve(pal, bg))
            rows.append((fg, bg, got, minimum, note))
            if got < minimum:
                failures.append(f"{label}: {fg} on {bg} = {got:.2f} < {minimum} （{note}）")
        ring_rows = []
        for note, got, minimum in ring_checks(pal, label == "浅色"):
            ring_rows.append((note, got, minimum))
            if got < minimum:
                failures.append(f"{label}: {note} = {got:.2f} < {minimum}")

        if not quiet:
            print(f"\n{label}")
            print(f"  {'前景':<12}{'背景':<10}{'实测':>6}{'下限':>6}  说明")
            for fg, bg, got, minimum, note in rows:
                flag = "  " if got >= minimum else "✗ "
                print(f"  {flag}{fg:<11}{bg:<10}{got:>6.2f}{minimum:>6.1f}  {note}")
            print(f"  {'— 焦点环（相邻对）—':<28}")
            for note, got, minimum in ring_rows:
                flag = "  " if got >= minimum else "✗ "
                print(f"  {flag}{note:<26}{got:>6.2f}{minimum:>6.1f}")

    if failures:
        print("\n对比度不达标：")
        for f in failures:
            print("  -", f)
        return 1
    if not quiet:
        print("\n全部达标（主文字≥7 / 正文≥4.5 / 图形≥3.0 / 焦点环最坏情况≥2.5）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

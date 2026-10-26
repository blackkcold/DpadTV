#!/usr/bin/env python3
"""生成 Libre Pro 的图标候选（预览；选定后落成 adaptive icon 矢量）。

约束：电视桌面 16dp 仍要认得出 —— 一个主形 + 一个区分标记，不做细字细线。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

S = 4
W = 512
CYAN = (56, 189, 248)
TEAL = (34, 211, 238)
ORANGE = (245, 158, 11)
WHITE = (226, 232, 240)
INK = (11, 18, 32)
BG_TOP = (11, 18, 32)
BG_BOT = (22, 35, 58)
LUMA = Image.Resampling.LANCZOS


def canvas():
    img = Image.new("RGBA", (W * S, W * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i in range(W * S):
        t = i / (W * S)
        d.line([(0, i), (W * S, i)],
               fill=tuple(int(BG_TOP[k] + (BG_BOT[k] - BG_TOP[k]) * t) for k in range(3)) + (255,))
    mask = Image.new("L", (W * S, W * S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W * S - 1, W * S - 1], radius=116 * S, fill=255)
    img.putalpha(mask)
    return img


def rtriangle(img, cx, cy, size, color, radius=None):
    """圆角三角形：多边形 mask 模糊后阈值化 —— 只磨外角，斜边保持直。"""
    h, w = size, size * 0.88
    pts = [(cx - w * 0.44, cy - h / 2), (cx - w * 0.44, cy + h / 2), (cx + w * 0.5, cy)]
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    r = radius if radius is not None else size * 0.13
    mask = mask.filter(ImageFilter.GaussianBlur(r / 2.2)).point(lambda v: 255 if v > 110 else 0)
    img.paste(Image.new("RGBA", img.size, color), (0, 0), mask)


def badge(d, cx, cy, r):
    d.ellipse([cx - r - 5 * S, cy - r - 5 * S, cx + r + 5 * S, cy + r + 5 * S], fill=INK + (255,))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ORANGE + (255,))


def p1_single():
    img = canvas(); d = ImageDraw.Draw(img)
    rtriangle(img, 250 * S, 250 * S, 190 * S, CYAN + (255,))
    badge(d, 372 * S, 368 * S, 29 * S)
    return img


def p2_double():
    img = canvas(); d = ImageDraw.Draw(img)
    rtriangle(img, 206 * S, 250 * S, 168 * S, CYAN + (255,))
    rtriangle(img, 298 * S, 250 * S, 168 * S, TEAL + (255,))
    badge(d, 380 * S, 372 * S, 27 * S)
    return img


def p3_frame():
    img = canvas(); d = ImageDraw.Draw(img)
    d.rounded_rectangle([118 * S, 118 * S, 394 * S, 394 * S], radius=62 * S,
                        outline=(255, 255, 255, 70), width=10 * S)
    rtriangle(img, 256 * S, 252 * S, 160 * S, CYAN + (255,))
    badge(d, 348 * S, 348 * S, 26 * S)
    return img


def p4_grid():
    """片库宫格：2×2 圆角块，右下换成播放三角。"""
    img = canvas(); d = ImageDraw.Draw(img)
    cell, gap, r = 86 * S, 16 * S, 20 * S
    x0 = 256 * S - cell - gap // 2
    y0 = 256 * S - cell - gap // 2
    for gx, gy, col in [(0, 0, TEAL), (1, 0, WHITE), (0, 1, WHITE)]:
        x = x0 + gx * (cell + gap)
        y = y0 + gy * (cell + gap)
        d.rounded_rectangle([x, y, x + cell, y + cell], radius=r, fill=col + (255,))
    x = x0 + cell + gap
    y = y0 + cell + gap
    rtriangle(img, x + cell * 0.54, y + cell / 2, cell * 0.78, CYAN + (255,))
    badge(d, 386 * S, 126 * S, 24 * S)
    return img


def save(img, path):
    img.resize((W, W), LUMA).save(path)


def main():
    out = "docs/brand/icons"
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.startswith(("A-", "B-", "C-", "D-")):
            os.remove(os.path.join(out, f))
    items = [("P1-single", p1_single()), ("P2-double", p2_double()),
             ("P3-frame", p3_frame()), ("P4-grid", p4_grid())]
    for n, im in items:
        save(im, f"{out}/{n}.png")

    cell, pad, label_h = 320, 28, 46
    sheet = Image.new("RGB", (cell * 2 + pad * 3, (cell + label_h) * 2 + pad * 3), (8, 12, 20))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Avenir Next.ttc", 26)
    except Exception:
        font = ImageFont.load_default()
    labels = ["P1 · 单三角 + 定制点", "P2 · 双三角（Pro）", "P3 · 描边框 + 定制点", "P4 · 片库宫格"]
    for i, ((n, im), label) in enumerate(zip(items, labels)):
        cx = pad + (i % 2) * (cell + pad)
        cy = pad + (i // 2) * (cell + label_h + pad)
        small = im.resize((cell, cell), LUMA)
        sheet.paste(small, (cx, cy), small)
        d.text((cx, cy + cell + 10), label, fill=(203, 213, 225), font=font)
    sheet.save(f"{out}/00-candidates.png")
    print("ok")


if __name__ == "__main__":
    main()

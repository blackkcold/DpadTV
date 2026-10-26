#!/usr/bin/env python3
"""Libre Pro 图标终版探索：对齐的双三角 + 液态玻璃，四套配色。

对齐规则：两个三角同尺寸、同中心线；第二个的底边落在第一个的 58% 处，
视觉上像「快进」而不是两个错位的箭头。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

S = 3
W = 512
LUMA = Image.Resampling.LANCZOS
INK = (9, 14, 26)
ORANGE = (245, 158, 11)
YELLOW = (253, 224, 71)

PALETTES = {
    "A-aurora": ((56, 189, 248), (34, 211, 238), ORANGE),      # 极光青（延续应用主色）
    "B-mint": ((52, 211, 153), (34, 211, 238), ORANGE),        # 薄荷青绿
    "C-violet": ((139, 92, 246), (236, 72, 153), YELLOW),      # 电光紫（最区分原版）
    "D-ice": ((125, 211, 252), (226, 232, 240), ORANGE),       # 冰蓝银（最"玻璃"）
}


def base():
    w = W * S
    img = Image.new("RGB", (w, w), (8, 13, 24))
    px = img.load()
    for y in range(w):
        for x in range(0, w, 4):
            t = (x / w) * 0.35 + (y / w) * 0.65
            c = (int(8 + 18 * t), int(13 + 28 * t), int(24 + 46 * t))
            for dx in range(4):
                if x + dx < w:
                    px[x + dx, y] = c
    glow = Image.new("RGB", (w, w), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-w * 0.25, -w * 0.35, w * 0.72, w * 0.62], fill=(10, 90, 130))
    gd.ellipse([w * 0.45, w * 0.42, w * 1.25, w * 1.25], fill=(6, 60, 96))
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.14))
    img = Image.blend(img, Image.composite(glow, img, Image.new("L", (w, w), 90)), 0.55)
    return img.convert("RGBA")


def grad_mask_fill(img, mask, top, bottom, alpha):
    w, h = img.size
    g = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c + (255,))
    g.putalpha(alpha)
    img.alpha_composite(Image.composite(g, Image.new("RGBA", (w, h), (0, 0, 0, 0)), mask))


def tri_mask(size, cx, cy, length, radius_px):
    w, h = size
    tw, th = length * 0.94, length
    pts = [(cx - tw * 0.5, cy - th / 2), (cx - tw * 0.5, cy + th / 2), (cx + tw * 0.5, cy)]
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.filter(ImageFilter.GaussianBlur(radius_px / 2.2)).point(lambda v: 255 if v > 110 else 0)


def tri(img, cx, cy, length, top, bottom, alpha=205, shadow=44):
    w, h = img.size
    m = tri_mask((w, h), cx, cy, length, length * 0.12)

    sh = m.filter(ImageFilter.GaussianBlur(shadow * S * 0.22)).point(lambda v: int(v * 0.5))
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 200))
    tmp = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    tmp.paste(layer, (0, int(shadow * S * 0.30)), sh)
    img.alpha_composite(tmp)

    refr = img.filter(ImageFilter.GaussianBlur(5 * S))
    img.paste(refr, (0, 0), m)
    grad_mask_fill(img, m, top, bottom, alpha)

    inner = m.filter(ImageFilter.MinFilter(int(2.5 * S) * 2 + 1))
    from PIL import ImageChops
    ring = ImageChops.subtract(m, inner).filter(ImageFilter.GaussianBlur(S * 0.6))
    img.alpha_composite(Image.composite(Image.new("RGBA", (w, h), (255, 255, 255, 140)),
                                        Image.new("RGBA", (w, h), (0, 0, 0, 0)), ring))
    return img


def bead(img, cx, cy, r, color):
    w, h = img.size
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([cx - r * 2.1, cy - r * 2.1, cx + r * 2.1, cy + r * 2.1],
                                 fill=color + (48,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(r * 0.9)))
    ImageDraw.Draw(img).ellipse([cx - r, cy - r, cx + r, cy + r], fill=color + (238,))
    hl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse([cx - r * 0.55, cy - r * 0.62, cx + r * 0.12, cy - r * 0.02],
                               fill=(255, 255, 255, 175))
    img.alpha_composite(hl.filter(ImageFilter.GaussianBlur(r * 0.28)))


def round_corners(img):
    w, h = img.size
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius=int(w * 0.225), fill=255)
    img.putalpha(m)
    return img


def render(palette_key, framed=True, scale=1.0):
    """framed=True：桌面图标（圆角深底）；False：透明底，只留图形（自适应前景用）。"""
    top, bottom, accent = PALETTES[palette_key]
    img = base() if framed else Image.new("RGBA", (W * S, W * S), (0, 0, 0, 0))

    # 极淡玻璃盘（放大有材质，缩小退成背景）
    if framed:
        disc = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(disc).ellipse([96 * S, 96 * S, 416 * S, 416 * S], fill=(255, 255, 255, 12))
        img.alpha_composite(disc)
        rim = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(rim).ellipse([96 * S, 96 * S, 416 * S, 416 * S],
                                    outline=(255, 255, 255, 46), width=2 * S)
        img.alpha_composite(rim.filter(ImageFilter.GaussianBlur(S * 1.4)))

    # 对齐的双三角：同尺寸、同一水平中线；第二个的底边 = 第一个的 58% 处
    length = 188 * S
    width = length * 0.94
    cx1 = 256 * S - width * 0.30
    cx2 = cx1 + width * 0.58
    tri(img, cx1, 252 * S, length, top, bottom, 200)
    tri(img, cx2, 252 * S, length, top, bottom, 218)
    bead(img, 380 * S, 368 * S, 26 * S, accent)
    if scale != 1.0:
        w = int(img.size[0] * scale)
        small = img.resize((w, w), LUMA)
        canvas_img = Image.new("RGBA", img.size, (0, 0, 0, 0))
        off = (img.size[0] - w) // 2
        canvas_img.alpha_composite(small, (off, off))
        img = canvas_img
    return round_corners(img) if framed else img


def main():
    out = "docs/brand/icons-final"
    os.makedirs(out, exist_ok=True)
    items = [(k, render(k)) for k in PALETTES]
    for k, im in items:
        im.resize((W, W), LUMA).save(f"{out}/{k}.png")

    cell, pad, label_h, cols = 300, 26, 44, 4
    sheet = Image.new("RGB", (cell * cols + pad * (cols + 1), cell + label_h + pad * 2), (8, 11, 18))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Avenir Next.ttc", 22)
    except Exception:
        font = ImageFont.load_default()
    labels = {"A-aurora": "A · 极光青", "B-mint": "B · 薄荷", "C-violet": "C · 电光紫", "D-ice": "D · 冰蓝银"}
    for i, (k, im) in enumerate(items):
        x = pad + i * (cell + pad)
        small = im.resize((cell, cell), LUMA)
        sheet.paste(small, (x, pad), small)
        d.text((x, pad + cell + 8), labels[k], fill=(203, 213, 225), font=font)
    sheet.save(f"{out}/00-palettes.png")

    # 尺寸条
    sizes = [48, 72, 96, 144]
    strip = Image.new("RGB", (sum(sizes) + 40 * len(sizes) + 40, 200), (10, 14, 22))
    x = 20
    for sz in sizes:
        im = items[0][1].resize((sz, sz), LUMA)
        strip.paste(im, (x, 20 + (144 - sz) // 2), im)
        x += sz + 40
    strip.save(f"{out}/01-sizes.png")
    print("ok")


if __name__ == "__main__":
    main()

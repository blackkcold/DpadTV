#!/usr/bin/env python3
"""Libre Pro 图标 · 液态玻璃（Liquid Glass）方向探索。

做法：先铺一层有明暗起伏的底（不然玻璃没有东西可折射），
再叠「模糊后的底 + 半透明白 + 高光边 + 内侧阴影」，最后放双三角与定制点。
全部用 PIL 光栅实现（adaptive icon 的前景层也用这张 PNG）。
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math
import os

S = 3
W = 512
CYAN = (56, 189, 248)
TEAL = (34, 211, 238)
ORANGE = (245, 158, 11)
LUMA = Image.Resampling.LANCZOS


# ---------------------------------------------------------------- 底
def base():
    w = W * S
    img = Image.new("RGB", (w, w), (8, 13, 24))
    px = img.load()
    # 斜向渐变 + 两团柔光（给玻璃制造可折射的内容）
    for y in range(w):
        for x in range(0, w, 4):
            t = (x / w) * 0.35 + (y / w) * 0.65
            r = int(8 + 18 * t)
            g = int(13 + 28 * t)
            b = int(24 + 46 * t)
            for dx in range(4):
                if x + dx < w:
                    px[x + dx, y] = (r, g, b)
    glow = Image.new("RGB", (w, w), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-w * 0.25, -w * 0.35, w * 0.72, w * 0.62], fill=(10, 90, 130))
    gd.ellipse([w * 0.45, w * 0.42, w * 1.25, w * 1.25], fill=(6, 60, 96))
    glow = glow.filter(ImageFilter.GaussianBlur(w * 0.14))
    img = Image.blend(img, Image.composite(glow, img, Image.new("L", (w, w), 90)), 0.55)
    return img.convert("RGBA")


def rounded_mask(size, box, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle(box, radius=radius, fill=255)
    return m


def glass(img, box, radius, tint=(255, 255, 255, 26), blur=18, rim=110):
    """玻璃片：模糊底 + 半透明白 + 亮边 + 内侧暗边。"""
    w, h = img.size
    mask = rounded_mask((w, h), box, radius)

    # 1) 折射：把底模糊后按 mask 贴回（比直接盖白色更像玻璃）
    refr = img.filter(ImageFilter.GaussianBlur(blur * S)).crop((0, 0, w, h))
    img.paste(refr, (0, 0), mask)

    # 2) 玻璃本身的乳白
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=radius, fill=tint)
    img.alpha_composite(layer)

    # 3) 亮边（左上更亮）+ 内侧暗边
    edge = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ed = ImageDraw.Draw(edge)
    ed.rounded_rectangle(box, radius=radius, outline=(255, 255, 255, rim), width=max(1, S))
    edge = edge.filter(ImageFilter.GaussianBlur(S * 0.8))
    img.alpha_composite(edge)

    # 4) 顶部斜向高光（细带，不是一团灰）
    x0, y0, x1, y1 = box
    spec = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    band = [(x0, y0 + (y1 - y0) * 0.30), (x0 + (x1 - x0) * 0.42, y0),
            (x0 + (x1 - x0) * 0.62, y0), (x0, y0 + (y1 - y0) * 0.50)]
    ImageDraw.Draw(spec).polygon(band, fill=(255, 255, 255, 46))
    spec = spec.filter(ImageFilter.GaussianBlur(S * 7))
    img.alpha_composite(Image.composite(spec, Image.new("RGBA", (w, h), (0, 0, 0, 0)), mask))
    return img


def tri_mask(size, cx, cy, length, radius_px):
    h, w = size
    tw, th = length, length * 0.9
    pts = [(cx - tw * 0.44, cy - th / 2), (cx - tw * 0.44, cy + th / 2), (cx + tw * 0.5, cy)]
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).polygon(pts, fill=255)
    m = m.filter(ImageFilter.GaussianBlur(radius_px / 2.2)).point(lambda v: 255 if v > 110 else 0)
    return m


def gradient_for(size, top, bottom):
    w, h = size
    g = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c + (255,))
    return g


def inner_stroke(mask, px):
    eroded = mask.filter(ImageFilter.MinFilter(max(3, px * 2 + 1)))
    return Image.eval(Image.merge("L", [mask]).split()[0], lambda v: v).point(lambda v: v)  # placeholder


def glass_tri(img, cx, cy, length, tint, alpha=205, shadow=28):
    w, h = img.size
    m = tri_mask((w, h), cx, cy, length, length * 0.13)

    # 阴影（往下偏一点，玻璃才有厚度）
    sh = m.filter(ImageFilter.GaussianBlur(shadow * S * 0.35))
    sh = sh.point(lambda v: int(v * 0.55))
    sh_layer = Image.new("RGBA", (w, h), (0, 0, 0, 190))
    tmp = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    tmp.paste(sh_layer, (0, int(shadow * S * 0.45)), sh)
    img.alpha_composite(tmp)

    # 折射
    refr = img.filter(ImageFilter.GaussianBlur(6 * S))
    img.paste(refr, (0, 0), m)

    # 本体：竖向渐变（上亮下深），半透明
    top = tuple(min(255, int(c * 1.28)) for c in tint)
    bottom = tuple(int(c * 0.72) for c in tint)
    grad = gradient_for((w, h), top, bottom)
    grad.putalpha(alpha)
    img.alpha_composite(Image.composite(grad, Image.new("RGBA", (w, h), (0, 0, 0, 0)), m))

    # 内描边：只取内侧 2px，白 55%，再压一点模糊——模拟玻璃的折边高光
    inner = m.filter(ImageFilter.MinFilter(9))
    ring = Image.eval(m, lambda v: v)
    from PIL import ImageChops
    ring = ImageChops.subtract(m, inner)
    ring = ring.filter(ImageFilter.GaussianBlur(S * 0.6))
    hl = Image.new("RGBA", (w, h), (255, 255, 255, 150))
    img.alpha_composite(Image.composite(hl, Image.new("RGBA", (w, h), (0, 0, 0, 0)), ring))

    # 顶部高光：三角上半部一层薄薄的白
    sheen = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).rectangle([cx - length, cy - length, cx + length, cy - length * 0.18],
                                    fill=(255, 255, 255, 34))
    sheen = sheen.filter(ImageFilter.GaussianBlur(S * 5))
    img.alpha_composite(Image.composite(sheen, Image.new("RGBA", (w, h), (0, 0, 0, 0)), m))
    return img


def bead(img, cx, cy, r):
    w, h = img.size
    img.alpha_composite(Image.new("RGBA", (w, h), (0, 0, 0, 0)))
    d = ImageDraw.Draw(img)
    # 外发光
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([cx - r * 2.1, cy - r * 2.1, cx + r * 2.1, cy + r * 2.1],
                                 fill=ORANGE + (46,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(r * 0.9)))
    # 球体
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ORANGE + (235,))
    # 高光
    hl = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse(
        [cx - r * 0.55, cy - r * 0.62, cx + r * 0.12, cy - r * 0.05],
        fill=(255, 255, 255, 170))
    img.alpha_composite(hl.filter(ImageFilter.GaussianBlur(r * 0.28)))
    return img


def icon_round_corners(img):
    w, h = img.size
    mask = rounded_mask((w, h), [0, 0, w - 1, h - 1], int(w * 0.225))
    img.putalpha(mask)
    return img


# ---------------------------------------------------------------- 四个方向
def g1_panel():
    img = base()
    glass(img, [44 * S, 44 * S, 468 * S, 468 * S], 96 * S, tint=(255, 255, 255, 30))
    glass_tri(img, 208 * S, 250 * S, 168 * S, CYAN, 200)
    glass_tri(img, 300 * S, 250 * S, 168 * S, TEAL, 210)
    bead(img, 380 * S, 372 * S, 27 * S)
    return icon_round_corners(img)


def g2_orb():
    img = base()
    w = img.size[0]
    # 玻璃球
    orb = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(orb).ellipse([60 * S, 60 * S, 452 * S, 452 * S], fill=(255, 255, 255, 22))
    img.alpha_composite(orb)
    ring = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse([60 * S, 60 * S, 452 * S, 452 * S],
                                 outline=(255, 255, 255, 120), width=2 * S)
    img.alpha_composite(ring.filter(ImageFilter.GaussianBlur(S)))
    glass_tri(img, 206 * S, 250 * S, 156 * S, CYAN, 205)
    glass_tri(img, 292 * S, 250 * S, 156 * S, TEAL, 215)
    bead(img, 366 * S, 352 * S, 24 * S)
    return icon_round_corners(img)


def g3_floating():
    img = base()
    glass_tri(img, 200 * S, 246 * S, 180 * S, CYAN, 190, shadow=40)
    glass_tri(img, 296 * S, 258 * S, 180 * S, TEAL, 205, shadow=40)
    bead(img, 384 * S, 366 * S, 28 * S)
    return icon_round_corners(img)


def g4_sheen():
    img = base()
    glass(img, [40 * S, 40 * S, 472 * S, 472 * S], 104 * S, tint=(255, 255, 255, 24))
    # 斜向高光扫过
    sheen = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen)
    sd.polygon([(40 * S, 300 * S), (300 * S, 40 * S), (430 * S, 40 * S), (40 * S, 430 * S)],
               fill=(255, 255, 255, 26))
    img.alpha_composite(sheen.filter(ImageFilter.GaussianBlur(14 * S)))
    glass_tri(img, 208 * S, 250 * S, 164 * S, CYAN, 200)
    glass_tri(img, 298 * S, 250 * S, 164 * S, TEAL, 212)
    bead(img, 376 * S, 370 * S, 26 * S)
    return icon_round_corners(img)


def g2b_orb():
    img = base()
    d = ImageDraw.Draw(img)
    box = [58 * S, 58 * S, 454 * S, 454 * S]
    img.alpha_composite(Image.new("RGBA", img.size, (0, 0, 0, 0)))
    glass_img = Image.new("RGBA", img.size, (255, 255, 255, 0))
    ImageDraw.Draw(glass_img).ellipse(box, fill=(255, 255, 255, 20))
    img.alpha_composite(glass_img)
    ring = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse(box, outline=(255, 255, 255, 130), width=2 * S)
    img.alpha_composite(ring.filter(ImageFilter.GaussianBlur(S * 1.2)))
    glass_tri(img, 206 * S, 248 * S, 158 * S, CYAN, 208)
    glass_tri(img, 294 * S, 254 * S, 158 * S, TEAL, 218)
    bead(img, 366 * S, 350 * S, 24 * S)
    return icon_round_corners(img)


def g3b_floating():
    img = base()
    glass_tri(img, 198 * S, 244 * S, 182 * S, CYAN, 200, shadow=44)
    glass_tri(img, 298 * S, 260 * S, 182 * S, TEAL, 214, shadow=44)
    bead(img, 384 * S, 364 * S, 27 * S)
    return icon_round_corners(img)


def g3c_final():
    """终选方向：浮起双三角 + 极淡玻璃底盘（大图有层次，小图不糊）。"""
    img = base()
    # 淡玻璃盘：只在放大时提供"材质"，16dp 时几乎不可见，不会糊掉轮廓
    disc = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse([96 * S, 96 * S, 416 * S, 416 * S], fill=(255, 255, 255, 12))
    img.alpha_composite(disc)
    rim = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(rim).ellipse([96 * S, 96 * S, 416 * S, 416 * S],
                                outline=(255, 255, 255, 48), width=2 * S)
    img.alpha_composite(rim.filter(ImageFilter.GaussianBlur(S * 1.4)))
    glass_tri(img, 200 * S, 246 * S, 184 * S, CYAN, 202, shadow=46)
    glass_tri(img, 300 * S, 260 * S, 184 * S, TEAL, 216, shadow=46)
    bead(img, 386 * S, 366 * S, 26 * S)
    return icon_round_corners(img)


def main():
    out = "docs/brand/icons-glass"
    os.makedirs(out, exist_ok=True)
    items = [("G1-panel", g1_panel()), ("G2-orb", g2_orb()),
             ("G3-floating", g3_floating()), ("G4-sheen", g4_sheen()),
             ("G2b-orb-refined", g2b_orb()), ("G3b-floating-refined", g3b_floating()),
             ("G3c-final", g3c_final())]
    for n, im in items:
        im.resize((W, W), LUMA).save(f"{out}/{n}.png")

    cell, pad, label_h = 320, 28, 46
    rows = (len(items) + 1) // 2
    sheet = Image.new("RGB", (cell * 2 + pad * 3, (cell + label_h) * rows + pad * (rows + 1)), (6, 9, 16))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Avenir Next.ttc", 26)
    except Exception:
        font = ImageFont.load_default()
    labels = ["G1 · 浮起玻璃卡", "G2 · 玻璃球", "G3 · 层叠玻璃三角", "G4 · 磨砂高光",
              "G2b · 玻璃球（精修）", "G3b · 层叠三角（精修）", "G3c · 终选：双三角 + 淡玻璃盘"]
    for i, ((n, im), label) in enumerate(zip(items, labels)):
        cx = pad + (i % 2) * (cell + pad)
        cy = pad + (i // 2) * (cell + label_h + pad)
        small = im.resize((cell, cell), LUMA)
        sheet.paste(small, (cx, cy), small)
        d.text((cx, cy + cell + 10), label, fill=(203, 213, 225), font=font)
    sheet.save(f"{out}/00-candidates.png")

    # 小尺寸实测条：48 / 72 / 96 / 144
    pick = dict(items)
    sizes = [48, 72, 96, 144]
    strip = Image.new("RGB", (sum(sizes) + 40 * len(sizes) + 40, 200), (10, 14, 22))
    x = 20
    for sz in sizes:
        im = pick["G3c-final"].resize((sz, sz), LUMA)
        strip.paste(im, (x, 20 + (144 - sz) // 2), im)
        x += sz + 40
    strip.save(f"{out}/01-g3c-sizes.png")
    print("ok", out)


if __name__ == "__main__":
    main()

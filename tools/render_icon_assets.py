#!/usr/bin/env python3
"""把选定的图标（G3 · 电光紫）导出成 Android 需要的全部资源。

- 传统 mipmap：48/72/96/144/192（API 25 及以下走这些）
- 自适应图标前景：432×432（108dp@4x），图形缩到 66.6% 落在 72dp 安全区内
- 单色层：白剪影，供 Android 13 主题图标取色
- 背景：纯色 #0B1220（写在 colors.xml）
"""
from PIL import Image, ImageDraw
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icon_final as F

W = 512
OUT = "app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
LUMA = Image.Resampling.LANCZOS
VIOLET_A = (139, 92, 246)
VIOLET_B = (236, 72, 153)
YELLOW = (253, 224, 71)
INK = (11, 18, 32)


def art(size, scale=1.0, bg=True):
    """统一走 icon_final 的渲染器：预览什么样，安装出来就什么样。"""
    img = F.render("C-violet", framed=bg, scale=scale)
    return img.resize((size, size), F.LUMA)


def main():
    icon512 = art(512)
    icon512.save("docs/brand/icons-final/C-violet.png")

    for name, px in DENSITIES.items():
        d = os.path.join(OUT, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        icon512.resize((px, px), LUMA).save(os.path.join(d, "ic_launcher.png"))
        icon512.resize((px, px), LUMA).save(os.path.join(d, "ic_launcher_round.png"))

    # 自适应前景：108dp 画布，图形缩到 66.6%
    fg = art(432, scale=0.666, bg=False)
    os.makedirs(os.path.join(OUT, "drawable-nodpi"), exist_ok=True)
    fg.save(os.path.join(OUT, "drawable-nodpi", "ic_launcher_foreground.png"))

    # 单色层：白剪影（系统会自己上色）
    mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    src = fg.split()[3].point(lambda v: 255 if v > 120 else 0)
    mono.paste(Image.new("RGBA", (432, 432), (255, 255, 255, 255)), (0, 0), src)
    mono.save(os.path.join(OUT, "drawable-nodpi", "ic_launcher_monochrome.png"))
    print("assets ok", icon512.size)


if __name__ == "__main__":
    main()

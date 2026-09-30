#!/usr/bin/env python3
"""麻衣 BMS 图标生成器：由 tools/icon/source.jpg 生成各平台图标资源。

Android 从 API 26 起启动器不会原样显示图标：它把图按自己的形状（圆形 / 圆角方形 /
超椭圆）裁一刀，只保证中间 66dp 的圆形「安全区」完整可见。所以原画不能直接等比缩小
塞进去，得先按安全区重新构图——见下面的 CROP / INSET_DP / ZOOM 三个参数。

用法（在仓库根目录）：
    python tools/icon/generate_icons.py             # 生成正式资源
    python tools/icon/generate_icons.py --preview   # 只出预览图（.shots/icons/，不入库）

依赖：Pillow（numpy 仅用于预览图的背景平均色，可选）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

# ---------------------------------------------------------------- 路径

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = HERE / "source.jpg"
ANDROID_RES = ROOT / "composeApp" / "src" / "androidMain" / "res"
DESKTOP_RES = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
DOCS_IMAGES = ROOT / "docs" / "images"
PREVIEW_DIR = ROOT / ".shots" / "icons"

# ---------------------------------------------------------------- 构图

# 源图里要当成整张图标的那块正方形 (left, top, size)。全部 1024 就是原画不裁。
# 当前取值是在 --variants 对比图里挑出来的：圆形蒙版下脸部居中、右下角那块「M」饼干还在，
# 超椭圆蒙版下还能看见耳朵根与兔子发夹。
CROP = (54, 70, 940)
# 画面四周留边（单位：108dp 画布里的 dp）。0 = 画面直接铺满，靠裁切换构图；
# 留边会把画面缩小、四周露出背景层，适合想让耳朵/头发完整保住的构图。
INSET_DP = 0.0
# 额外缩放：<1 往里收（人物变小、留白多），>1 往外放。INSET_DP 不够用时可微调它。
ZOOM = 1.0

CANVAS_DP = 108.0  # 自适应图标画布固定 108dp

# 各密度档的像素尺寸
MIPMAP_SCALES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
LEGACY_DP = 48  # 传统 ic_launcher 的基准尺寸

BACKGROUND_SAFE = (108, 108, 108)  # 背景层兜底色（画面之外露出的部分）


# ---------------------------------------------------------------- 渲染

def load_source() -> Image.Image:
    return Image.open(SOURCE).convert("RGB")


def crop_square(src: Image.Image, crop: tuple[int, int, int]) -> Image.Image:
    left, top, size = crop
    left = max(0, min(left, src.width - size))
    top = max(0, min(top, src.height - size))
    box = (left, top, left + size, top + size)
    return src.crop(box)


def background_layer(src: Image.Image, size: int) -> Image.Image:
    """背景层：把原画压成 8x8 再放大，得到一张干净的同色调渐变。

    前景铺满时它是看不见的；但只要启动器做了视差（前景相对背景位移）或者
    给画面留了边，露出来的这块就得跟画面边缘顺色，不然会有明显接缝。
    """
    tone = src.resize((8, 8), Image.BOX).resize((size, size), Image.BICUBIC)
    return tone.filter(ImageFilter.GaussianBlur(size / 24)).convert("RGB")


def master_layers(src: Image.Image, px: int = 1024) -> tuple[Image.Image, Image.Image]:
    """按构图参数渲染出 (前景层, 背景层)，像素尺寸 px x px，即整张 108dp 画布。"""
    return render(src, px=px), background_layer(src, px)


def render(src: Image.Image, crop=None, inset: float | None = None, px: int = 1024) -> Image.Image:
    """按给定裁剪窗口与留边渲染整张 108dp 画布。"""
    crop = CROP if crop is None else crop
    inset = INSET_DP if inset is None else inset

    art_dp = (CANVAS_DP - 2 * inset) * ZOOM
    art_px = max(1, round(px * art_dp / CANVAS_DP))
    art = crop_square(src, crop).resize((art_px, art_px), Image.LANCZOS)

    if inset <= 0 and art_px >= px:
        return art.crop(((art_px - px) // 2, (art_px - px) // 2, (art_px - px) // 2 + px,
                         (art_px - px) // 2 + px))

    canvas = Image.new("RGB", (px, px), BACKGROUND_SAFE)
    canvas.paste(background_layer(src, px), (0, 0))
    offset = (px - art_px) // 2
    canvas.paste(art, (offset, offset))
    return canvas


# ---------------------------------------------------------------- 蒙版（预览用）

def squircle_mask(px: int, box_ratio: float, exponent: float = 4.0) -> Image.Image:
    """超椭圆蒙版，近似 Pixel / MIUI 的圆角方形。box_ratio = 蒙版边长 / 画布边长。"""
    m = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(m)
    half = px * box_ratio / 2
    c = px / 2
    step = max(1, px // 512)
    for y in range(0, px, step):
        dy = abs(y + step / 2 - c) / half
        if dy >= 1:
            continue
        # |x|^n + |y|^n = 1 解出该行的 |x| 上限
        xr = half * (max(0.0, 1 - dy ** exponent)) ** (1 / exponent)
        d.rectangle([c - xr, y, c + xr, y + step - 1], fill=255)
    return m


def circle_mask(px: int, box_ratio: float) -> Image.Image:
    m = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(m)
    o = px * (1 - box_ratio) / 2
    d.ellipse([o, o, px - o, px - o], fill=255)
    return m


def roundrect_mask(px: int, box_ratio: float, radius_ratio: float = 0.18) -> Image.Image:
    m = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(m)
    o = px * (1 - box_ratio) / 2
    r = px * box_ratio * radius_ratio
    d.rounded_rectangle([o, o, px - o, px - o], radius=r, fill=255)
    return m


def apply_mask(img: Image.Image, mask: Image.Image) -> Image.Image:
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def flat(img: Image.Image, color=(0x2A, 0x2A, 0x2E)) -> Image.Image:
    """把带透明通道的图标压到纯色底上，方便肉眼检查裁切。"""
    bg = Image.new("RGBA", img.size, color + (255,))
    bg.alpha_composite(img.convert("RGBA"))
    return bg.convert("RGB")


# ---------------------------------------------------------------- 输出

def dump(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print(f"  {path.relative_to(ROOT)}  {img.width}x{img.height} {img.mode}")


def write_android(src: Image.Image) -> None:
    fg, bg = master_layers(src)
    legacy = fg

    for bucket, scale in MIPMAP_SCALES.items():
        px = round(CANVAS_DP * scale)
        if px != fg.width:
            fg_i = fg.resize((px, px), Image.LANCZOS)
            bg_i = bg.resize((px, px), Image.LANCZOS)
        else:
            fg_i, bg_i = fg, bg
        dump(fg_i.convert("RGB"), ANDROID_RES / f"mipmap-{bucket}" / "ic_launcher_foreground.png")
        dump(bg_i.convert("RGB"), ANDROID_RES / f"mipmap-{bucket}" / "ic_launcher_background.png")

        lp = round(LEGACY_DP * scale)
        square = legacy.resize((lp, lp), Image.LANCZOS)
        dump(square.convert("RGB"), ANDROID_RES / f"mipmap-{bucket}" / "ic_launcher.png")
        dump(apply_mask(square, circle_mask(lp, 1.0)),
             ANDROID_RES / f"mipmap-{bucket}" / "ic_launcher_round.png")

    anydpi = ANDROID_RES / "mipmap-anydpi-v26"
    # 这里刻意不给 <monochrome>：主题图标（Android 13+ 跟随壁纸取色）要求一层单色剪影，
    # 而这张画的人物几乎铺满整幅，抠出来的剪影就是一块实心方块，不如不给——
    # 没有 monochrome 时系统会退回用普通自适应图标，不会有副作用。
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标：启动器按自己的形状裁切，画面已按 66dp 安全区重新构图 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = anydpi / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(adaptive, encoding="utf-8")
        print(f"  {p.relative_to(ROOT)}")


def write_desktop(src: Image.Image) -> None:
    art = master_layers(src)[0].resize((512, 512), Image.LANCZOS)
    dump(art, DESKTOP_RES / "app_icon.png")  # 窗口左上角图标（Main.kt 里读它）

    # jpackage 打包 Windows 用。不带 macOS 的 .icns：Pillow 写 icns 一定会塞进 1024px 位图
    # （1MB+），而这个项目只在 Android / Windows 桌面上跑，没必要为此在仓库里背个大文件。
    ico = DESKTOP_RES / "app.ico"
    ico_sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    art.save(ico, format="ICO", sizes=ico_sizes, bitmap_format="png")
    print(f"  {ico.relative_to(ROOT)}  ico {len(ico_sizes)} 档")


def write_store(src: Image.Image) -> None:
    art = master_layers(src)[0].resize((512, 512), Image.LANCZOS)
    dump(art, DOCS_IMAGES / "app-icon-512.png")


def write_preview(src: Image.Image, crop=None, inset: float | None = None) -> None:
    """渲染一张检查图：同一构图在常见启动器形状和小尺寸下的样子。"""
    art = render(src, crop, inset).resize((512, 512), Image.LANCZOS)
    tile = 300
    gap = 18
    sheet = Image.new("RGB", (tile * 4 + gap * 5, tile * 2 + gap * 3 + 30), (0x1B, 0x1B, 0x1E))

    masks = [
        ("无蒙版 (squircle 撑满)", None),
        ("圆形 circle", circle_mask(tile, 72 / 108)),
        ("超椭圆 n=4 (Pixel/MIUI)", squircle_mask(tile, 76 / 108)),
        ("圆角方形 r=18%", roundrect_mask(tile, 76 / 108)),
    ]
    big = art.resize((tile, tile), Image.LANCZOS)
    for i, (label, mask) in enumerate(masks):
        img = big if mask is None else apply_mask(big, mask)
        sheet.paste(flat(img), (gap + i * (tile + gap), 26))
        ImageDraw.Draw(sheet).text((gap + i * (tile + gap) + 4, 8), label, fill=(230, 230, 230))

    small_labels = ["48dp @1x", "48dp @2x", "48dp @3x", "48dp @4x"]
    for i, (label, size) in enumerate(zip(small_labels, (48, 96, 144, 192))):
        s = art.resize((size, size), Image.LANCZOS)
        cell = Image.new("RGB", (tile, tile), (0x1B, 0x1B, 0x1E))
        cell.paste(flat(apply_mask(s, squircle_mask(size, 76 / 108)), (0x33, 0x33, 0x38)),
                   ((tile - size) // 2, (tile - size) // 2))
        sheet.paste(cell, (gap + i * (tile + gap), 26 + tile + gap))
        ImageDraw.Draw(sheet).text((gap + i * (tile + gap) + 4, 26 + tile + gap - 14),
                                   f"{label} = {size}px", fill=(230, 230, 230))

    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    out = PREVIEW_DIR / "preview.png"
    sheet.save(out)
    print(f"预览：{out.relative_to(ROOT)}")


def write_variants(src: Image.Image) -> None:
    """把几个候选构图并排渲染，用来挑安全区里的取景。"""
    variants = [
        ("A 现状 crop(40,20,964)", (40, 20, 964), 0.0),
        ("B 上移 crop(40,60,964)", (40, 60, 964), 0.0),
        ("C 推近 crop(130,110,880)", (130, 110, 880), 0.0),
        ("D 留边6dp crop(0,0,1024)", (0, 0, 1024), 6.0),
        ("E 留边10dp crop(0,0,1024)", (0, 0, 1024), 10.0),
    ]
    cell = 260
    gap = 14
    cols = 4
    sheet = Image.new("RGB", (cols * cell + (cols + 1) * gap,
                              len(variants) * (cell + 34) + gap), (0x1B, 0x1B, 0x1E))
    d = ImageDraw.Draw(sheet)
    heads = ["圆形 72dp", "超椭圆 76dp", "正方形 (108dp)", "48dp @4x"]
    for c, h in enumerate(heads):
        d.text((gap + c * (cell + gap) + 4, 4), h, fill=(235, 235, 235))

    for r, (label, crop, inset) in enumerate(variants):
        art = render(src, crop, inset).resize((cell, cell), Image.LANCZOS)
        y = 24 + r * (cell + 34)
        d.text((6, y + cell + 4), label, fill=(255, 214, 120))
        views = [
            apply_mask(art, circle_mask(cell, 72 / 108)),
            apply_mask(art, squircle_mask(cell, 76 / 108)),
            art,
            apply_mask(art.resize((192, 192), Image.LANCZOS), squircle_mask(192, 76 / 108)),
        ]
        for c, v in enumerate(views):
            box = Image.new("RGB", (cell, cell), (0x1B, 0x1B, 0x1E))
            if v.width != cell:
                box.paste(flat(v, (0x33, 0x33, 0x38)), ((cell - v.width) // 2, (cell - v.width) // 2))
            else:
                box.paste(flat(v), (0, 0))
            sheet.paste(box, (gap + c * (cell + gap), y))

    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    out = PREVIEW_DIR / "variants.png"
    sheet.save(out)
    print(f"变体对比：{out.relative_to(ROOT)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--preview", action="store_true", help="只渲染预览图，不写资源")
    ap.add_argument("--variants", action="store_true", help="渲染候选构图对比图")
    ap.add_argument("--crop", help="临时覆盖裁剪窗口，写法 left,top,size")
    ap.add_argument("--inset", type=float, help="临时覆盖四周留边（dp）")
    args = ap.parse_args()

    if not SOURCE.exists():
        print(f"缺少源图：{SOURCE}", file=sys.stderr)
        return 1
    src = load_source()
    print(f"源图 {SOURCE.relative_to(ROOT)} {src.width}x{src.height}")

    if args.variants:
        write_variants(src)
        return 0

    crop, inset = CROP, INSET_DP
    if args.crop:
        crop = tuple(int(x) for x in args.crop.split(","))  # type: ignore[assignment]
    if args.inset is not None:
        inset = args.inset
    print(f"构图：crop={crop} inset={inset}dp zoom={ZOOM}")

    if args.preview:
        write_preview(src, crop, inset)
        return 0

    write_android(src)
    write_store(src)
    write_desktop(src)
    write_preview(src)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

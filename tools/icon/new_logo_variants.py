#!/usr/bin/env python3
"""针对真实启动器裁切（72dp 可见区）重新对比构图：全图缩小 vs 推近。"""
from PIL import Image, ImageDraw
import sys
sys.path.insert(0, r"E:\AI 项目\ant_bms_open\tools\icon")
import generate_icons as g

SRC = Image.open(r"E:\AI 项目\ant_bms_open\tools\icon\source.jpg").convert("RGB")
N = SRC.width

variants = [
    ("F 推近+微留边", (62, 15, 1150), 6.0),
    ("G 全图+留边9dp", (0, 0, N), 9.0),
    ("H 现状+留边6dp", (88, 37, 1090), 6.0),
    ("I 全图+留边12dp", (0, 0, N), 12.0),
]

cell = 300
gap = 14
heads = ["圆形 72dp", "方圆 72dp(近MIUI)", "正方形 108dp", "48px"]
sheet = Image.new("RGB", (len(heads)*cell + (len(heads)+1)*gap,
                          len(variants)*(cell+34)+gap), (0x1B, 0x1B, 0x1E))
d = ImageDraw.Draw(sheet)
for c, h in enumerate(heads):
    d.text((gap + c*(cell+gap)+4, 4), h, fill=(235, 235, 235))

for r, (label, crop, inset) in enumerate(variants):
    art = g.render(SRC, crop=crop, inset=inset).resize((cell, cell), Image.LANCZOS)
    y = 24 + r*(cell+34)
    d.text((6, y+cell+4), f"{label}  crop={crop} inset={inset}", fill=(255, 214, 120))
    views = [
        g.apply_mask(art, g.circle_mask(cell, 72/108)),
        g.apply_mask(art, g.squircle_mask(cell, 72/108)),
        art,
        g.apply_mask(art.resize((48, 48), Image.LANCZOS), g.squircle_mask(48, 72/108)),
    ]
    for c, v in enumerate(views):
        sheet.paste(g.flat(v), (gap + c*(cell+gap), y))

out = r"E:\AI 项目\ant_bms_open\.shots\icons\new_logo_variants.png"
sheet.save(out)
print("saved", out)

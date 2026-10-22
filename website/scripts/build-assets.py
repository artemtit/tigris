#!/usr/bin/env python3
"""Generate the web image set for the Tigris website.

Source of truth: ../../docs/screenshots/*.jpg (real captures, 1280x720).
Outputs:         public/img/{name}-1280.webp, {name}-640.webp and og.jpg

Run from anywhere:  python scripts/build-assets.py

Font requirements
-----------------
The Open Graph card needs a CJK-capable font. The script does NOT ship a font
and does not hard-code one machine's paths; it searches a list of common
locations per platform and any file named by $TIGRIS_OG_FONT. If none is
found it exits with a clear message instead of writing a broken card. Any
font you already have installed is used only at render time, never copied into
the repository, so no font is redistributed.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT.parent / "docs" / "screenshots"
OUT = ROOT / "public" / "img"
OUT.mkdir(parents=True, exist_ok=True)

# Regular and bold candidates, in preference order. Any font that covers Han
# characters (and Latin, for "TIGRIS") works; the look is unchanged because the
# card only uses regular and bold weights of the same family.
FONT_CANDIDATES = [
    # Windows
    ("C:/Windows/Fonts/msyhbd.ttc", "C:/Windows/Fonts/msyh.ttc"),
    ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyh.ttc"),
    # macOS
    ("/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/PingFang.ttc"),
    ("/System/Library/Fonts/STHeiti Medium.ttc", "/System/Library/Fonts/STHeiti Light.ttc"),
    # Linux (Noto / Source Han / WenQuanYi)
    ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    ("/usr/share/fonts/noto-cjk/NotoSansCJK-Bold.ttc", "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"),
    ("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"),
]


def resolve_fonts() -> tuple[str, str]:
    override = os.environ.get("TIGRIS_OG_FONT")
    if override:
        path = Path(override)
        if not path.exists():
            sys.exit(f"TIGRIS_OG_FONT points at a missing file: {path}")
        return str(path), str(path)
    for bold, regular in FONT_CANDIDATES:
        if Path(bold).exists() and Path(regular).exists():
            print(f"og font: {regular}  (bold: {bold})")
            return bold, regular
    sys.exit(
        "No CJK font found for the Open Graph card.\n"
        "Install Noto Sans CJK, PingFang or Microsoft YaHei, or point\n"
        "TIGRIS_OG_FONT at any CJK .ttf/.ttc/.otf file and re-run.\n"
        "The screenshots themselves do not need a font."
    )


def build_screenshots() -> None:
    for name in ["home", "shelf", "grid", "cc", "frame"]:
        src = SRC / f"{name}.jpg"
        if not src.exists():
            sys.exit(f"missing screenshot: {src}")
        im = Image.open(src).convert("RGB")
        if im.size != (1280, 720):
            sys.exit(f"unexpected size for {src}: {im.size}")
        im.save(OUT / f"{name}-1280.webp", "WEBP", quality=84, method=6)
        im.resize((640, 360), Image.LANCZOS).save(
            OUT / f"{name}-640.webp", "WEBP", quality=80, method=6
        )


def build_og() -> None:
    bold_path, regular_path = resolve_fonts()
    w, h = 1200, 630
    card = Image.new("RGB", (w, h), "#070A10")

    glow = Image.new("RGB", (w, h), "#070A10")
    gd = ImageDraw.Draw(glow)
    gd.ellipse((560, -220, 1420, 520), fill="#123A5A")
    gd.ellipse((-260, 300, 520, 900), fill="#2A2450")
    glow = glow.filter(ImageFilter.GaussianBlur(130))
    card = Image.blend(card, glow, 0.85)

    d = ImageDraw.Draw(card)
    font_bold = ImageFont.truetype(bold_path, 76)
    font_mid = ImageFont.truetype(regular_path, 28)
    font_small = ImageFont.truetype(regular_path, 22)
    font_brand = ImageFont.truetype(bold_path, 30)

    d.rounded_rectangle((72, 62, 112, 102), radius=12, fill="#B9D4FF")
    d.text((72, 122), "TIGRIS", font=font_brand, fill="#B9D4FF")
    d.text((72, 170), "让电视桌面，", font=font_bold, fill="#F5F7FB")
    d.text((72, 258), "重新惊艳。", font=font_bold, fill="#F5F7FB")
    d.multiline_text(
        (76, 372),
        "液态玻璃 Dock · 顶部航拍屏保\nAndroid TV 9+ · 无需 root · 开源",
        font=font_mid,
        fill="#A9B3C2",
        spacing=12,
    )

    tv = Image.open(SRC / "home.jpg").convert("RGB").resize((620, 349), Image.LANCZOS)
    bezel = Image.new("RGB", (652, 381), "#050709")
    bezel.paste(tv, (16, 16))
    mask = Image.new("L", bezel.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 651, 380), radius=18, fill=255)
    card.paste(bezel, (516, 130), mask)
    d.rounded_rectangle((516, 130, 1167, 510), radius=18, outline="#3A4657", width=2)
    d.text((76, 540), "github.com/lesnolie/Tigris", font=font_small, fill="#8C98A8")

    card.save(OUT / "og.jpg", "JPEG", quality=88, optimize=True)


if __name__ == "__main__":
    build_screenshots()
    build_og()
    print("wrote", OUT)
    for f in sorted(OUT.iterdir()):
        print(f"  {f.name:26s} {f.stat().st_size / 1024:7.1f} KB")

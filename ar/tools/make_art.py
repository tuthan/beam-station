"""Draws Station AR's icon and TV banner (Pillow) into ar/app/src/main/res/drawable-nodpi, with the home screen's
drawing helpers (tools/make_art.py at the top of the repository).
Usage: python3 ar/tools/make_art.py"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "tools"))
import make_art  # noqa: E402  (the home screen's helpers: gradient, glow, mark, banner)


def workspace(s):
    """Station AR's mark as a mask: three screens on an arc, floating over the glasses."""
    m = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(m)
    w = int(s * 0.045)
    # the side screens, turned away (narrower, lower), then the middle one
    d.polygon([(s * 0.06, s * 0.20), (s * 0.27, s * 0.165), (s * 0.27, s * 0.455), (s * 0.06, s * 0.42)], outline=255, width=w)
    d.polygon([(s * 0.94, s * 0.20), (s * 0.73, s * 0.165), (s * 0.73, s * 0.455), (s * 0.94, s * 0.42)], outline=255, width=w)
    d.rounded_rectangle((s * 0.31, s * 0.12, s * 0.69, s * 0.48), radius=int(s * 0.05), outline=255, width=w)
    make_art.sparkle(d, s * 0.5, s * 0.30, s * 0.085)
    # glasses: tinted lenses under one brow, as Station Home's mark
    for b in ((s * 0.14, s * 0.60, s * 0.475, s * 0.82), (s * 0.525, s * 0.60, s * 0.86, s * 0.82)):
        d.rounded_rectangle(b, radius=int(s * 0.09), fill=96)
        d.rounded_rectangle(b, radius=int(s * 0.09), outline=255, width=int(s * 0.045))
    d.rounded_rectangle((s * 0.14, s * 0.60, s * 0.86, s * 0.645), radius=int(s * 0.0225), fill=255)
    return m


DEV = {
    "name": "Station AR",
    "stops": [(0.0, (110, 231, 183)), (0.5, (20, 184, 166)), (1.0, (30, 64, 175))],   # mint -> teal -> deep blue
    "glyph": workspace,
}

if __name__ == "__main__":
    res = os.path.join(os.path.dirname(HERE), "app/src/main/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    make_art.mark(DEV, 192).save(os.path.join(res, "icon.png"))
    make_art.banner(DEV).save(os.path.join(res, "banner.png"))
    print("ok")

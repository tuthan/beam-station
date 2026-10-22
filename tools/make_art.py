"""Draws the app icon, the TV banner and the settings symbol (Pillow). Usage: python3 tools/make_art.py"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
NAME = "Beam Home"
STOPS = [(0.0, (255, 196, 102)), (0.5, (242, 99, 94)), (1.0, (102, 44, 145))]   # amber light -> coral -> plum


def gradient(w, h, angle=35):
    """Diagonal three-color gradient."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, w * dx, h * dy, w * dx + h * dy)
    hi = max(0, w * dx, h * dy, w * dx + h * dy)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                    break
    return img


def glow(w, h, cx, cy, r, alpha):
    """A soft white light (a blurred disc)."""
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((cx - r, cy - r, cx + r, cy + r), fill=round(255 * alpha))
    out = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    out.putalpha(mask.filter(ImageFilter.GaussianBlur(r * 0.6)))
    return out


def sparkle(draw, cx, cy, r, fill=255):
    """Four-point star."""
    pts = []
    for i in range(8):
        ang = math.pi / 2 * (i // 2) + (0 if i % 2 == 0 else math.pi / 4)
        rad = r if i % 2 == 0 else r * 0.26
        pts.append((cx + rad * math.cos(ang - math.pi / 2), cy + rad * math.sin(ang - math.pi / 2)))
    draw.polygon(pts, fill=fill)


def fade(mask, top, bottom, a_top, a_bottom):
    """The mask, its opacity going from a_top (at y = top) to a_bottom (at y = bottom)."""
    s = mask.width
    ramp = Image.new("L", (1, s))
    for y in range(s):
        f = min(1, max(0, (y - top) / max(1, bottom - top)))
        ramp.putpixel((0, y), round(255 * (a_top + (a_bottom - a_top) * f)))
    return Image.composite(mask, Image.new("L", (s, s), 0), ramp.resize((s, s)))


def glyph(s):
    """The mark as a mask: a projector throwing its beam onto a screen with a sparkle."""
    m = Image.new("L", (s, s), 0)
    beam = Image.new("L", (s, s), 0)
    ImageDraw.Draw(beam).polygon([(s * 0.465, s * 0.70), (s * 0.535, s * 0.70), (s * 0.76, s * 0.545), (s * 0.24, s * 0.545)], fill=255)
    m.paste(fade(beam, s * 0.545, s * 0.70, 0.22, 0.62), (0, 0))
    d = ImageDraw.Draw(m)
    # screen
    d.rounded_rectangle((s * 0.18, s * 0.16, s * 0.82, s * 0.53), radius=int(s * 0.065), outline=255, width=int(s * 0.052))
    sparkle(d, s * 0.5, s * 0.345, s * 0.105)
    sparkle(d, s * 0.655, s * 0.25, s * 0.04)
    # projector, its lens cut out
    d.rounded_rectangle((s * 0.29, s * 0.69, s * 0.71, s * 0.835), radius=int(s * 0.045), fill=255)
    lx, ly, lr, dot = s * 0.5, s * 0.7625, s * 0.043, s * 0.016
    d.ellipse((lx - lr, ly - lr, lx + lr, ly + lr), fill=0)
    d.ellipse((lx - dot, ly - dot, lx + dot, ly + dot), fill=255)
    return m


def white(alpha):
    img = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    img.putalpha(alpha)
    return img


def mark(size, bg=True):
    """The mark on the gradient squircle (bg) or alone (white)."""
    s = size * 4
    alpha = glyph(s)
    if not bg:
        return white(alpha).resize((size, size), Image.LANCZOS)
    img = gradient(s, s).convert("RGBA")
    img.alpha_composite(glow(s, s, s * 0.22, s * 0.12, s * 0.55, 0.28))
    shadow = Image.new("RGBA", (s, s), STOPS[-1][1] + (0,))
    shadow.putalpha(alpha.filter(ImageFilter.GaussianBlur(s * 0.022)).point(lambda v: int(v * 0.35)))
    img.alpha_composite(shadow, (0, int(s * 0.016)))
    img.alpha_composite(white(alpha))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def font(px, weight=600):
    f = ImageFont.truetype(FONT, px)
    try:
        axes = f.get_variation_axes()
        values = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def banner(w=640, h=360):
    s = 4
    W, H = w * s, h * s
    img = gradient(W, H, angle=20).convert("RGBA")
    img.alpha_composite(glow(W, H, W * 0.18, H * 0.05, H * 0.9, 0.22))
    m = mark(int(h * 0.52 * s), bg=False)
    d = ImageDraw.Draw(img)
    f = font(int(h * 0.15 * s), 650)
    box = d.textbbox((0, 0), NAME, font=f)
    gap = int(w * 0.015 * s)
    x = (W - (m.width + gap + box[2] - box[0])) // 2
    my = (H - m.height) // 2
    tx, ty = x + m.width + gap - box[0], (H - (box[3] - box[1])) // 2 - box[1]
    # one soft shadow under the mark and the name
    sh = Image.new("L", (W, H), 0)
    sh.paste(m.getchannel("A"), (x, my))
    ImageDraw.Draw(sh).text((tx, ty), NAME, font=f, fill=255)
    shadow = Image.new("RGBA", (W, H), STOPS[-1][1] + (0,))
    shadow.putalpha(sh.filter(ImageFilter.GaussianBlur(H * 0.02)).point(lambda v: int(v * 0.3)))
    img.alpha_composite(shadow, (0, int(H * 0.01)))
    img.alpha_composite(m, (x, my))
    d.text((tx, ty), NAME, font=f, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS)


def symbol(size=240):
    """The mark alone, cropped, for Symbols (the settings page and the settings tile on the shelf)."""
    m = mark(size, bg=False)
    return m.crop(m.getchannel("A").getbbox())


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    banner().save(os.path.join(res, "banner.png"))
    symbol().save(os.path.join(ROOT, "app/assets/symbols/mark.png"))
    print("ok")

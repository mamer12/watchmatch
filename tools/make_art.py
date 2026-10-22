#!/usr/bin/env python3
"""Legacy "sunrise" brand art: app icon, TV banner and the website icons.

Run from anywhere: python3 tools/make_art.py   (needs Pillow). Prints "ok".
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app", "assets", "fonts", "InterVariable.ttf")
STOPS = [(0.0, (255, 194, 122)), (0.5, (255, 122, 158)), (1.0, (154, 140, 255))]
SS = 4  # supersampling for the mark


def ramp(t):
    t = min(1.0, max(0.0, t))
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
    return STOPS[-1][1]


def gradient(w, h, angle):
    """Diagonal 3-stop gradient; t is the pixel's projection on the direction, over the corner extent."""
    dx, dy = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    extent = abs(w * dx) + abs(h * dy)
    lut = [ramp(i / 1023) for i in range(1024)]
    px = []
    for y in range(h):
        for x in range(w):
            t = 0.5 + ((x + 0.5 - w / 2) * dx + (y + 0.5 - h / 2) * dy) / extent
            px.append(lut[min(1023, max(0, int(t * 1023 + 0.5)))])
    img = Image.new("RGB", (w, h))
    img.putdata(px)
    return img.convert("RGBA")


def sparkle(draw, cx, cy, r, fill):
    pts = []
    for i in range(8):
        rad = r if i % 2 == 0 else r * 0.26
        a = math.radians(-90 + i * 45)
        pts.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    draw.polygon(pts, fill=fill)


def foreground_mask(s):
    """White TV outline, stand and two sparkles as an 'L' mask, s px square."""
    m = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([s * .20, s * .27, s * .80, s * .66], radius=s * .07, outline=255,
                        width=max(1, round(s * .055)))
    d.rounded_rectangle([s * .36, s * .715, s * .64, s * .765], radius=s * .025, fill=255)
    sparkle(d, s * .50, s * .465, s * .12, 255)
    sparkle(d, s * .655, s * .355, s * .045, 255)
    return m


def mark(size, bg):
    big = size * SS
    fg = foreground_mask(big)
    if not bg:
        out = Image.new("RGBA", (big, big), (255, 255, 255, 0))
        out.putalpha(fg)
        return out.resize((size, size), Image.LANCZOS)
    base = gradient(size, size, 35).resize((big, big), Image.BILINEAR)
    box = Image.new("L", (big, big), 0)
    ImageDraw.Draw(box).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * .23, fill=255)
    # soft shadow under the foreground
    sh = Image.new("L", (big, big), 0)
    sh.paste(fg, (0, round(big * .012)))
    sh = sh.filter(ImageFilter.GaussianBlur(big * .02)).point(lambda v: v * 0.22)
    base.paste(Image.new("RGBA", (big, big), (120, 40, 80, 255)), (0, 0), sh)
    base.paste(Image.new("RGBA", (big, big), (255, 255, 255, 255)), (0, 0), fg)
    base.putalpha(box)
    return base.resize((size, size), Image.LANCZOS)


def load_font(px):
    try:
        f = ImageFont.truetype(FONT, px)
        axes = f.get_variation_axes()
        vals = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            vals.append(650 if "eight" in name else 32 if "ptical" in name else a["default"])
        f.set_variation_by_axes(vals)
        return f
    except Exception:
        return ImageFont.load_default()


def banner(w=640, h=360):
    img = gradient(w, h, 20)
    m = mark(round(h * .5), False)
    font = load_font(round(h * .15))
    d = ImageDraw.Draw(img)
    text = "WatchMatch"
    tb = d.textbbox((0, 0), text, font=font)
    tw, th = tb[2] - tb[0], tb[3] - tb[1]
    gap = round(w * .02)
    x0 = (w - (m.width + gap + tw)) // 2
    img.alpha_composite(m, (x0, (h - m.height) // 2))
    d.text((x0 + m.width + gap - tb[0], (h - th) // 2 - tb[1]), text, font=font, fill=(255, 255, 255, 255))
    return img


def save(img, rel):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def main():
    save(mark(192, True), "app/res/drawable-nodpi/icon.png")
    save(banner(), "app/res/drawable-nodpi/banner.png")
    save(mark(512, True), "docs/assets/icon-512.png")
    save(mark(180, True), "docs/assets/apple-touch-icon.png")
    save(mark(64, True), "docs/assets/favicon-64.png")
    print("ok")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Render the white-on-transparent icons in app/assets/symbols from the Material Symbols Rounded variable font.

Usage: python3 -I tools/make_symbols.py FONT.ttf CODEPOINTS OUT_DIR [EM_PX]
  FONT.ttf    MaterialSymbolsRounded[FILL,GRAD,opsz,wght].ttf (Google Fonts / the material-symbols repo; not bundled)
  CODEPOINTS  text file, one "name hexcode" per line (e.g. "sunny f1c2" from Google's .codepoints file)
  OUT_DIR     where NAME.png files go
  EM_PX       size of one em in the output, default 172
Needs Pillow.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# app icon name -> (Material symbol name, filled)
ICONS = {
    "sunny": ("sunny", 1), "clear_night": ("clear_night", 1), "partly_cloudy_day": ("partly_cloudy_day", 1),
    "partly_cloudy_night": ("partly_cloudy_night", 1), "cloud": ("cloud", 1), "foggy": ("foggy", 1),
    "rainy": ("rainy", 1), "thunderstorm": ("thunderstorm", 1), "snowflake": ("ac_unit", 0),
    "tv": ("tv", 0), "gamepad": ("sports_esports", 1), "hdmi": ("settings_input_hdmi", 1),
    "apps": ("apps", 1), "settings": ("settings", 1), "speaker": ("speaker", 1),
    "sparkles": ("auto_awesome", 1), "bedtime": ("bedtime", 1), "home": ("home", 1),
    "chevron_right": ("chevron_right", 0), "bell": ("notifications", 1), "sync": ("sync", 0),
    "check": ("check", 0), "play": ("play_arrow", 1), "tv_settings": ("display_settings", 1),
    "chevron_down": ("keyboard_arrow_down", 0), "sunrise": ("wb_twilight", 1), "sunset": ("wb_twilight", 1),
    "live_tv": ("live_tv", 1), "bitcoin": ("currency_bitcoin", 0), "mic": ("mic", 1),
    "palette": ("palette", 1), "movie": ("movie", 1), "soccer": ("sports_soccer", 1),
    "remote": ("settings_remote", 1), "accessibility": ("accessibility_new", 0), "speed": ("speed", 1),
    "info": ("info", 1),
}
SCALE = 8          # glyph drawn this much larger, then reduced
PAD = 16           # padding around the glyph (in the large canvas)
AXES = {"FILL": None, "GRAD": 0, "opsz": 48, "wght": 600}


def load_codepoints(path):
    table = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            parts = line.split()
            if len(parts) >= 2:
                table[parts[0]] = int(parts[1], 16)
    return table


def set_axes(font, fill):
    values = []
    for a in font.get_variation_axes():
        name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
        key = {"Fill": "FILL", "Grade": "GRAD", "Optical Size": "opsz", "Optical size": "opsz",
               "Weight": "wght"}.get(name, name)
        v = {"FILL": fill, "GRAD": 0, "opsz": 48, "wght": 600}.get(key, a["default"])
        values.append(max(a["minimum"], min(a["maximum"], v)))
    font.set_variation_by_axes(values)


def render(font_path, codepoint, fill, em):
    big_em = em * SCALE
    font = ImageFont.truetype(font_path, big_em)
    set_axes(font, fill)
    side = big_em * 4  # a 4 em square holds one glyph (same result as a larger canvas)
    canvas = Image.new("L", (side, side), 0)
    d = ImageDraw.Draw(canvas)
    d.fontmode = "1"  # not antialiased
    d.text((big_em, big_em), chr(codepoint), font=font, fill=255)
    box = canvas.getbbox()
    if not box:
        raise ValueError("empty glyph U+%X" % codepoint)
    # work on the glyph's neighbourhood only (the filters are slow on a big canvas)
    m = PAD + 8
    canvas = canvas.crop((box[0] - m, box[1] - m, box[2] + m, box[3] + m))
    # close hairline gaps: dilate then erode
    canvas = canvas.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))
    l, t, r, b = canvas.getbbox()
    crop = canvas.crop((l - PAD, t - PAD, r + PAD, b + PAD))
    small = crop.resize((max(1, round(crop.width / SCALE)), max(1, round(crop.height / SCALE))), Image.LANCZOS)
    out = Image.new("RGBA", small.size, (255, 255, 255, 0))
    out.putalpha(small)
    return out


def main(argv):
    if len(argv) not in (4, 5):
        sys.exit(__doc__)
    font_path, cp_path, out_dir = argv[1:4]
    em = int(argv[4]) if len(argv) == 5 else 172
    for p in (font_path, cp_path):
        if not os.path.isfile(p):
            sys.exit("not found: " + p)
    table = load_codepoints(cp_path)
    os.makedirs(out_dir, exist_ok=True)
    done = 0
    for name, (symbol, fill) in ICONS.items():
        if symbol not in table:
            sys.exit("no codepoint for %s (%s) in %s" % (name, symbol, cp_path))
        render(font_path, table[symbol], fill, em).save(os.path.join(out_dir, name + ".png"), optimize=True)
        done += 1
    print("ok: %d icons in %s axes: %s" % (done, out_dir, ["FILL", "GRAD", "opsz", "wght"]))


if __name__ == "__main__":
    main(sys.argv)

"""Orlando's app icon: his rocket drawing (Nov 2026), redrawn as a bold launcher icon.

The black body leans right with its jagged top, an orange oval wing on the left and a pointed one on the
right, and two orange flames. Run: python make-icon.py (needs Pillow). Writes the Android launcher icons
into android/app/src/main/res and a preview to icon-preview.png.
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).parent
RES = HERE / 'android/app/src/main/res'
NIGHT = (30, 42, 90)        # adaptive icon background (also values/ic_launcher_background.xml)
GLOW = (52, 74, 150)
INK = (21, 21, 28)          # outlines
BODY = (36, 36, 48)
STRIPE = (54, 54, 70)
ORANGE = (255, 154, 60)
YELLOW = (255, 212, 59)
STAR = (255, 236, 150)

S = 2048                    # drawn big, then scaled down for smooth edges
C = S / 2
K = S / 1024 * 0.78         # rocket coordinates below are on a 1024 grid around the centre
OY = -35                    # nudged up so the flames stay inside the icon's safe circle


def P(pts, dx=0, dy=0):
    return [(C + (x + dx) * K, C + (y + dy + OY) * K) for x, y in pts]


def rocket():
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    w = int(12 * K)
    # flames (behind the body), orange with a yellow core
    for flame, core in (([(-112, 220), (-18, 220), (-70, 380)], [(-90, 225), (-38, 225), (-68, 320)]),
                        ([(-12, 220), (100, 220), (36, 400)], [(10, 225), (78, 225), (40, 335)])):
        d.polygon(P(flame), fill=ORANGE, outline=INK, width=w)
        d.polygon(P(core), fill=YELLOW)
    # wings: oval on the left, pointed on the right
    d.ellipse(P([(-315, 110), (-60, 222)]), fill=ORANGE, outline=INK, width=w)
    d.polygon(P([(90, 95), (310, 165), (90, 225)]), fill=ORANGE, outline=INK, width=w)
    # body with the jagged top
    body = [(-118, 232), (118, 232), (108, -195), (100, -250), (55, -300), (12, -232), (-28, -268), (-104, -262), (-112, -200)]
    d.polygon(P(body), fill=BODY, outline=INK, width=w)
    # felt-tip stripes and the band across, like the drawing
    for x in (-50, 25):
        d.line(P([(x, -150), (x + 4, 210)]), fill=STRIPE, width=int(22 * K))
    d.line(P([(-108, -140), (106, -150)]), fill=INK, width=int(9 * K))
    # soft highlight down the left side so the black body stands out from the night sky
    d.line(P([(-92, -235), (-96, 215)]), fill=(120, 130, 175), width=int(14 * K))
    return layer.rotate(-9, resample=Image.BICUBIC, center=(C, C + (40 + OY) * K))  # leans right


def sparkle(d, x, y, r, col):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i
        rr = r if i % 2 == 0 else r * 0.32
        pts.append((x + math.cos(a) * rr, y + math.sin(a) * rr))
    d.polygon(pts, fill=col)


def foreground():
    """The adaptive-icon foreground (108 dp): soft glow, stars, rocket, all inside the 66 dp safe circle."""
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([C - 560, C - 560, C + 560, C + 560], fill=GLOW + (255,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(160)))
    d = ImageDraw.Draw(img)
    for x, y, r in ((-300, -270, 46), (310, -200, 34), (250, 330, 26), (-330, 250, 22)):
        sparkle(d, C + x * K, C + y * K, r * K, STAR)
    for x, y in ((-180, -360), (200, -340), (360, 40), (-380, 20), (120, 400)):
        d.ellipse([C + x * K - 9 * K, C + y * K - 9 * K, C + x * K + 9 * K, C + y * K + 9 * K], fill=STAR)
    img.alpha_composite(rocket())
    return img


def full(fg, shape):
    """Old-style icon: background + foreground, cropped to the visible 72 dp, in a rounded square or circle."""
    bg = Image.new('RGBA', (S, S), NIGHT + (255,))
    bg.alpha_composite(fg)
    m = S * 18 / 108
    crop = bg.crop((m, m, S - m, S - m))
    mask = Image.new('L', crop.size, 0)
    md = ImageDraw.Draw(mask)
    if shape == 'round':
        md.ellipse([0, 0, crop.width - 1, crop.height - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, crop.width - 1, crop.height - 1], radius=crop.width * 0.22, fill=255)
    out = Image.new('RGBA', crop.size, (0, 0, 0, 0))
    out.paste(crop, mask=mask)
    return out


fg = foreground()
for dpi, scale in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    folder = RES / f'mipmap-{dpi}'
    fg.resize((round(108 * scale),) * 2, Image.LANCZOS).save(folder / 'ic_launcher_foreground.png')
    full(fg, 'square').resize((round(48 * scale),) * 2, Image.LANCZOS).save(folder / 'ic_launcher.png')
    full(fg, 'round').resize((round(48 * scale),) * 2, Image.LANCZOS).save(folder / 'ic_launcher_round.png')
(RES / 'values/ic_launcher_background.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>' % NIGHT,
    encoding='utf-8')
full(fg, 'square').resize((512, 512), Image.LANCZOS).save(HERE / 'icon-preview.png')
print('icons written')

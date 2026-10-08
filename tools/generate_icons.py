"""Render Installer's launcher PNGs from the same geometry as its vector icon.
Run with Python and Pillow: python3 tools/generate_icons.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'android/app/src/main/res'
SCALE = 8
CANVAS = 108 * SCALE

def point(x, y):
    return (round(x * SCALE), round(y * SCALE))

def icon():
    image = Image.new('RGBA', (CANVAS, CANVAS))
    pixels = image.load()
    start, end = (41, 92, 211), (10, 39, 102)
    for y in range(CANVAS):
        for x in range(CANVAS):
            t = (x + y) / (2 * (CANVAS - 1))
            pixels[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(start, end)) + (255,)
    draw = ImageDraw.Draw(image)
    def polygon(points, color):
        draw.polygon([point(*p) for p in points], fill=color)
    # Open package, lit left face and shaded right face.
    polygon([(33,60),(54,71),(54,84),(33,73)], '#FFFFFF')
    polygon([(54,71),(75,60),(75,73),(54,84)], '#CBDEFF')
    polygon([(33,60),(44,54),(54,60),(43,66)], '#ECF4FF')
    polygon([(54,60),(64,54),(75,60),(64,66)], '#FFFFFF')
    # Install arrow.
    polygon([(50,28),(58,28),(58,45),(66,45),(54,57),(42,45),(50,45)], '#4DE3C4')
    return image

image = icon()
# Rounded legacy tile and in-app mark. Android masks the adaptive version itself.
mask = Image.new('L', image.size, 0)
ImageDraw.Draw(mask).rounded_rectangle((0,0,CANVAS-1,CANVAS-1), radius=24*SCALE, fill=255)
image.putalpha(mask)
for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    target = RES / f'mipmap-{density}/ic_launcher.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size,size), Image.Resampling.LANCZOS).save(target)
(ROOT / 'assets').mkdir(exist_ok=True)
image.resize((256,256), Image.Resampling.LANCZOS).save(ROOT / 'assets/installer_icon.png')

"""Contact sheet of screenshots, to review smoke-test output at a glance.

Usage: python3 tools/screens/sheet.py out.png COLUMNS WIDTH img1.png img2.png ...
Needs Pillow (pip install pillow). Each image is scaled to WIDTH and labelled with its file name.
"""
import os
import sys

from PIL import Image, ImageDraw

out, cols, w = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
paths = sys.argv[4:]
scaled = []
for p in paths:
    im = Image.open(p).convert("RGB")
    scaled.append(im.resize((w, int(im.height * w / im.width))))
h = max(i.height for i in scaled) + 24
rows = (len(scaled) + cols - 1) // cols
sheet = Image.new("RGB", (cols * (w + 8), rows * h), "white")
d = ImageDraw.Draw(sheet)
for k, (im, p) in enumerate(zip(scaled, paths)):
    x, y = (k % cols) * (w + 8), (k // cols) * h
    sheet.paste(im, (x, y + 20))
    d.text((x + 4, y + 4), os.path.basename(p), fill="black")
sheet.save(out)

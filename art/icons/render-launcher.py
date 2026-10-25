"""Render the launcher mipmaps from the adaptive-icon SVG that claw-lab.html downloads.

Usage: render-launcher.py adaptive.svg app/src/main/res

The SVG is rasterised at 432 px (xxxhdpi, 108 dp) by headless Chromium, the same renderer the lab page
is viewed in, then downsampled with Lanczos into ic_launcher_foreground.png (the full 108 dp canvas)
and ic_launcher.png (the central 72 dp, for launchers without adaptive icons) at each density.
Needs Pillow and a Chromium or Chrome binary on PATH (or set CHROME=/path/to/it).
"""
import os, shutil, subprocess, sys, tempfile
from PIL import Image

svg_path, res = sys.argv[1], os.path.abspath(sys.argv[2])
chrome = os.environ.get("CHROME") or next((c for c in ("chromium", "chromium-browser", "google-chrome", "chrome") if shutil.which(c)), None)
if not chrome: sys.exit("no Chromium or Chrome on PATH; set CHROME=/path/to/binary")

svg = open(svg_path).read()
assert 'width="384" height="384"' in svg, "expected the adaptive SVG from claw-lab.html (384 design units)"
with tempfile.TemporaryDirectory() as tmp:
    html, png = os.path.join(tmp, "adaptive.html"), os.path.join(tmp, "adaptive.png")
    open(html, "w").write('<html><body style="margin:0">' + svg.replace('width="384" height="384"', 'width="432" height="432"', 1) + '</body></html>')
    subprocess.run([chrome, "--headless=new", "--no-sandbox", "--hide-scrollbars", "--window-size=432,432",
                    "--screenshot=" + png, "file://" + html], check=True, capture_output=True)
    im = Image.open(png).convert("RGB")
assert im.size == (432, 432), im.size
for dens, fg, lg in (("mdpi", 108, 48), ("hdpi", 162, 72), ("xhdpi", 216, 96), ("xxhdpi", 324, 144), ("xxxhdpi", 432, 192)):
    d = os.path.join(res, f"mipmap-{dens}"); os.makedirs(d, exist_ok=True)
    im.resize((fg, fg), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"))
    im.crop((72, 72, 360, 360)).resize((lg, lg), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
print("mipmaps written under", res)

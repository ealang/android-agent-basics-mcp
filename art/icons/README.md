# Icons

The launcher icon and the status-bar icon are a claw gripping a phone. Both are
built in the browser from the same design-space geometry, by two
self-contained pages. Open them from a file URL; no server or build step.

| File | What it is |
|---|---|
| `claw-lab.html` | Launcher icon. Drag the claw or the phone, adjust the sliders, copy the parameter block. Downloads the adaptive-icon SVG and the monochrome (themed-icon) SVG, and previews the themed icon. |
| `launcher.json` | The parameter block of the launcher icon as shipped. |
| `render-launcher.py` | Turns the page's two SVGs into the mipmap PNGs. |
| `launcher.png` | The launcher icon as a launcher shows it: the inner 72 dp of the xxxhdpi foreground under a squircle mask, 288 px, for the README. |
| `claw-stat-lab.html` | Status-bar icon, 24 dp, white on transparent. Emits the finished `VectorDrawable`. |
| `status.json` | The parameter block of the status-bar icon as shipped. |
| `clipper-6.4.2.js` | Polygon boolean library used by the status page (Angus Johnson, Boost licence). |

Both pages open on their shipped parameters. To reproduce a saved icon, paste
its block over the text box and press *Load parameters*; a partial block
adjusts only the keys it names.

## Launcher

`claw-lab.html` shows the icon at full size, as the circle and squircle
masks a launcher may apply, and as the themed icon Android 13 and later
show when the user turns themed icons on: the monochrome layer, tinted
from the wallpaper, under the same masks. *Download adaptive SVG* saves
the 108 dp adaptive-icon foreground with the background baked in;
*Download monochrome SVG* saves the same drawing flattened to black ink
on white paper. Then:

```bash
python3 render-launcher.py adaptive.svg monochrome.svg ../../app/src/main/res
```

writes `ic_launcher_foreground.png` and `ic_launcher_monochrome.png` into
each `mipmap-*` directory, the paper of the monochrome one turned into
transparency so the launcher can tint the ink. It rasterises at 432 px
with headless Chromium and downsamples, so it needs Pillow and a Chromium
or Chrome on `PATH` (or `CHROME=/path/to/binary`). The adaptive icon
itself is `mipmap/ic_launcher.xml`, with the background colour in
`values/ic_launcher_background.xml`. There is no legacy
`ic_launcher.png`: every Android the app installs on draws the adaptive
icon.

## Status bar

Status-bar icons are a single alpha mask, so the status page builds the
silhouette with real polygon booleans: the upper jaw in front of the phone
with a gap cut around it, the phone hiding the lower jaw. Each shape can be
drawn as white fill with a black outline, as a white outline with black fill,
or both. The XML box is the finished drawable: copy it over
`app/src/main/res/drawable/ic_stat_claw.xml`. Its path runs to about 1800
characters, over lint's 800-character `VectorPath` threshold; the drawable
is rasterised once per notification, so `app/lint.xml` ignores the check
for this file rather than coarsening the outline.

# Icons

The launcher icon and the status-bar icon are a claw gripping a phone. Both are
built in the browser from the same design-space geometry, by two
self-contained pages. Open them from a file URL; no server or build step.

| File | What it is |
|---|---|
| `claw-lab.html` | Launcher icon. Drag the claw or the phone, adjust the sliders, copy the parameter block. |
| `launcher.json` | The parameter block of the launcher icon as shipped. |
| `render-launcher.py` | Turns the page's adaptive SVG into the mipmap PNGs. |
| `claw-stat-lab.html` | Status-bar icon, 24 dp, white on transparent. Emits the finished `VectorDrawable`. |
| `status.json` | The parameter block of the status-bar icon as shipped. |
| `clipper-6.4.2.js` | Polygon boolean library used by the status page (Angus Johnson, Boost licence). |

Both pages open on their shipped parameters. To reproduce a saved icon, paste
its block over the text box and press *Load parameters*; a partial block
adjusts only the keys it names.

## Launcher

`claw-lab.html` shows the icon at full size and as the circle and squircle
masks a launcher may apply. *Download adaptive SVG* saves the 108 dp
adaptive-icon foreground, with the background baked in. Then:

```bash
python3 render-launcher.py adaptive.svg ../../app/src/main/res
```

writes `ic_launcher_foreground.png` and `ic_launcher.png` into each
`mipmap-*` directory. It rasterises at 432 px with headless Chromium and
downsamples, so it needs Pillow and a Chromium or Chrome on `PATH` (or
`CHROME=/path/to/binary`). The adaptive icon itself is
`mipmap-anydpi-v26/ic_launcher.xml`, with the background colour in
`values/ic_launcher_background.xml`.

## Status bar

Status-bar icons are a single alpha mask, so the status page builds the
silhouette with real polygon booleans: the upper jaw in front of the phone
with a gap cut around it, the phone hiding the lower jaw. Each shape can be
drawn as white fill with a black outline, as a white outline with black fill,
or both. The XML box is the finished drawable: copy it over
`app/src/main/res/drawable/ic_stat_claw.xml`.

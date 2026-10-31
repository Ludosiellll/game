# Name and icon options (31.10)

Denis asked for a new name and a new icon, 3–4 options. `render.js` draws them with Playwright (`node render.js <out-dir>`):
`options.png` (the four, large, with the name and why, and on a phone's home screen) and `icon-*.png` (each 512 × 512).
The icons are drawn in the adaptive icon's 108 box, inside its safe area, so the chosen one turns into
`ic_launcher_foreground.xml` as it is. Nothing is in the app until Denis picks one.

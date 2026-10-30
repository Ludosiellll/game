// Store art for Google Play: the 512×512 icon and the 1024×500 feature graphic, drawn with Playwright.
// Usage: node render.js <out-dir>
const { chromium } = require('playwright');
const path = require('path');
const out = process.argv[2] || __dirname;
const fs = require('fs');
// Embedded: a page set from a string may not load file:// fonts.
const embed = (f) => 'data:font/otf;base64,' + fs.readFileSync(path.resolve(__dirname, '../../../app/src/main/res/font/' + f)).toString('base64');
const font = embed('inter_display_black.otf');
const body = embed('inter_semibold.otf');
const css = `@font-face { font-family: D; src: url(${font}); } @font-face { font-family: B; src: url(${body}); }
  html, body { margin: 0; } * { box-sizing: border-box; }`;
// The launcher icon: the plate seen edge-on, crossed by the bar, on its own dark ground.
const icon = `<style>${css}</style><svg width="512" height="512" viewBox="21 21 66 66" xmlns="http://www.w3.org/2000/svg">
  <rect x="0" y="0" width="108" height="108" fill="BG"/>
  <path fill="none" stroke="#D7FF3A" stroke-width="7" d="M54,40 C65.6,40 75,46.3 75,54 C75,61.7 65.6,68 54,68 C42.4,68 33,61.7 33,54 C33,46.3 42.4,40 54,40 Z"/>
  <path fill="#D7FF3A" d="M50.5,31 h7 v46 h-7 z"/></svg>`;
const banner = `<style>${css}
  .b { width: 1024px; height: 500px; background: #fff; color: #111; padding: 56px 64px; position: relative; overflow: hidden; }
  .t { font: 900 150px/0.9 D; letter-spacing: -0.045em; text-transform: uppercase; }
  .r { height: 8px; background: #111; margin: 26px 0 22px; width: 560px; }
  .s { font: 600 34px/1.25 B; color: #111; }
  .s span { color: #FF4D12; }
  .k { position: absolute; right: 64px; bottom: 56px; font: 900 120px/1 D; color: #FF4D12; letter-spacing: -0.04em; }
  .k small { display: block; font: 600 22px/1.2 B; color: #6F6F6F; letter-spacing: 0; text-align: right; }</style>
  <div class="b"><div class="t">Форма</div><div class="r"></div>
  <div class="s">Зал и бег по плану.<br>Веса, круги, прогресс <span>и тренер.</span></div>
  <div class="k">+8%<small>сила за 6 недель</small></div></div>`;
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  const bg = process.env.ICON_BG || '#101112';
  await page.setViewportSize({ width: 512, height: 512 });
  await page.setContent(icon.replace('BG', bg));
  await page.screenshot({ path: path.join(out, 'icon-512.png'), clip: { x: 0, y: 0, width: 512, height: 512 } });
  await page.setViewportSize({ width: 1024, height: 500 });
  await page.setContent(banner);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(out, 'feature-1024x500.png'), clip: { x: 0, y: 0, width: 1024, height: 500 } });
  await browser.close();
})();

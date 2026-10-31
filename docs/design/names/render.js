// Latin brand names under the chosen icon (Denis, 31.10: «что-то звучащее прикольное», like his Meldy).
// Usage: node render.js <out-dir>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const out = process.argv[2] || __dirname;
const embed = (f) => 'data:font/otf;base64,' + fs.readFileSync(path.resolve(__dirname, '../../../app/src/main/res/font/' + f)).toString('base64');
// The launcher icon's own vector paths, as an SVG.
const xml = fs.readFileSync(path.resolve(__dirname, '../../../app/src/main/res/drawable/ic_launcher_foreground.xml'), 'utf8');
const paths = [...xml.matchAll(/android:fillColor="#FF([0-9A-F]{6})"(?:\s+android:fillType="evenOdd")?\s+android:pathData="([^"]+)"/g)]
  .map(([, c, d]) => `<path fill="#${c}" fill-rule="evenodd" d="${d}"/>`).join('');
const icon = (s, r) => `<div style="width:${s}px;height:${s}px;border-radius:${r};overflow:hidden;flex:none">
  <svg width="${s}" height="${s}" viewBox="18 18 72 72"><rect x="0" y="0" width="108" height="108" fill="#0D0D0D"/>${paths}</svg></div>`;
const names = [
  ['Kilo', 'Кило', 'Килограммы в зале и километры на беге. Одно слово про оба.'],
  ['Lapsy', 'Лэпси', 'Laps — круги на стадионе. Мягкое «-y» на конце, как у Meldy.'],
  ['Hybi', 'Хайби', 'От hybrid: зал плюс бег. Коротко и по-дружески.'],
  ['Ferrun', 'Феррун', 'Ferrum (железо) + run (бег). Половина блина, половина дорожки.'],
  ['Dashy', 'Дэши', 'Dash — рывок, спринт. Звучит быстро и легко.'],
  ['Splity', 'Сплити', 'Сплиты — время кругов на стадионе и сплит-программа в зале.'],
];
const css = `@font-face{font-family:D;src:url(${embed('inter_display_black.otf')})}
  @font-face{font-family:M;src:url(${embed('inter_medium.otf')})}
  *{box-sizing:border-box;margin:0} body{background:#070707;color:#F4F4F2}
  .b{width:1500px;padding:52px 60px 60px}
  .t{font:900 60px/1 D;text-transform:uppercase;letter-spacing:-.04em}
  .s{font:500 21px/1.4 M;color:#9E9E9E;margin:12px 0 36px}
  .g{display:grid;grid-template-columns:repeat(3,1fr);gap:24px}
  .c{background:#0D0D0D;border-top:4px solid #F4F4F2;padding:26px 26px 28px;display:flex;gap:22px;align-items:center}
  .n{font:900 56px/1 D;letter-spacing:-.035em}
  .r{font:500 18px/1 M;color:#FF5A1F;margin:8px 0 10px}
  .w{font:500 17px/1.45 M;color:#A8A8A8}
  .p{margin-top:40px;border-radius:28px;padding:30px 40px;background:radial-gradient(120% 140% at 20% 0%,#2c3a4a 0%,#141b24 55%,#0b0e12 100%)}
  .a{display:grid;grid-template-columns:repeat(6,1fr);gap:10px}
  .ap{display:flex;flex-direction:column;align-items:center;gap:10px}
  .ap span{font:500 18px/1 M;color:#fff}`;
const html = `<style>${css}</style><div class="b"><div class="t">Название</div>
  <div class="s">Латиницей, коротко и звучно, как Meldy. Под каждым — как читается и почему так.</div>
  <div class="g">${names.map(([n, ru, why]) => `<div class="c">${icon(92, '50%')}<div><div class="n">${n}</div><div class="r">${ru}</div><div class="w">${why}</div></div></div>`).join('')}</div>
  <div class="p"><div class="a">${names.map(([n]) => `<div class="ap">${icon(96, '50%')}<span>${n}</span></div>`).join('')}</div></div></div>`;
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.setViewportSize({ width: 1500, height: 900 });
  await p.setContent(html); await p.evaluate(() => document.fonts.ready); await p.waitForTimeout(200);
  const h = await p.evaluate(() => document.querySelector('.b').getBoundingClientRect().height);
  await p.setViewportSize({ width: 1500, height: Math.ceil(h) });
  await p.screenshot({ path: path.join(out, 'names.png') }); await b.close();
})();

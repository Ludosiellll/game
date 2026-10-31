// Name and icon options (31.10): a board with each icon large, its name and why, and the icons on a phone's home
// screen. Usage: node render.js <out-dir>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const out = process.argv[2] || __dirname;
const embed = (f) => 'data:font/otf;base64,' + fs.readFileSync(path.resolve(__dirname, '../../../app/src/main/res/font/' + f)).toString('base64');

const INK = '#F4F4F2', BG = '#0D0D0D', OR = '#FF5A1F', GREY = '#5A5A5A';
// Each icon is drawn in the adaptive icon's 108 × 108 box; what matters sits in the middle 66.
const icons = {
  ryvok: `
    <path d="M38 78 C 44 77 52 72 54 52" fill="none" stroke="${OR}" stroke-width="5" stroke-linecap="round"/>
    <path d="M47 60 L 54 51 L 61 60" fill="none" stroke="${OR}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
    <line x1="24" y1="40" x2="84" y2="40" stroke="${INK}" stroke-width="4" stroke-linecap="round"/>
    <rect x="29" y="29" width="7" height="22" rx="2.5" fill="${INK}"/>
    <rect x="37.5" y="32" width="5" height="16" rx="2" fill="${INK}"/>
    <rect x="72" y="29" width="7" height="22" rx="2.5" fill="${INK}"/>
    <rect x="65.5" y="32" width="5" height="16" rx="2" fill="${INK}"/>`,
  krug: `
    <rect x="25" y="37" width="58" height="34" rx="17" fill="none" stroke="${INK}" stroke-width="6"/>
    <line x1="58" y1="64" x2="58" y2="78" stroke="${OR}" stroke-width="4" stroke-linecap="round"/>
    <circle cx="58" cy="71" r="5.5" fill="${OR}"/>`,
  gibrid: `
    <path d="M54 29 A25 25 0 0 0 54 79 Z" fill="${INK}"/>
    <circle cx="54" cy="54" r="6" fill="${BG}"/>
    <path d="M57 29 A25 25 0 0 1 57 79" fill="none" stroke="${OR}" stroke-width="4.5"/>
    <path d="M57 38 A16 16 0 0 1 57 70" fill="none" stroke="${OR}" stroke-width="4.5"/>
    <path d="M57 47 A7 7 0 0 1 57 61" fill="none" stroke="${OR}" stroke-width="4.5"/>`,
  otsechka: `
    <rect x="49" y="22" width="10" height="7" rx="2" fill="${INK}"/>
    <circle cx="54" cy="57" r="24" fill="none" stroke="${INK}" stroke-width="6"/>
    <path d="M54 57 L54 37 A20 20 0 0 1 71.3 47 Z" fill="${OR}"/>
    <circle cx="54" cy="57" r="3.5" fill="${INK}"/>`,
};
const options = [
  { key: 'ryvok', name: 'Рывок', why: 'Рывок штанги в зале и рывок на финише: одно слово про оба твоих дела. Штанга и путь, которым её поднимают.' },
  { key: 'krug', name: 'Круг', why: 'Круг на стадионе от твоей линии и круг тренировок недели.' },
  { key: 'gibrid', name: 'Гибрид', why: 'Зал и бег вместе: половина блина, половина беговых дорожек.' },
  { key: 'otsechka', name: 'Отсечка', why: 'Время круга на линии. Секундомер с отрезанным кругом.' },
];
const svg = (k, size, shape) => `<svg width="${size}" height="${size}" viewBox="21 21 66 66" xmlns="http://www.w3.org/2000/svg">
  <defs><clipPath id="m${k}${size}">${shape === 'circle' ? '<circle cx="54" cy="54" r="33"/>' : '<rect x="21" y="21" width="66" height="66" rx="15"/>'}</clipPath></defs>
  <g clip-path="url(#m${k}${size})"><rect x="0" y="0" width="108" height="108" fill="${BG}"/>
  <g transform="translate(54 54) scale(0.74) translate(-54 -54)">${icons[k]}</g></g></svg>`;

const css = `@font-face { font-family: D; src: url(${embed('inter_display_black.otf')}); }
  @font-face { font-family: B; src: url(${embed('inter_semibold.otf')}); }
  @font-face { font-family: M; src: url(${embed('inter_medium.otf')}); }
  * { box-sizing: border-box; margin: 0; }
  body { background: #070707; color: ${INK}; font-family: B; }
  .board { width: 1600px; padding: 56px 64px 64px; }
  .top { font: 900 64px/1 D; text-transform: uppercase; letter-spacing: -0.04em; }
  .sub { font: 500 22px/1.4 M; color: #9E9E9E; margin: 14px 0 40px; }
  .row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 28px; }
  .card { background: ${BG}; border-top: 4px solid ${INK}; padding: 28px 24px 30px; }
  .num { font: 600 18px/1 B; color: #6C6C6C; letter-spacing: .12em; }
  .big { margin: 22px 0 24px; display: flex; justify-content: center; }
  .big svg { border-radius: 60px; box-shadow: 0 0 0 1px #262626; }
  .name { font: 900 52px/1 D; text-transform: uppercase; letter-spacing: -0.04em; }
  .why { font: 500 19px/1.45 M; color: #A8A8A8; margin-top: 12px; min-height: 84px; }
  .phone { margin-top: 44px; border-radius: 28px; padding: 34px 40px 30px;
    background: radial-gradient(120% 140% at 20% 0%, #2c3a4a 0%, #141b24 55%, #0b0e12 100%); }
  .phone .label { font: 500 18px/1 M; color: #c9d1da; margin-bottom: 26px; }
  .apps { display: grid; grid-template-columns: repeat(8, 1fr); gap: 18px 10px; }
  .app { display: flex; flex-direction: column; align-items: center; gap: 10px; }
  .app span { font: 500 17px/1 M; color: #fff; text-shadow: 0 1px 2px rgba(0,0,0,.6); }
  .ghost { width: 96px; height: 96px; border-radius: 50%; background: #3a4552; opacity: .55; }`;

const board = `<style>${css}</style><div class="board">
  <div class="top">Название и значок</div>
  <div class="sub">Четыре варианта в цветах нового вида: чёрный, белый и один оранжевый. Значок — как на рабочем столе телефона, внизу.</div>
  <div class="row">${options.map((o, i) => `<div class="card">
    <div class="num">ВАРИАНТ ${i + 1}</div>
    <div class="big">${svg(o.key, 240, 'square')}</div>
    <div class="name">${o.name}</div>
    <div class="why">${o.why}</div></div>`).join('')}</div>
  <div class="phone"><div class="label">На рабочем столе</div><div class="apps">
    ${options.map((o) => `<div class="app">${svg(o.key, 96, 'circle')}<span>${o.name}</span></div>`).join('')}
    ${['Камера', 'Карты', 'Музыка', 'Почта'].map((n) => `<div class="app"><div class="ghost"></div><span>${n}</span></div>`).join('')}
  </div></div></div>`;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.setContent(board);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(300);
  const h = await page.evaluate(() => document.querySelector('.board').getBoundingClientRect().height);
  await page.setViewportSize({ width: 1600, height: Math.ceil(h) });
  await page.screenshot({ path: path.join(out, 'options.png'), clip: { x: 0, y: 0, width: 1600, height: Math.ceil(h) } });
  // Each icon alone, large.
  for (const o of options) {
    await page.setViewportSize({ width: 512, height: 512 });
    await page.setContent(`<style>html,body{margin:0;background:${BG}}</style>${svg(o.key, 512, 'square')}`);
    await page.screenshot({ path: path.join(out, `icon-${o.key}.png`), clip: { x: 0, y: 0, width: 512, height: 512 } });
  }
  await browser.close();
})();

// Preview of the body map: node render.js <out-dir>
const { chromium } = require('playwright');
const path = require('path');
const { front, back } = require('./body.js');
const out = process.argv[2] || __dirname;
const SKIN = '#262626', IDLE = '#3A3A3A', OR = [255, 90, 31];
const load = { CHEST: 0.3, BACK: 1, SHOULDERS: 0.6, BICEPS: 0.5, TRICEPS: 0.4, QUADS: 0.9, HAMSTRINGS: 0.7, GLUTES: 0.8, CALVES: 0.2, CORE: 0.4 };
const mix = (t) => { const a = [58, 58, 58]; return `rgb(${a.map((v, i) => Math.round(v + (OR[i] - v) * t)).join(',')})`; };
const fig = (parts) => parts.map(([, m, d]) => `<path d="${d}" fill="${m ? mix(load[m]) : SKIN}"/>`).join('');
const html = `<body style="margin:0;background:#0D0D0D"><svg width="560" height="600" viewBox="0 0 220 230" xmlns="http://www.w3.org/2000/svg">
  <g transform="translate(5,10)">${fig(front)}</g><g transform="translate(115,10)">${fig(back)}</g></svg></body>`;
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.setViewportSize({ width: 560, height: 600 }); await p.setContent(html);
  await p.screenshot({ path: path.join(out, 'body.png') }); await b.close();
})();

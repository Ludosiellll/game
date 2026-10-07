// Скриншоты веб-сборки на размерах телефонов. Состояния игры подкладываются
// готовыми сохранениями в localStorage (ключ как у shared_preferences на вебе).
//
// Запуск:
//   flutter build web --release
//   (cd build/web && python3 -m http.server 8765) &
//   cd tool/visual_qa && npm install && node shots.mjs
//
// Флаги Chromium нужны в контейнерах без видеокарты (программный WebGL),
// локаль ru-RU — потому что системная локаль «C» ломает старт Flutter web.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const URL = process.env.URL ?? 'http://127.0.0.1:8765/';
const OUT = 'out';
mkdirSync(OUT, { recursive: true });

const now = Date.now();
const base = {
  v: 1, echo: '0e0', runEarned: '0e0', totalEarned: '0e0', region: 0, generators: {}, birds: 0,
  seeds: '0e0', songs: 0, memory: {}, whales: [], lastSeenMs: now, boostUntilMs: 0, taps: 40,
  playSeconds: 600, createdMs: now - 3600e3,
};

const scenarios = [
  { name: '03_region1_mid', save: { ...base, echo: '4.5e3', runEarned: '9e3', generators: { flower: 28, bush: 8 }, birds: 4 }, taps: 6 },
  {
    name: '04_region2_boost',
    save: { ...base, region: 1, echo: '2.1e6', runEarned: '5e6', generators: { flower: 40, bush: 30, hive: 15, mill: 3 }, birds: 8, boostUntilMs: now + 9 * 60e3 },
    taps: 6,
  },
  {
    name: '05_region3_night',
    save: {
      ...base, region: 2, songs: 3, whales: ['dawn', 'storm'], seeds: '12e0', echo: '8.2e9', runEarned: '2e10',
      generators: { flower: 60, bush: 50, hive: 40, mill: 30, lantern: 20, observatory: 10 }, birds: 12, memory: { echo: 2 },
    },
    taps: 6,
  },
  { name: '06_offline', save: { ...base, generators: { flower: 30, bush: 10 }, birds: 3, lastSeenMs: now - 3 * 3600e3 } },
  {
    name: '07_song_tab',
    save: { ...base, region: 2, songs: 1, whales: ['dawn'], seeds: '7e0', echo: '1e9', runEarned: '3e8',
      generators: { flower: 60, bush: 50, hive: 40, mill: 30, lantern: 20, observatory: 12 }, birds: 10 },
    tab: 1,
  },
  { name: '08_more_tab', save: { ...base, region: 1, generators: { flower: 40, bush: 30, hive: 10 }, birds: 6 }, tab: 2 },
  { name: '09_small_screen', save: { ...base, echo: '4.5e3', generators: { flower: 28, bush: 8 }, birds: 4 }, viewport: { width: 360, height: 640 } },
];

const browser = await chromium.launch({ args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
const problems = [];

for (const sc of scenarios) {
  const viewport = sc.viewport ?? { width: 390, height: 844 };
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => problems.push(`${sc.name}: ${String(e).slice(0, 200)}`));
  await page.addInitScript((value) => localStorage.setItem('skywhale.save.v1', value), JSON.stringify(JSON.stringify(sc.save)));
  await page.goto(URL, { waitUntil: 'load' });
  await page.waitForFunction(() => document.querySelector('flutter-view') !== null, null, { timeout: 60000 });
  await page.waitForTimeout(1800);
  if (sc.tab !== undefined) {
    // Вкладки делят ширину экрана на три части.
    const y = Math.round(viewport.height * 0.57) + 28;
    await page.touchscreen.tap(Math.round(viewport.width * (sc.tab + 0.5) / 3), y);
    await page.waitForTimeout(700);
  }
  for (let i = 0; i < (sc.taps ?? 0); i++) {
    await page.touchscreen.tap(viewport.width * 0.3 + (i % 3) * 50, viewport.height * 0.22 + (i % 2) * 70);
    await page.waitForTimeout(90);
  }
  await page.waitForTimeout(sc.taps ? 200 : 300);
  await page.screenshot({ path: `${OUT}/${sc.name}.png` });
  await ctx.close();
}

await browser.close();
console.log(problems.length ? problems.join('\n') : 'ошибок на страницах нет');

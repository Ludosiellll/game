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
// Кнопки нажимаются по координатам: Flutter web рисует всё на холсте.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const URL = process.env.URL ?? 'http://127.0.0.1:8765/';
const OUT = 'out';
mkdirSync(OUT, { recursive: true });

const now = Date.now();
const base = {
  v: 2, echo: '0e0', runEarned: '0e0', totalEarned: '0e0', region: 0, generators: {}, birds: 0,
  seeds: '0e0', songs: 0, memory: {}, whales: [], lastSeenMs: now, boostUntilMs: 0, taps: 40,
  playSeconds: 600, createdMs: now - 3600e3, years: 3, age: 0, scales: 0, traits: {}, guardiansBeaten: 0,
  cyclePeak: '0e0', lastCyclePeak: '0e0', bossesMet: [], fightCooldownUntilMs: 0, lastSongMs: 0,
};
const allGens = { flower: 60, bush: 50, hive: 40, mill: 30, lantern: 20, observatory: 12 };

// Кнопки нижней панели и боковая кнопка события (доли ширины, отступ снизу в px).
const nav = { garden: [0.09, 62], whale: [0.265, 62], path: [0.735, 62], more: [0.91, 62], event: [0.875, 190] };

const scenarios = [
  { name: '01_calf_start', save: null },
  { name: '02_calf_garden', save: { ...base, echo: '320e0', runEarned: '900e0', generators: { flower: 26, bush: 9 }, birds: 3 }, taps: 6 },
  {
    name: '03_young_village',
    save: { ...base, age: 1, years: 160, songs: 1, region: 1, guardiansBeaten: 1, whales: ['dawn'], echo: '2.1e6',
      runEarned: '5e6', generators: { flower: 40, bush: 30, hive: 15, mill: 3 }, birds: 6, boostUntilMs: now + 9 * 60e3 },
    taps: 6,
  },
  {
    name: '04_guardian_ready',
    save: { ...base, age: 1, years: 180, songs: 1, region: 1, guardiansBeaten: 1, whales: ['dawn'], echo: '5.4e6',
      runEarned: '9e6', generators: { flower: 60, bush: 50, hive: 30, mill: 12 }, birds: 8, lastSongMs: now - 5 * 3600e3 },
  },
  { name: '05_shop', save: { ...base, age: 1, years: 150, songs: 1, region: 1, guardiansBeaten: 1, echo: '3.3e4',
      generators: { flower: 48, bush: 31, hive: 12, mill: 4 }, birds: 5 }, open: 'garden' },
  { name: '06_whale_sheet', save: { ...base, age: 1, years: 520, songs: 3, region: 1, guardiansBeaten: 1, scales: 9,
      traits: { voice: 1 }, whales: ['dawn'], lastCyclePeak: '4e4', generators: { flower: 40, bush: 30, hive: 10 }, birds: 5 }, open: 'whale' },
  { name: '07_fight', save: { ...base, age: 1, years: 180, songs: 1, region: 1, guardiansBeaten: 1, whales: ['dawn'],
      echo: '5.4e6', generators: { flower: 60, bush: 50, hive: 30, mill: 12 }, birds: 8 }, open: 'event', fightTaps: 14 },
  { name: '08_adult', save: { ...base, age: 2, years: 640, songs: 5, region: 2, guardiansBeaten: 2, whales: ['dawn', 'storm'],
      echo: '8.2e9', generators: allGens, birds: 12, memory: { echo: 3 }, seeds: '40e0' }, taps: 4 },
  { name: '09_ancient', save: { ...base, age: 3, years: 1620, songs: 9, region: 2, guardiansBeaten: 2, whales: ['dawn', 'storm', 'night'],
      echo: '4.1e12', generators: { ...allGens, flower: 120, bush: 90 }, birds: 16 } },
  { name: '10_deity', save: { ...base, age: 4, years: 2300, songs: 12, region: 2, guardiansBeaten: 3, whales: ['dawn', 'storm', 'night', 'mother'],
      echo: '7.7e15', generators: { ...allGens, flower: 200, bush: 120, hive: 60, mill: 55 }, birds: 20 }, taps: 4 },
  { name: '11_path_sheet', save: { ...base, age: 1, years: 180, songs: 1, region: 1, guardiansBeaten: 1, whales: ['dawn'], seeds: '9e0',
      echo: '5.4e6', generators: { flower: 60, bush: 50, hive: 30, mill: 12 }, birds: 8 }, open: 'path' },
  { name: '12_more_sheet', save: { ...base, age: 1, years: 150, region: 1, guardiansBeaten: 1, generators: { flower: 40, bush: 30 } }, open: 'more' },
  { name: '13_offline', save: { ...base, generators: { flower: 30, bush: 10 }, birds: 3, lastSeenMs: now - 3 * 3600e3 } },
  { name: '14_small_screen', save: { ...base, age: 1, years: 150, region: 1, guardiansBeaten: 1, echo: '4.5e3',
      generators: { flower: 28, bush: 8, hive: 2 }, birds: 4 }, viewport: { width: 360, height: 640 } },
  // Испытание: лист «Кит» → «БОЙ» в карточке Тени кита.
  { name: '15_trial_fight', save: { ...base, age: 1, years: 520, songs: 3, region: 1, guardiansBeaten: 1, scales: 9,
      whales: ['dawn'], lastCyclePeak: '2e2', generators: { flower: 40, bush: 30, hive: 10 }, birds: 5 },
    open: 'whale', steps: [{ tap: [0.82, 0.51] }, { wait: 4200 }, { tap: [0.5, 0.3] }, { tap: [0.55, 0.32] }] },
  // Сад сильнее Тени — «ПРОПЕТЬ», победа, кит взрослеет.
  { name: '16_age_up', save: { ...base, age: 1, years: 520, songs: 3, region: 1, guardiansBeaten: 1, scales: 9,
      traits: { fins: 1 }, whales: ['dawn'], generators: { flower: 40, bush: 30, hive: 10 }, birds: 5 },
    open: 'whale', steps: [{ tap: [0.82, 0.51] }, { wait: 900 }, { tap: [0.5, 0.79] }, { wait: 700 },
      { shot: '16a_victory' }, { wait: 3000 }] },
  // Страж побеждён, регион восстановлен — боковая кнопка «В ПУТЬ».
  { name: '17_migrate_ready', save: { ...base, age: 1, years: 150, songs: 1, region: 0, guardiansBeaten: 1,
      echo: '2e3', generators: { flower: 60, bush: 30 }, birds: 3 } },
];

const browser = await chromium.launch({ args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
const problems = [];

for (const sc of scenarios) {
  const viewport = sc.viewport ?? { width: 390, height: 844 };
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ru-RU' });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => problems.push(`${sc.name}: ${String(e).slice(0, 200)}`));
  page.on('console', (m) => { if (m.type() === 'error') problems.push(`${sc.name}: ${m.text().slice(0, 200)}`); });
  if (sc.save) {
    await page.addInitScript((value) => localStorage.setItem('skywhale.save.v1', value), JSON.stringify(JSON.stringify(sc.save)));
  }
  await page.goto(URL, { waitUntil: 'load' });
  await page.waitForFunction(() => document.querySelector('flutter-view') !== null, null, { timeout: 60000 });
  await page.waitForTimeout(3500);
  const tap = async ([fx, fromBottom]) => page.touchscreen.tap(Math.round(viewport.width * fx), viewport.height - fromBottom);
  if (sc.open) {
    await tap(nav[sc.open]);
    await page.waitForTimeout(sc.open === 'event' ? 2600 : 900);
  }
  for (let i = 0; i < (sc.fightTaps ?? 0); i++) {
    await page.touchscreen.tap(viewport.width * 0.5 + (i % 3) * 30, viewport.height * 0.3);
    await page.waitForTimeout(120);
  }
  for (const st of sc.steps ?? []) {
    if (st.tap) await page.touchscreen.tap(Math.round(viewport.width * st.tap[0]), Math.round(viewport.height * st.tap[1]));
    if (st.wait) await page.waitForTimeout(st.wait);
    if (st.shot) await page.screenshot({ path: `${OUT}/${st.shot}.png` });
  }
  for (let i = 0; i < (sc.taps ?? 0); i++) {
    await page.touchscreen.tap(viewport.width * 0.3 + (i % 3) * 50, viewport.height * 0.4 + (i % 2) * 70);
    await page.waitForTimeout(90);
  }
  await page.waitForTimeout(250);
  await page.screenshot({ path: `${OUT}/${sc.name}.png` });
  await ctx.close();
}

await browser.close();
console.log(problems.length ? problems.join('\n') : 'ошибок на страницах нет');

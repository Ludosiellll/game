// The «Минимализм» direction Denis picked (30.10), worked out on four real screens and a dark variant.
const { chromium } = require('playwright');
const out = process.argv[2];

const icon = {
  today: '<path d="M4 10.5 12 4l8 6.5V20h-5v-5H9v5H4z"/>',
  plan: '<rect x="4" y="5" width="16" height="15" rx="1.5"/><path d="M4 9.5h16M9 3v4M15 3v4"/>',
  progress: '<path d="M4 19h16M6 15l4-4 3 3 5-6"/>',
  coach: '<path d="M5 5h14v10H10l-5 4z"/>',
  more: '<circle cx="6" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="18" cy="12" r="1.2"/>',
};
const nav = (on) => `<div class="nav">${[['today','Сегодня'],['plan','План'],['progress','Прогресс'],['coach','Тренер'],['more','Ещё']]
  .map(([k,n])=>`<div class="${k==on?'on':''}"><svg viewBox="0 0 24 24">${icon[k]}</svg><span>${n}</span></div>`).join('')}</div>`;
const status = `<div class="status"><span>18:42</span><span class="sys"><svg viewBox="0 0 24 12"><rect x="0" y="7" width="3" height="5"/><rect x="5" y="5" width="3" height="7"/><rect x="10" y="2" width="3" height="10"/><rect x="15" y="0" width="3" height="12"/></svg> 74%</span></div>`;

const today = (dark) => `
<div class="phone ${dark?'dark':''}">${status}
  <div class="top"><span class="kicker">ПЯТНИЦА, 30 ОКТЯБРЯ</span><span class="ava">Д</span></div>
  <div class="mega">СЕГОДНЯ</div>
  <div class="mood"><b>Силы есть, можно в полную</b><span>3 из 5 тренировок на этой неделе</span></div>
  <div class="sec"><span class="k">ЗАЛ · 55 МИН</span></div>
  <div class="title">ВЕРХ ТЕЛА</div>
  <table class="ex">
    <tr><td>Жим лёжа</td><td class="m b">80 кг</td><td class="m">4 × 6</td></tr>
    <tr><td>Тяга штанги</td><td class="m b">65 кг</td><td class="m">4 × 8</td></tr>
    <tr><td>Жим гантелей</td><td class="m b">22 кг</td><td class="m">3 × 10</td></tr>
  </table>
  <div class="more">и подтягивания</div>
  <div class="go"><span>НАЧАТЬ</span><svg viewBox="0 0 24 24"><path d="M5 12h13M13 6l6 6-6 6"/></svg></div>
  <div class="sec"><span class="k">ВЕЧЕРОМ БЕГ</span></div>
  <div class="line"><b>30 минут спокойно</b><span class="m">5:50–6:20 на км</span></div>
  <div class="goal">
    <div class="goalhead"><span>1500 м</span><span class="m"><b class="acc">5:52</b> сейчас · цель 5:30</span></div>
    <div class="track"><i class="was"></i><i class="now"></i><i class="end"></i></div>
  </div>
  ${nav('today')}
</div>`;

const workout = `
<div class="phone">${status}
  <div class="top"><span class="kicker">2 ИЗ 4 · 18 МИН</span><span class="k soft">ЗАВЕРШИТЬ</span></div>
  <div class="mega2">ЖИМ<br>ЛЁЖА</div>
  <div class="plain">80 кг, 4 подхода по 6. Не до отказа.</div>
  <table class="sets">
    <tr class="done"><td class="m">1</td><td class="m">80 кг × 6</td><td>✓</td></tr>
    <tr class="done"><td class="m">2</td><td class="m">80 кг × 6</td><td>✓</td></tr>
    <tr class="cur"><td class="m">3</td><td class="m big">80 × 6</td><td class="acc">сейчас</td></tr>
    <tr><td class="m">4</td><td class="m">80 кг × 6</td><td></td></tr>
  </table>
  <div class="rest"><span class="k">ОТДЫХ</span><b class="m">1:24</b><div class="rbar"><i></i></div></div>
  <div class="go"><span>ГОТОВО</span><svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7"/></svg></div>
  <div class="next"><span class="k soft">ДАЛЬШЕ</span><b>Тяга штанги</b></div>
  ${nav('today')}
</div>`;

const run = `
<div class="phone">${status}
  <div class="top"><span class="kicker">КОНТРОЛЬНЫЙ · 1500 М</span><span class="k soft">ЦЕЛЬ 5:30</span></div>
  <div class="clock m">3:21</div>
  <div class="laps">
    <div><span class="k">КРУГ</span><b class="m">5<small> из 7</small></b></div>
    <div><span class="k">ПОСЛЕДНИЙ</span><b class="m acc">48,6</b><i>в темпе, нужно 47–49</i></div>
  </div>
  <div class="lapbars">${[49.3,48.1,48.9,48.6].map(v=>`<div><i style="height:${(v-44)*14}px"></i><span class="m">${String(v).replace('.',',')}</span></div>`).join('')}<div class="ghost"><i></i><span class="m"> </span></div><div class="ghost"><i></i><span class="m"> </span></div><div class="ghost"><i></i><span class="m"> </span></div></div>
  <div class="lapbtn">КРУГ</div>
  <div class="hint">Жми на своей линии. Пауза: держи кнопку.</div>
</div>`;

const progress = `
<div class="phone">${status}
  <div class="top"><span class="kicker">ОКТЯБРЬ · 12 ТРЕНИРОВОК</span><span class="ava">Д</span></div>
  <div class="mega">ПРОГРЕСС</div>
  <div class="sec"><span class="k">СИЛА</span></div>
  <div class="big2"><b class="m acc">+8%</b><span class="soft">за 6 недель</span></div>
  <svg class="chart" viewBox="0 0 340 90"><path d="M0 70 L40 68 L80 62 L120 64 L160 52 L200 47 L240 40 L280 33 L320 24" fill="none" stroke="currentColor" stroke-width="2.5"/><circle cx="320" cy="24" r="5" class="dot"/><path d="M0 89H340" stroke="#ddd"/></svg>
  <table class="ex">
    <tr><td>Жим лёжа</td><td class="m b">92,5 кг</td><td class="up">+5</td></tr>
    <tr><td>Присед</td><td class="m b">120 кг</td><td class="up">+7,5</td></tr>
  </table>
  <div class="sec"><span class="k">БЕГ</span></div>
  <div class="big2"><b class="m">5:52</b><span class="soft">на 1500 м сейчас, было 6:05</span></div>
  <div class="weeks">${[8,12,10,14,9,15,17,13].map((v,i)=>`<i style="height:${v*3.2}px" class="${i==7?'cur':''}"></i>`).join('')}</div>
  <div class="k soft">КИЛОМЕТРЫ ЗА 8 НЕДЕЛЬ</div>
  ${nav('progress')}
</div>`;

const css = `
.mood{border-top:3px solid var(--fg);padding-top:10px;display:flex;flex-direction:column;gap:2px}.mood b{font-size:20px;font-weight:800;letter-spacing:-.4px}.mood span{font-size:13px;color:var(--soft)}
.more{font-size:13px;color:var(--soft)}
.plain{font-size:15px;color:var(--soft)}
.goalhead{display:flex;justify-content:space-between;align-items:baseline;font-size:15px;font-weight:700}.goalhead .m{font-size:13px;color:var(--soft);font-weight:600}.goalhead b{font-size:15px}
.big2 span{font-weight:600}

*{box-sizing:border-box;margin:0;padding:0}
body{background:#cfcfcf;padding:24px;font-family:Inter,sans-serif}
.phone{--bg:#fff;--fg:#111;--soft:#8a8a8a;--line:#e3e3e3;--acc:#ff4d12;background:var(--bg);color:var(--fg);width:390px;height:844px;border-radius:44px;overflow:hidden;position:relative;padding:12px 22px 0;display:flex;flex-direction:column;gap:10px;box-shadow:0 20px 50px rgba(0,0,0,.25)}
.phone.dark{--bg:#0d0d0d;--fg:#f4f4f2;--soft:#7c7c7c;--line:#262626}
.m{font-variant-numeric:tabular-nums;font-feature-settings:"tnum"}
.status{display:flex;justify-content:space-between;font-size:13px;font-weight:600}.sys{display:flex;gap:5px;align-items:center}.sys svg{width:18px;height:10px;fill:currentColor}
.top{display:flex;justify-content:space-between;align-items:center;margin-top:6px}
.kicker,.k{font-size:11px;font-weight:700;letter-spacing:1.4px}.soft{color:var(--soft)}.acc{color:var(--acc)}
.ava{width:34px;height:34px;border-radius:50%;background:var(--fg);color:var(--bg);display:flex;align-items:center;justify-content:center;font-weight:800;font-size:14px}
.mega{font-family:'Inter Display',Inter;font-weight:900;font-size:56px;letter-spacing:-2.5px;line-height:.9;margin-top:2px}
.mega2{font-family:'Inter Display',Inter;font-weight:900;font-size:64px;letter-spacing:-2.5px;line-height:1;margin-top:6px}
.stats{display:flex;border-top:3px solid var(--fg);padding-top:8px}
.stats div{flex:1;display:flex;flex-direction:column;gap:1px;padding-right:8px}.stats div+div{border-left:1px solid var(--line);padding-left:10px}
.stats b{font-size:26px;font-weight:800;letter-spacing:-.8px}.stats i{font-style:normal;font-size:11px;color:var(--soft)}
.sec{display:flex;gap:10px;align-items:baseline;border-top:1px solid var(--line);padding-top:10px;margin-top:4px}.n{font-size:11px;font-weight:800;color:var(--acc)}
.title{font-family:'Inter Display',Inter;font-weight:900;font-size:40px;letter-spacing:-1.5px;line-height:.9}
table{border-collapse:collapse;width:100%}.ex td{padding:6px 0;font-size:14px;border-bottom:1px solid var(--line)}.ex td.m{text-align:right;width:1%;white-space:nowrap;padding-left:14px;color:var(--soft)}.ex td.b{color:var(--fg);font-weight:800;font-size:15px}
.ex td.up{text-align:right;width:44px;font-size:12px;font-weight:700;color:var(--acc)}
.go{display:flex;justify-content:space-between;align-items:center;background:var(--fg);color:var(--bg);border-radius:999px;padding:14px 22px;font-weight:800;letter-spacing:1.5px;font-size:14px;margin-top:4px}
.go svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:2.2}
.line{display:flex;justify-content:space-between;align-items:baseline;font-size:16px}.line .m{color:var(--soft);font-size:13px}
.goal{margin-top:14px}.track{position:relative;height:3px;background:var(--line);margin:10px 4px}
.track i{position:absolute;top:-4px;width:11px;height:11px;border-radius:50%;background:var(--bg);border:2px solid var(--fg)}
.track .was{left:0}.track .now{left:62%;background:var(--acc);border-color:var(--acc)}.track .end{right:0;background:var(--fg)}
.track:before{content:"";position:absolute;left:0;top:0;height:3px;width:62%;background:var(--fg)}
.marks{display:flex;justify-content:space-between;font-size:13px;font-weight:800}.marks span:nth-child(2){margin-left:24%}.marks em{font-style:normal;font-weight:500;font-size:10px;color:var(--soft);letter-spacing:.5px}
.nav{margin:auto -22px 0;display:flex;justify-content:space-around;padding:10px 6px 24px;border-top:1px solid var(--line)}
.nav div{display:flex;flex-direction:column;align-items:center;gap:3px;font-size:10.5px;color:var(--soft);font-weight:600}
.nav svg{width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linejoin:round;stroke-linecap:round}
.nav .on{color:var(--fg)}.nav .on svg{stroke-width:2.4}
.timer{font-size:15px;font-weight:800}
.sets td{padding:9px 0;border-bottom:1px solid var(--line);font-size:15px}.sets td:first-child{width:28px;color:var(--soft);font-size:12px;font-weight:700}
.sets .done{color:var(--soft)}.sets .done td:last-child{text-align:right;font-size:12px}.sets td:last-child{text-align:right}
.sets .cur td{border-bottom:3px solid var(--fg)}.sets .big{font-size:30px;font-weight:900;letter-spacing:-1px}
.rest{display:flex;align-items:center;gap:10px}.rest b{font-size:22px;font-weight:800}.rbar{flex:1;height:3px;background:var(--line)}.rbar i{display:block;height:3px;width:44%;background:var(--acc)}
.next{display:flex;gap:10px;align-items:baseline;border-top:1px solid var(--line);padding-top:10px;font-size:14px}.next .m{margin-left:auto;color:var(--soft)}
.clock{font-family:'Inter Display',Inter;font-weight:900;font-size:120px;letter-spacing:-6px;line-height:.9;margin-top:12px}
.laps{display:flex;border-top:3px solid var(--fg);padding-top:8px;margin-top:6px}.laps div{flex:1;display:flex;flex-direction:column}.laps div+div{border-left:1px solid var(--line);padding-left:10px}
.laps b{font-size:30px;font-weight:900;letter-spacing:-1px}.laps small{font-size:16px;color:var(--soft)}.laps i{font-style:normal;font-size:11px;color:var(--soft)}
.lapbars{display:flex;gap:8px;align-items:flex-end;height:110px;margin-top:8px}.lapbars div{flex:1;display:flex;flex-direction:column;align-items:center;gap:4px;justify-content:flex-end;height:100%}
.lapbars i{display:block;width:100%;background:var(--fg)}.lapbars div:nth-child(4) i{background:var(--acc)}.lapbars span{font-size:11px;font-weight:700}.lapbars .ghost i{height:6px;background:var(--line)}.lapbars .ghost span{color:var(--soft)}
.lapbtn{margin:auto auto 0;width:190px;height:190px;border-radius:50%;background:var(--fg);color:var(--bg);display:flex;align-items:center;justify-content:center;font-family:'Inter Display',Inter;font-weight:900;font-size:40px;letter-spacing:1px}
.hint{text-align:center;font-size:12px;color:var(--soft);padding:12px 0 30px}
.big2{display:flex;align-items:baseline;gap:12px}.big2 b{font-size:44px;font-weight:900;letter-spacing:-1.5px}.big2 span{font-size:13px;font-weight:700}
.chart{width:100%;height:80px;color:var(--fg)}.chart .dot{fill:var(--acc)}
.weeks{display:flex;gap:8px;align-items:flex-end;height:60px}.weeks i{flex:1;background:var(--fg)}.weeks i.cur{background:var(--acc)}
`;

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 438, height: 892 }, deviceScaleFactor: 2 });
  for (const [name, html] of [['1-segodnya', today(false)], ['2-trenirovka', workout], ['3-stadion', run], ['4-progress', progress], ['5-segodnya-temnyj', today(true)]]) {
    await p.setContent(`<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body>${html}</body></html>`);
    await p.locator('.phone').screenshot({ path: `${out}/${name}.png` });
    console.log(name);
  }
  await b.close();
})();

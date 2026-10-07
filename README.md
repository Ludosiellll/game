# Песнь небесного кита — прототип

Idle-кликер для Android: огромный кит летит над облаками, на его спине растёт сад, тап по небу — нота песни, серое небо возвращает цвет. Кит взрослеет от китёнка до древнего божества, стражи маршрута и Тень кита вызывают его на бой. Концепция и решения — в репозитории `game-notes` (`10_Project/Game_Concept.md`, `10_Project/Whale_Ages_and_Bosses.md`).

Статус: играбельный прототип в стиле «игрушечное 3D» — пять возрастов кита, бои со стражами и Тенью кита, черты кита; загрузочный экран и ролик-предыстория в стиле китайской туши. Реклама — заглушка, покупок нет, звука пока нет.

## Стек

- Flutter 3.47.6 (Dart 3.13.5), отрисовка Impeller на Android.
- Графика: спрайты WebP, отрендеренные нашим кодом в Blender (`tool/art`), собираются в сцену `CustomPainter`-ом по слоям (кит дышит, моргает, машет плавниками; сад на спине растёт). Интерфейс — «сочные» виджеты из `lib/ui/juicy.dart`.
- Сохранение: `shared_preferences` (на вебе — localStorage, ключ `skywhale.save.v1`).
- Шрифт Nunito (SIL OFL 1.1), лицензия в `assets/fonts/OFL-Nunito.txt`, регистрируется в `LicenseRegistry`.

## Команды

```bash
flutter pub get
flutter analyze                 # должно быть «No issues found!»
flutter test                    # правила экономики и боёв, сохранение, контроллер, вёрстка экранов
dart run tool/simulate.dart 72  # симулятор баланса: 3 профиля игроков на 72 часа
flutter build web --release     # веб-сборка для быстрой проверки
flutter build apk --release --split-per-abi   # APK (подпись debug-ключом — только для тестов)
```

Визуальная проверка на размерах телефонов (Playwright + Chromium):

```bash
flutter build web --release
(cd build/web && python3 -m http.server 8765) &
cd tool/visual_qa && npm install && node shots.mjs   # скриншоты в tool/visual_qa/out/
```

В контейнерах без видеокарты Chromium запускается с `--enable-unsafe-swiftshader`, а локаль браузера задаётся `ru-RU`: системная локаль `C` ломает старт Flutter web («Incorrect locale information provided»).

Арт в стиле «игрушечное 3D» (утверждён, без контура): модели строятся кодом в Blender 4.5 LTS, рендер Cycles на CPU с прозрачным фоном.

```bash
B=/path/to/blender   # Blender 4.5 LTS, достаточно версии без интерфейса
$B -b --python tool/art/toy_scene.py -- scene out/scene.png --size=1000x1250 --samples=64   # кит с садом
$B -b --python tool/art/toy_scene.py -- icons out/icons    # иконки предметов (цветок, куст, улей…, птица)
$B -b --python tool/art/toy_scene.py -- clouds out/clouds  # облака
$B -b --python tool/art/toy_scene.py -- scene out/stage5.png --stage=5 --ortho=11   # возраст 1..5 в общем масштабе
$B -b --python tool/art/toy_scene.py -- boss out/boss.png  # страж Пояса бурь
# --yaw=112 — поворот кита, --pitch/--tilt — камера и наклон кита (сцена боя), --outline — контур, --flat — крупные грани
```

Кадр сцены 1000×1250 на 4 ядрах рендерится примерно 2–3 минуты, иконка — несколько секунд.

Спрайты для игры (`assets/sprites/`) — экспорт по слоям и упаковка:

```bash
RAW=/tmp/sprites_raw
for st in 1 2 3 4 5; do   # кит по возрастам: тело с палубой, плавники, хвост, моргание, рот, места сада
  $B -b --python tool/art/toy_scene.py -- sprites $RAW/age$st --stage=$st --size=1024x1024 --samples=48
done
for kind in items birds bosses clouds icons; do   # постройки сада, птица (3 кадра), стражи, облака, иконки
  $B -b --python tool/art/toy_scene.py -- $kind $RAW/$kind --samples=40
done
python3 tool/art/pack_sprites.py $RAW assets/sprites   # обрезка, патчи моргания и рта, WebP, manifest.json
```

Весь экспорт занимает около 20 минут на 4 ядрах; упакованные спрайты — около 1,2 МБ.

Ролик-предыстория в стиле китайской живописи тушью (`assets/video/intro.mp4`, ~41 с, показывается при первом запуске, повтор — «Ещё» → «Смотреть предысторию»): Blender рисует китов без сада в два прохода — объём и линии кисти, остальное (бумага, горы, облака, Мгла, печать, подписи, акварельный разлив цвета) дорисовывает `tool/art/ink_frames.py` на numpy.

```bash
$B -b --python tool/art/toy_scene.py -- ink $RAW/mother --stage=6 --yaw=66 --pitch=99 --tilt=5 --size=2000x1100   # мать-кит из легенды
$B -b --python tool/art/toy_scene.py -- ink $RAW/calf --stage=1 --traits= --yaw=100 --tilt=8 --pitch=74 --size=900x700
$B -b --python tool/art/toy_scene.py -- ink $RAW/stone1 --stage=6 --traits=noface,nofins --yaw=48 --pitch=80 --size=1400x700
$B -b --python tool/art/toy_scene.py -- ink $RAW/stone2 --stage=6 --traits=noface,nofins --yaw=236 --pitch=80 --size=1400x700
python3 tool/art/ink_frames.py $RAW FONTS out/             # пробные кадры; FONTS: MarckScript, MaShanZheng (SIL OFL); нужны numpy, scipy, Pillow
python3 tool/art/ink_frames.py $RAW FONTS assets/intro loading   # фон загрузочного экрана
python3 tool/art/ink_intro.py $RAW FONTS frames/ --workers=4     # ~980 кадров 1080×1920, около 10 минут на 4 ядрах
ffmpeg -framerate 24 -i frames/%05d.jpg -vf scale=720:1280:flags=lanczos -c:v libx264 -profile:v main -preset slow \
  -crf 24 -pix_fmt yuv420p -movflags +faststart -an assets/video/intro.mp4
```

Пробный кадр стиля (главный экран и магазин, собраны из этих рендеров) — `docs/art/style_frame_2026-10-07.jpg`, кит без контура и с контуром — `docs/art/outline_ab_2026-10-07.jpg`. Возрасты кита (китёнок → божество) — `docs/art/whale_ages_2026-10-07.jpg`, макет боя со стражем — `docs/art/boss_fight_2026-10-07.jpg`.

## Устройство кода

```
lib/
  core/        правила без Flutter: big.dart (числа больше double), economy.dart,
               content.dart (баланс), game_state.dart, save.dart, format.dart, story.dart
  app/         GameController (время, сохранение, офлайн, события), SaveStore, AdService
  core/fight.dart  бой: урон, крещендо, криты, тёмные ноты, щит, Песнь кита
  art/         SpriteAtlas: загрузка спрайтов и manifest.json
  scene/       сцена: небо, облака, кит по слоям, сад на спине, птицы, эффекты тапа;
               портрет кита с садом (WhalePortrait)
  ui/          загрузочный экран и ролик (intro_screen.dart), главный экран, HUD, листы Сад/Кит/Путь/Ещё, экран боя, окна,
               сочные виджеты (juicy.dart)
tool/
  simulate.dart   бот-игрок для настройки баланса
  visual_qa/      скриншоты веб-сборки
  art/            модели и рендер арта в Blender (toy_scene.py), упаковка спрайтов (pack_sprites.py)
test/             тесты правил на отдельном тестовом наборе чисел (test_content.dart)
```

Правила экономики тестируются на `test/test_content.dart`, а боевой баланс живёт в `defaultContent` (`lib/core/content.dart`). Менять баланс — только с прогоном симулятора.

## Баланс (симулятор, 2026-10-07)

`dart run tool/simulate.dart 504 [active|casual|lazy]` — три недели календаря.

| Событие | Активный (без перерывов) | Обычный (8 × 10 мин в день) | Ленивый (4 × 5 мин в день) |
| --- | --- | --- | --- |
| Юный кит (1-я песнь + Тень) | 13 мин игры | 7 мин игры | 2-й день |
| Взрослый кит | 11 ч | 14 ч с начала | 4-й день |
| Древний кит | 7-й день | 5-й день | 14-й день |
| Небесный кит (божество) | 16-й день | 13-й день | после 21 дня |
| Стражи: попыток на победу / победный бой | 1.0 / 15–21 с | 1.0 / 15–19 с | 3–8 / ~31 с |

Бот в бою играет той же автоигрой, что и подсказка силы (`Fight.autoPlay`): активный — 6 тапов/с и 90% нот, обычный — 4 тапа/с, 60% нот и +15 с за рекламу, ленивый — 2 тапа/с и 30% нот. На испытание бот идёт, когда подсказка силы ≥ 100%.

Подсказка «Сила песни» — прогон копии боя обычной игрой (4 тапа/с, 60% нот, щит, Песнь кита): 100% — победа впритык, 200% — к середине боя. Прежняя формула «сад + тапы × крещендо 2» занижала силу почти втрое — показывала стражу 55% при победе за 26 с из 40.

Здоровье стража = доход в момент его появления × 40 с × коэффициент (3.75, 4.5, 5.2): пока игрок растит сад, страж не крепнет. Тень кита — пик дохода прошлого цикла × 60 с × 0.8. Годы: 1 за 30 минут (и офлайн в пределах лимита), +100 за Долгую песнь, если с прошлой прошло ≥ 3 ч (иначе доля), +5–12 за стражей.

## Ассеты и лицензии

| Файл | Автор | Лицензия | Откуда |
| --- | --- | --- | --- |
| `assets/fonts/Nunito-*.ttf` (статические начертания из вариативного шрифта, fontTools instancer; `Nunito-Black` — ось wght 1000) | The Nunito Project Authors | SIL OFL 1.1 | github.com/google/fonts, `ofl/nunito` |
| `assets/fonts/MarckScript-Regular.ttf` (надписи загрузки и ролика) | Denis Masharov, Marck Fogel | SIL OFL 1.1 | github.com/google/fonts, `ofl/marckscript` |
| `assets/video/intro.mp4`, `assets/intro/loading.jpg` (ролик и фон загрузки в стиле туши; в кадр вписаны глифы Marck Script и Ma Shan Zheng, оба SIL OFL) | наш код: `tool/art/toy_scene.py` (режим `ink`) → `ink_frames.py` / `ink_intro.py` | собственные | рендер из кода |
| `assets/sprites/*.webp`, `manifest.json` (кит пяти возрастов, постройки, птицы, облака, боссы, иконки) | наш код: `tool/art/toy_scene.py` (Blender 4.5 LTS) → `tool/art/pack_sprites.py` | собственные | рендер из кода, сторонних моделей и текстур нет |

3D-арт (`tool/art/toy_scene.py` и его рендеры) построен из примитивов нашим кодом — сторонних моделей и текстур в нём нет.

Чужие скриншоты и картинки в репозиторий не кладём. Новые ассеты добавлять только с записью в эту таблицу.

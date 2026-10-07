# Песнь небесного кита — прототип

Idle-кликер для Android: огромный кит летит над облаками, на его спине растёт сад, тап по небу — нота песни, серое небо возвращает цвет, престиж будит уснувших китов стаи. Концепция и решения — в репозитории `game-notes` (`10_Project/Game_Concept.md`).

Статус: серый прототип плюс первая версия графики, нарисованной кодом. Реклама — заглушка, покупок нет. Стиль графики пересматривается.

## Стек

- Flutter 3.47.6 (Dart 3.13.5), отрисовка Impeller на Android.
- Графика целиком кодом: `CustomPainter`, градиенты, кривые; без растровых картинок.
- Сохранение: `shared_preferences` (на вебе — localStorage, ключ `skywhale.save.v1`).
- Шрифт Nunito (SIL OFL 1.1), лицензия в `assets/fonts/OFL-Nunito.txt`, регистрируется в `LicenseRegistry`.

## Команды

```bash
flutter pub get
flutter analyze                 # должно быть «No issues found!»
flutter test                    # правила экономики, сохранение, контроллер, цвета
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

Арт в стиле «игрушечное 3D» (проба, ждёт утверждения): модели строятся кодом в Blender 4.5 LTS, рендер Cycles на CPU с прозрачным фоном, игра получает готовые PNG.

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

Пробный кадр стиля (главный экран и магазин, собраны из этих рендеров) — `docs/art/style_frame_2026-10-07.jpg`, кит без контура и с контуром — `docs/art/outline_ab_2026-10-07.jpg`. Возрасты кита (китёнок → божество) — `docs/art/whale_ages_2026-10-07.jpg`, макет боя со стражем — `docs/art/boss_fight_2026-10-07.jpg`.

## Устройство кода

```
lib/
  core/        правила без Flutter: big.dart (числа больше double), economy.dart,
               content.dart (баланс), game_state.dart, save.dart, format.dart, story.dart
  app/         GameController (время, сохранение, офлайн, события), SaveStore, AdService
  scene/       сцена: кит сверху, сад, птицы, облака, эффекты тапа
  ui/          экран, верхняя панель, вкладки Сад/Песнь/Ещё, окна, палитра (OKLab)
tool/
  simulate.dart   бот-игрок для настройки баланса
  visual_qa/      скриншоты веб-сборки
  art/            модели и рендер арта в Blender (toy_scene.py)
test/             тесты правил на отдельном тестовом наборе чисел (test_content.dart)
```

Правила экономики тестируются на `test/test_content.dart`, а боевой баланс живёт в `defaultContent` (`lib/core/content.dart`). Менять баланс — только с прогоном симулятора.

## Баланс (симулятор, 2026-10-07)

| Событие | Активный (без перерывов) | Обычный (8 × 10 мин в день) | Ленивый (4 × 5 мин в день) |
| --- | --- | --- | --- |
| Первый перелёт | 12 мин игры | 7 мин | 5 мин |
| 1-я Долгая песнь (первый кит) | 1 ч 22 мин | 4 ч с начала | 14 ч |
| 3-я песнь (второй кит) | 4 ч 13 мин | 12 ч | 2-й день |
| 6-я песнь (третий кит) | 8 ч | 2-й день | 5-й день |

## Ассеты и лицензии

| Файл | Автор | Лицензия | Откуда |
| --- | --- | --- | --- |
| `assets/fonts/Nunito-*.ttf` (статические начертания из вариативного шрифта, fontTools instancer) | The Nunito Project Authors | SIL OFL 1.1 | github.com/google/fonts, `ofl/nunito` |

3D-арт (`tool/art/toy_scene.py` и его рендеры) построен из примитивов нашим кодом — сторонних моделей и текстур в нём нет.

Чужие скриншоты и картинки в репозиторий не кладём. Новые ассеты добавлять только с записью в эту таблицу.

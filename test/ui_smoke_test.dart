import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/app/game_controller.dart';
import 'package:skywhale/app/save_store.dart';
import 'package:skywhale/art/sprite_atlas.dart';
import 'package:skywhale/core/economy.dart';
import 'package:skywhale/core/save.dart';
import 'package:skywhale/ui/fight_screen.dart';
import 'package:skywhale/ui/game_screen.dart';
import 'package:skywhale/ui/juicy.dart';

const start = 1700000000000;

/// Настоящие шрифты — иначе тестовый шрифт шире и даёт ложные переполнения.
Future<void> _loadFonts() async {
  final loader = FontLoader('Nunito');
  for (final w in ['Regular', 'SemiBold', 'Bold', 'ExtraBold', 'Black']) {
    loader.addFont(rootBundle.load('assets/fonts/Nunito-$w.ttf'));
  }
  await loader.load();
}

/// Сохранение «молодой кит во втором регионе». С [guardianReady] сад дорос
/// до цели региона, и страж «Пояса бурь» ждёт боя.
Future<(GameController, SpriteAtlas)> _setUp(WidgetTester tester, {bool guardianReady = false}) async {
  tester.view.physicalSize = const Size(1080, 2340);
  tester.view.devicePixelRatio = 2.75;
  addTearDown(tester.view.reset);
  final saved = newGame(start)
    ..age = 1
    ..years = 520
    ..songs = 2
    ..region = 1
    ..guardiansBeaten = 1
    ..scales = 9
    ..birds = 6
    ..generators.addAll({'flower': 60, 'bush': 50, 'hive': 30, 'mill': guardianReady ? 24 : 12});
  final c = GameController(store: MemorySaveStore(encodeSave(saved)), now: () => start);
  late SpriteAtlas atlas;
  await tester.runAsync(() async {
    await _loadFonts();
    atlas = await SpriteAtlas.load();
    await c.init();
  });
  return (c, atlas);
}

Widget _app(GameController c, SpriteAtlas atlas) => MaterialApp(
  theme: J.theme(),
  home: GameScreen(controller: c, atlas: atlas),
);

void main() {
  testWidgets('главный экран и все листы собираются на телефоне без ошибок', (tester) async {
    final (c, atlas) = await _setUp(tester);
    await tester.pumpWidget(_app(c, atlas));
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('Пояс бурь'), findsWidgets);
    for (final label in ['Сад', 'Кит', 'Путь', 'Ещё']) {
      await tester.tap(find.byKey(ValueKey('nav-$label')));
      await tester.pump(const Duration(milliseconds: 700));
      expect(tester.takeException(), isNull, reason: 'лист «$label»');
      Navigator.of(tester.element(find.byType(GameScreen))).pop();
      await tester.pump(const Duration(milliseconds: 700));
    }
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('бой со стражем: экран боя собирается, тапы бьют', (tester) async {
    final (c, atlas) = await _setUp(tester, guardianReady: true);
    expect(c.canFightGuardian, isTrue);
    await tester.pumpWidget(_app(c, atlas));
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(find.byKey(const ValueKey('event-fight')));
    // Первый кадр новый маршрут строится за кадром (для героев), второй — на экране.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byType(FightScreen), findsOneWidget);
    // Заставка идёт по кадрам: шаг кадра ограничен 0,1 с, как на телефоне.
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    final hp = c.fight!.hp.toDouble();
    await tester.tapAt(const Offset(200, 260));
    await tester.pump(const Duration(milliseconds: 100));
    expect(c.fight!.hp.toDouble(), lessThan(hp));
    expect(tester.takeException(), isNull);
    c.closeFight();
    await tester.pumpWidget(const SizedBox());
  });
}

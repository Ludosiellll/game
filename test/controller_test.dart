import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/app/game_controller.dart';
import 'package:skywhale/app/save_store.dart';
import 'package:skywhale/core/economy.dart';
import 'package:skywhale/core/fight.dart';
import 'package:skywhale/core/save.dart';

import 'test_content.dart';

void main() {
  const start = 1700000000000;
  late int clock;
  int now() => clock;

  setUp(() => clock = start);

  GameController make(MemorySaveStore store) => GameController(store: store, now: now, content: testContent);

  group('контроллер игры', () {
    test('при первом запуске начинается новая игра без окна офлайна', () async {
      final c = make(MemorySaveStore());
      await c.init();
      expect(c.state.echo.isZero, isTrue);
      expect(c.pendingOffline, isNull);
    });

    test('предыстория показывается при первом запуске один раз', () async {
      final store = MemorySaveStore();
      final c = make(store);
      await c.init();
      expect(c.shouldShowIntro, isTrue);
      await c.markIntroSeen();
      expect(c.shouldShowIntro, isFalse);
      final again = make(store);
      await again.init();
      expect(again.shouldShowIntro, isFalse);
    });

    test('после часа отсутствия начисляет офлайн и показывает окно', () async {
      final saved = newGame(start, content: testContent)..generators['flower'] = 10;
      final store = MemorySaveStore(encodeSave(saved));
      clock = start + 3600 * 1000;
      final c = make(store);
      await c.init();
      expect(c.pendingOffline, isNotNull);
      expect(c.pendingOffline!.earned.toDouble(), closeTo(14400, 1e-6));
    });

    test('отлучка меньше 5 минут даёт доход, но окно не показывается', () async {
      final saved = newGame(start, content: testContent)..generators['flower'] = 10;
      clock = start + 240 * 1000;
      final c = make(MemorySaveStore(encodeSave(saved)));
      await c.init();
      expect(c.pendingOffline, isNull);
      expect(c.state.echo.toDouble(), closeTo(960, 1e-6));
    });

    test('«забрать ×2» после рекламы удваивает офлайн-доход', () async {
      final saved = newGame(start, content: testContent)..generators['flower'] = 10;
      clock = start + 3600 * 1000;
      final c = make(MemorySaveStore(encodeSave(saved)));
      await c.init();
      await c.claimOffline(doubled: true);
      expect(c.state.echo.toDouble(), closeTo(28800, 1e-6));
      expect(c.pendingOffline, isNull);
    });

    test('покупка ×10 и «Макс.»', () async {
      final c = make(MemorySaveStore());
      await c.init();
      c.debugAddEcho(1000);
      c.buyMode = BuyMode.ten;
      expect(c.buyGenerator('flower'), isTrue);
      expect(c.state.owned('flower'), 10);
      c.buyMode = BuyMode.max;
      final before = c.state.owned('flower');
      expect(c.buyGenerator('flower'), isTrue);
      expect(c.state.owned('flower'), greaterThan(before));
      expect(maxAffordable(c.state, 'flower'), 0);
    });

    test('сохранение записывается в хранилище', () async {
      final store = MemorySaveStore();
      final c = make(store);
      await c.init();
      c.debugAddEcho(500);
      await c.saveNow();
      final restored = decodeSave(store.data!, content: testContent);
      expect(restored!.echo.toDouble(), 500);
    });

    test('тестовая реклама включает буст ×2', () async {
      final c = make(MemorySaveStore());
      await c.init();
      c.state.generators['flower'] = 1;
      await c.watchBoostAd();
      expect(incomePerSecond(c.state, clock).toDouble(), closeTo(0.8, 1e-9));
    });

    test('кадр игрового цикла начисляет доход за прошедшее время', () async {
      final c = make(MemorySaveStore());
      await c.init();
      c.state.generators['flower'] = 1;
      c.onFrame(0.5);
      c.onFrame(0.5);
      expect(c.state.echo.toDouble(), closeTo(0.4, 1e-9));
    });
  });

  group('бои в контроллере', () {
    Future<GameController> ready() async {
      final saved = newGame(start, content: testContent)
        ..generators['flower'] = 30
        ..generators['bush'] = 10;
      final c = make(MemorySaveStore(encodeSave(saved)));
      await c.init();
      return c;
    }

    test('страж появляется, когда регион восстановлен; бой начинается и идёт по кадрам', () async {
      final c = await ready();
      expect(c.canFightGuardian, isTrue);
      expect(c.startGuardianFight(), isTrue);
      expect(c.startGuardianFight(), isFalse, reason: 'второй бой поверх первого не начинается');
      final hp = c.fight!.hp.toDouble();
      c.onFrame(1);
      expect(c.fight!.hp.toDouble(), lessThan(hp));
      expect(c.fightTap(), isNotNull);
    });

    test('победа применяет награду один раз и шлёт событие', () async {
      final c = await ready();
      final events = <GameEvent>[];
      final sub = c.events.listen(events.add);
      c.startGuardianFight();
      c.fight!.hp = c.fight!.hp.mulNum(1e-9);
      c.fightTap();
      c.fightTap();
      await Future<void>.delayed(Duration.zero);
      expect(c.fight!.outcome, FightOutcome.won);
      expect(c.state.scales, 1);
      expect(c.lastReward, isNotNull);
      expect(events.whereType<FightEndEvent>().single.won, isTrue);
      c.closeFight();
      expect(c.fight, isNull);
      expect(c.state.scales, 1);
      expect(canMigrate(c.state), isTrue);
      await sub.cancel();
    });

    test('время вышло: +15 с за рекламу продолжают бой; отступление — пауза перед повтором', () async {
      final c = await ready();
      c.startGuardianFight();
      for (var i = 0; i < 41; i++) {
        c.onFrame(1);
      }
      expect(c.fight!.outcome, FightOutcome.lost);
      expect(await c.fightAddTime(), isTrue);
      expect(c.fight!.outcome, FightOutcome.ongoing);
      for (var i = 0; i < 16; i++) {
        c.onFrame(1);
      }
      expect(c.fight!.outcome, FightOutcome.lost);
      expect(await c.fightAddTime(), isFalse);
      c.retreatFight();
      expect(c.fight, isNull);
      expect(c.fightCooldownLeft, closeTo(60, 1e-9));
      expect(c.startGuardianFight(), isFalse);
      expect(await c.skipFightCooldownWithAd(), isTrue);
      expect(c.startGuardianFight(), isTrue);
    });

    test('черта кита покупается за чешуйки', () async {
      final c = await ready();
      c.state.scales = 2;
      expect(c.buyTrait('voice'), isTrue);
      expect(c.state.traitLevel('voice'), 1);
      expect(c.buyTrait('voice'), isFalse);
    });
  });
}

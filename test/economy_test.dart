import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/core/big.dart';
import 'package:skywhale/core/economy.dart';

import 'test_content.dart';

import 'package:skywhale/core/game_state.dart';

const now = 1700000000000; // мс

void main() {
  group('тап и покупка', () {
    test('новая игра начинается без Эха, тап даёт 1 Эхо', () {
      final s = newGame(now, content: testContent);
      expect(s.echo.toDouble(), 0);
      tap(s, now);
      expect(s.echo.toDouble(), 1);
    });

    test('первый цветок стоит 10 Эха', () {
      final s = newGame(now, content: testContent);
      for (var i = 0; i < 10; i++) {
        tap(s, now);
      }
      expect(buyGenerator(s, 'flower', 1), isTrue);
      expect(s.owned('flower'), 1);
      expect(s.echo.toDouble(), closeTo(0, 1e-9));
    });

    test('без нужной суммы покупка не проходит и ничего не меняет', () {
      final s = newGame(now, content: testContent);
      for (var i = 0; i < 9; i++) {
        tap(s, now);
      }
      expect(buyGenerator(s, 'flower', 1), isFalse);
      expect(s.owned('flower'), 0);
      expect(s.echo.toDouble(), 9);
    });

    test('второй цветок дороже первого на 10%', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 1;
      expect(costOf(s, 'flower', 1).toDouble(), closeTo(11, 1e-9));
    });

    test('10 цветов с нуля стоят 159.374 Эха (сумма прогрессии)', () {
      final s = newGame(now, content: testContent);
      expect(costOf(s, 'flower', 10).toDouble(), closeTo(159.374, 1e-3));
    });

    test('на 100 Эха можно купить 7 цветов, но не 8', () {
      final s = newGame(now, content: testContent)..echo = Big.from(100);
      expect(maxAffordable(s, 'flower'), 7);
    });

    test('растения следующих регионов в начале закрыты', () {
      final s = newGame(now, content: testContent)..echo = Big.from(1e9);
      expect(isUnlocked(s, 'hive'), isFalse);
      expect(buyGenerator(s, 'hive', 1), isFalse);
    });
  });

  group('доход', () {
    test('один цветок даёт 0.4 Эха в секунду', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 1;
      expect(incomePerSecond(s, now).toDouble(), closeTo(0.4, 1e-9));
    });

    test('за 10 секунд один цветок приносит 4 Эха', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 1;
      tick(s, 10, now);
      expect(s.echo.toDouble(), closeTo(4, 1e-9));
    });

    test('25 цветов производят вдвое больше, чем 25 × 0.4', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 25;
      expect(productionPerSecond(s, now).toDouble(), closeTo(20, 1e-9));
    });

    test('тап добавляет 3% дохода в секунду: при 4 Эха/с тап даёт 1.12', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      expect(tapValue(s, now).toDouble(), closeTo(1.12, 1e-9));
    });

    test('птица поёт сама: одна птица за 10 секунд даёт 5 Эха', () {
      final s = newGame(now, content: testContent)..birds = 1;
      tick(s, 10, now);
      expect(s.echo.toDouble(), closeTo(5, 1e-9));
    });

    test('птица стоит 25 Эха и дорожает на 15%', () {
      final s = newGame(now, content: testContent)..echo = Big.from(25);
      expect(buyBirds(s, 1), isTrue);
      expect(s.birds, 1);
      expect(birdCost(s, 1).toDouble(), closeTo(28.75, 1e-9));
    });

    test('буст ×2 удваивает доход на 10 минут', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 1;
      addBoost(s, now);
      expect(incomePerSecond(s, now + 60 * 1000).toDouble(), closeTo(0.8, 1e-9));
      expect(incomePerSecond(s, now + 601 * 1000).toDouble(), closeTo(0.4, 1e-9));
    });

    test('буст копится до часа, но не больше', () {
      final s = newGame(now, content: testContent);
      for (var i = 0; i < 10; i++) {
        addBoost(s, now);
      }
      expect(s.boostUntilMs - now, 60 * 60 * 1000);
    });
  });

  group('регионы и миграция', () {
    test('цвет неба считается по саду: 30 цветов и 10 кустов восстанавливают первый регион', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 30
        ..generators['bush'] = 10;
      expect(bloom(s), closeTo(60, 1e-9));
      expect(regionProgress(s), closeTo(1, 1e-9));
      expect(guardianReady(s), isTrue, reason: 'дальше пускает только побеждённый страж');
      s.guardiansBeaten = 1;
      expect(canMigrate(s), isTrue);
    });

    test('без полного цвета лететь дальше нельзя', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 59;
      expect(regionProgress(s), closeTo(59 / 60, 1e-9));
      expect(canMigrate(s), isFalse);
      expect(migrate(s), isFalse);
      expect(s.region, 0);
    });

    test('миграция открывает новые растения и удваивает доход', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 30
        ..generators['bush'] = 10
        ..guardiansBeaten = 1;
      final before = productionPerSecond(s, now).toDouble();
      expect(migrate(s), isTrue);
      expect(s.region, 1);
      expect(isUnlocked(s, 'hive'), isTrue);
      expect(productionPerSecond(s, now).toDouble(), closeTo(before * 2, 1e-9));
    });

    test('прогресс второго региона считается от цели первого', () {
      final s = newGame(now, content: testContent)
        ..region = 1
        ..generators['flower'] = 230; // 230 цвета; цели 60 → 400
      expect(regionProgress(s), closeTo(0.5, 1e-9));
    });

    test('из последнего региона маршрута лететь дальше некуда', () {
      final s = newGame(now, content: testContent)
        ..region = 2
        ..generators['observatory'] = 100;
      expect(regionProgress(s), closeTo(1, 1e-9));
      expect(canMigrate(s), isFalse);
    });
  });

  group('Долгая песнь (престиж)', () {
    GameState readyToSing() => newGame(now, content: testContent)
      ..region = 2
      ..generators['observatory'] =
          10 // 3000 цвета ≥ 2500
      ..guardiansBeaten = 3
      ..runEarned = Big.from(4e9);

    test('песнь доступна только после восстановления последнего региона', () {
      expect(canSing(newGame(now, content: testContent)), isFalse);
      expect(canSing(readyToSing()), isTrue);
    });

    test('семена: 3 × √(заработано за забег / 1e9) с округлением вниз — за 4e9 дают 6', () {
      expect(seedsForSong(readyToSing()).toDouble(), 6);
    });

    test('песнь сбрасывает сад, птиц, Эхо и регион, но сохраняет семена и счёт песен', () {
      final s = readyToSing()
        ..echo = Big.from(1e6)
        ..birds = 12;
      expect(longSong(s, now), isNotNull);
      expect(s.echo.isZero, isTrue);
      expect(s.owned('observatory'), 0);
      expect(s.birds, 0);
      expect(s.region, 0);
      expect(s.runEarned.isZero, isTrue);
      expect(s.seeds.toDouble(), 6);
      expect(s.songs, 1);
    });


    test('без готовности песнь не поётся и ничего не сбрасывает', () {
      final s = newGame(now, content: testContent)..echo = Big.from(500);
      expect(longSong(s, now), isNull);
      expect(s.echo.toDouble(), 500);
    });

    test('«Эхо памяти» стоит 1 семя и даёт +50% дохода', () {
      final s = newGame(now, content: testContent)
        ..seeds = Big.from(1)
        ..generators['flower'] = 10;
      expect(buyMemory(s, 'echo'), isTrue);
      expect(s.seeds.isZero, isTrue);
      expect(productionPerSecond(s, now).toDouble(), closeTo(6, 1e-9));
    });

    test('без семян узел не покупается', () {
      final s = newGame(now, content: testContent);
      expect(buyMemory(s, 'echo'), isFalse);
      expect(s.memoryLevel('echo'), 0);
    });

    test('«Ранние ростки» дают 10 цветов в начале следующей песни', () {
      final s = readyToSing()..memory['sprouts'] = 1;
      longSong(s, now);
      expect(s.owned('flower'), 10);
    });
  });

  group('офлайн-доход', () {
    const hour = 3600 * 1000;

    test('час офлайна при 4 Эха/с приносит 14 400', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      final r = applyOffline(s, now + hour);
      expect(r.seconds, closeTo(3600, 1e-9));
      expect(r.earned.toDouble(), closeTo(14400, 1e-6));
      expect(s.echo.toDouble(), closeTo(14400, 1e-6));
      expect(s.lastSeenMs, now + hour);
    });

    test('офлайн ограничен 8 часами', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      final r = applyOffline(s, now + 24 * hour);
      expect(r.capped, isTrue);
      expect(r.earned.toDouble(), closeTo(4 * 8 * 3600, 1e-6));
    });

    test('«Долгий сон» добавляет 2 часа к лимиту', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 10
        ..memory['sleep'] = 1;
      final r = applyOffline(s, now + 24 * hour);
      expect(r.seconds, closeTo(10 * 3600, 1e-9));
    });

    test('перевод часов назад не даёт дохода и не сдвигает отметку времени назад', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      final r = applyOffline(s, now - hour);
      expect(r.earned.isZero, isTrue);
      expect(s.lastSeenMs, now);
    });

    test('Рассветный кит удваивает офлайн-доход', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 10
        ..whales.add('dawn');
      final r = applyOffline(s, now + hour);
      expect(r.earned.toDouble(), closeTo(28800, 1e-6));
    });

    test('буст за рекламу офлайн не действует', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      addBoost(s, now);
      final r = applyOffline(s, now + hour);
      expect(r.earned.toDouble(), closeTo(14400, 1e-6));
    });

    test('офлайн-доход идёт в заработок забега (для семян)', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      applyOffline(s, now + hour);
      expect(s.runEarned.toDouble(), closeTo(14400, 1e-6));
    });
  });

  group('рост маршрута после песен', () {
    test('после двух песен при росте 0.5 стае нужно вдвое больше цвета', () {
      final s = newGame(now, content: makeTestContent(routeGrowthPerSong: 0.5))
        ..songs = 2
        ..generators['flower'] = 60; // раньше этого хватало на первый регион
      expect(regionTarget(s, 0), closeTo(120, 1e-9));
      expect(regionProgress(s), closeTo(0.5, 1e-9));
      expect(canMigrate(s), isFalse);
    });
  });
}

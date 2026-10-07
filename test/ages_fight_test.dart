import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/core/big.dart';
import 'package:skywhale/core/content.dart';
import 'package:skywhale/core/economy.dart';
import 'package:skywhale/core/fight.dart';
import 'package:skywhale/core/game_state.dart';
import 'package:skywhale/core/save.dart';

import 'test_content.dart';

const now = 1700000000000;

/// Случайность под контролем теста: всегда одно и то же число.
class FixedRandom implements math.Random {
  FixedRandom(this.value);
  final double value;
  @override
  double nextDouble() => value;
  @override
  int nextInt(int max) => (value * max).floor();
  @override
  bool nextBool() => value >= 0.5;
}

/// Босс без нот и щита — чтобы проверять урон без побочных эффектов.
const calmBoss = BossDef(
  id: 'calm',
  name: 'Тихий',
  title: '',
  hpFactor: 1,
  seconds: 40,
  scales: 1,
  years: 0,
  payoutSeconds: 0,
  notesPerVolley: 0,
  volleySeconds: 5,
  shield: false,
);

Fight makeFight({
  BossDef boss = calmBoss,
  double hp = 1000,
  double dps = 10,
  double tap = 5,
  double crit = 0,
  double duration = 40,
  math.Random? random,
}) => Fight(
  kind: FightKind.guardian,
  boss: boss,
  rules: testContent.fight,
  maxHp: Big.from(hp),
  duration: duration,
  gardenDps: Big.from(dps),
  tapDamage: Big.from(tap),
  critChance: crit,
  random: random ?? FixedRandom(0.99),
);

/// Первый регион восстановлен: 30 цветов и 10 кустов = 60 цвета, доход 54/с.
GameState regionDone({GameContent? content}) => newGame(now, content: content ?? testContent)
  ..generators['flower'] = 30
  ..generators['bush'] = 10;

void main() {
  group('годы кита', () {
    test('за 15 минут игры кит стареет на год', () {
      final s = newGame(now, content: testContent);
      tick(s, 900, now);
      expect(s.years, closeTo(1, 1e-9));
    });

    test('час офлайна — 4 года, но не больше офлайн-лимита', () {
      final s = newGame(now, content: testContent);
      applyOffline(s, now + 3600 * 1000);
      expect(s.years, closeTo(4, 1e-9));
      final t = newGame(now, content: testContent);
      applyOffline(t, now + 24 * 3600 * 1000);
      expect(t.years, closeTo(32, 1e-9));
    });

    test('Долгая песнь — век сна: +100 лет', () {
      final s = newGame(now, content: testContent)
        ..region = 2
        ..generators['observatory'] = 10
        ..guardiansBeaten = 3;
      longSong(s, now);
      expect(s.years, closeTo(100, 1e-9));
      expect(s.lastSongMs, now);
    });

    test('песня наспех — короткий сон: через полтора часа после прошлой — полвека', () {
      final s = newGame(now, content: testContent)
        ..songs = 1
        ..lastSongMs = now;
      expect(songYears(s, now + 90 * 60 * 1000), closeTo(50, 1e-9));
      expect(songYears(s, now + 5 * 3600 * 1000), closeTo(100, 1e-9));
    });
  });

  group('возраст и маршрут', () {
    test('китёнку открыт только первый регион: после стража можно петь, но не лететь', () {
      final s = regionDone(content: makeTestContent(calfRegions: 1))..guardiansBeaten = 1;
      expect(openRegions(s), 1);
      expect(isLastRegion(s), isTrue);
      expect(canMigrate(s), isFalse);
      expect(canSing(s), isTrue);
    });

    test('испытание открывается в 100 лет, не раньше', () {
      final s = newGame(now, content: testContent)..years = 99.9;
      expect(trialUnlocked(s), isFalse);
      s.years = 100;
      expect(trialUnlocked(s), isTrue);
    });

    test('победа в испытании: новый возраст, просыпается кит стаи, маршрут длиннее', () {
      final s = newGame(now, content: makeTestContent(calfRegions: 1))
        ..years = 100
        ..lastCyclePeak = Big.from(100);
      final f = startTrialFight(s, now)!;
      expect(f.kind, FightKind.trial);
      expect(f.boss.id, 'shadow');
      f.outcome = FightOutcome.won;
      final r = finishFight(s, f, now)!;
      expect(s.age, 1);
      expect(r.newAge, 1);
      expect(r.woke, 'dawn');
      expect(s.whales, contains('dawn'));
      expect(openRegions(s), 3);
    });

    test('на последнем возрасте испытаний больше нет', () {
      final s = newGame(now, content: testContent)
        ..age = 2
        ..years = 1e6;
      expect(nextAge(s), isNull);
      expect(trialUnlocked(s), isFalse);
      expect(startTrialFight(s, now), isNull);
    });
  });

  group('стражи регионов', () {
    test('регион восстановлен, но пока страж не побеждён, дальше не лететь', () {
      final s = regionDone();
      expect(guardianReady(s), isTrue);
      expect(canMigrate(s), isFalse);
      s.guardiansBeaten = 1;
      expect(guardianReady(s), isFalse);
      expect(canMigrate(s), isTrue);
    });

    test('Долгую песнь тоже открывает только побеждённый страж последнего региона', () {
      final s = newGame(now, content: testContent)
        ..region = 2
        ..generators['observatory'] = 10;
      expect(canSing(s), isFalse);
      s.guardiansBeaten = 3;
      expect(canSing(s), isTrue);
    });

    test('здоровье стража — доход в момент его появления × 40 с × 2', () {
      final s = regionDone();
      expect(guardianReference(s, now)!.toDouble(), closeTo(54, 1e-9));
      expect(guardianHp(s, now).toDouble(), closeTo(4320, 1e-6));
      s.generators['flower'] = 40; // сад вырос — страж прежний
      expect(guardianHp(s, now).toDouble(), closeTo(4320, 1e-6));
    });

    test('буст за рекламу не делает стража сильнее', () {
      final s = regionDone();
      addBoost(s, now);
      expect(guardianReference(s, now)!.toDouble(), closeTo(54, 1e-9));
    });

    test('бой со стражем: сила тапа ×4, длительность 40 с', () {
      final s = regionDone();
      expect(startGuardianFight(newGame(now, content: testContent), now), isNull);
      final f = startGuardianFight(s, now)!;
      expect(f.boss.id, 'fog');
      expect(f.maxHp.toDouble(), closeTo(4320, 1e-6));
      expect(f.duration, 40);
      expect(f.gardenDps.toDouble(), closeTo(54, 1e-9));
      expect(f.tapDamage.toDouble(), closeTo((1 + 54 * 0.03) * 4, 1e-9));
    });

    test('победа над стражем: чешуйки, годы, Эхо за минуту дохода, путь открыт', () {
      final s = regionDone()..echo = Big.from(10);
      final f = startGuardianFight(s, now)!..outcome = FightOutcome.won;
      final r = finishFight(s, f, now)!;
      expect(r.scales, 1);
      expect(s.scales, 1);
      expect(s.years, closeTo(5, 1e-9));
      expect(s.echo.toDouble(), closeTo(10 + 54 * 60, 1e-6));
      expect(r.firstWin, isTrue);
      expect(s.bossesMet, contains('fog'));
      expect(s.guardiansBeaten, 1);
      expect(canMigrate(s), isTrue);
    });

    test('поражение ничего не отнимает, но повтор — через минуту (или сразу за рекламу)', () {
      final s = regionDone()..echo = Big.from(10);
      final f = startGuardianFight(s, now)!..outcome = FightOutcome.lost;
      expect(finishFight(s, f, now), isNull);
      expect(s.echo.toDouble(), 10);
      expect(startGuardianFight(s, now + 30 * 1000), isNull);
      expect(fightCooldownLeft(s, now + 30 * 1000), closeTo(30, 1e-9));
      expect(startGuardianFight(s, now + 61 * 1000), isNotNull);
      skipFightCooldown(s);
      expect(startGuardianFight(s, now), isNotNull);
    });

    test('миграция и песнь сбрасывают опорный доход стража', () {
      final s = regionDone()..guardiansBeaten = 1;
      guardianReference(s, now);
      migrate(s);
      expect(s.guardianRef, isNull);
    });
  });

  group('бой', () {
    test('сад бьёт сам: 10 урона в секунду', () {
      final f = makeFight()..tick(10);
      expect(f.hp.toDouble(), closeTo(900, 1e-9));
    });

    test('крещендо: частые тапы бьют всё сильнее, до ×3', () {
      final f = makeFight(dps: 0);
      final hits = <double>[];
      for (var i = 0; i < 7; i++) {
        hits.add(f.tap()!.damage.toDouble());
        f.tick(0.2);
      }
      expect(hits, [5, 7.5, 10, 12.5, 15, 15, 15]);
    });

    test('пауза дольше секунды сбрасывает крещендо', () {
      final f = makeFight(dps: 0)
        ..tap()
        ..tick(0.2)
        ..tap();
      expect(f.crescendo, 1.5);
      f.tick(1.1);
      expect(f.tap()!.damage.toDouble(), 5);
    });

    test('крит бьёт втрое', () {
      final f = makeFight(dps: 0, crit: 0.5, random: FixedRandom(0.1));
      final hit = f.tap()!;
      expect(hit.crit, isTrue);
      expect(hit.damage.toDouble(), 15);
    });

    test('тёмная нота: долетела — глушит сад, лопнута — урон и заряд песни', () {
      final boss = testContent.bossById('fog'); // 1 нота раз в 5 с, полёт 2 с
      final f = makeFight(boss: boss, dps: 0)..tick(5);
      expect(f.notes, hasLength(1));
      f.tick(2.01);
      expect(f.notes, isEmpty);
      expect(f.notesLanded, 1);
      expect(f.mute, closeTo(0.75, 1e-9));

      final g = makeFight(boss: boss, dps: 0)..tick(5);
      final dmg = g.pop(g.notes.single.id)!;
      expect(dmg.toDouble(), closeTo(15, 1e-9));
      expect(g.songCharge, closeTo(0.25, 1e-9));
      g.tick(3);
      expect(g.mute, 1);
    });

    test('сад нельзя заглушить ниже половины', () {
      final f = makeFight(boss: testContent.bossById('fog'), dps: 0);
      for (var i = 0; i < 300; i++) {
        f.tick(0.1);
      }
      expect(f.notesLanded, greaterThanOrEqualTo(3));
      expect(f.mute, closeTo(0.5, 1e-9));
    });

    test('щит тишины режет урон в 10 раз, три удара по трещине его разбивают', () {
      const shielded = BossDef(
        id: 'sh',
        name: '',
        title: '',
        hpFactor: 1,
        seconds: 40,
        scales: 1,
        years: 0,
        payoutSeconds: 0,
        notesPerVolley: 0,
        volleySeconds: 5,
        shield: true,
      );
      final f = makeFight(boss: shielded, dps: 0)..tick(10);
      expect(f.shieldUp, isTrue);
      expect(f.tap()!.damage.toDouble(), closeTo(0.5, 1e-9));
      expect(f.hitCrack(), isTrue);
      expect(f.hitCrack(), isTrue);
      expect(f.shieldUp, isTrue);
      expect(f.hitCrack(), isTrue);
      expect(f.shieldUp, isFalse);
      expect(f.hitCrack(), isFalse);
    });

    test('Песнь кита: удар в 10 тапов и 3 секунды двойного урона', () {
      final f = makeFight(dps: 10);
      for (var i = 0; i < 10; i++) {
        f.tap();
        f.tick(0.6); // без крещендо
      }
      expect(f.songReady, isTrue);
      final before = f.hp.toDouble();
      expect(f.releaseSong()!.toDouble(), closeTo(50, 1e-9));
      expect(f.hp.toDouble(), closeTo(before - 50, 1e-9));
      expect(f.songReady, isFalse);
      expect(f.tap()!.damage.toDouble(), closeTo(10, 1e-9));
      final mid = f.hp.toDouble();
      f.tick(1);
      expect(f.hp.toDouble(), closeTo(mid - 20, 1e-9));
      f.tick(3);
      expect(f.songLeft, 0);
    });

    test('здоровье кончилось — победа; дальше тапы не считаются', () {
      final f = makeFight(hp: 12, dps: 0)..tap();
      expect(f.over, isFalse);
      f.tick(0.2);
      f.tap();
      expect(f.outcome, FightOutcome.won);
      expect(f.hp.isZero, isTrue);
      expect(f.tap(), isNull);
    });

    test('время вышло — поражение; +15 с за рекламу возвращают в бой один раз', () {
      final f = makeFight(dps: 0)..tick(40);
      expect(f.outcome, FightOutcome.lost);
      expect(f.addTime(), isTrue);
      expect(f.outcome, FightOutcome.ongoing);
      expect(f.duration, 55);
      expect(f.addTime(), isFalse);
    });

    test('сила против здоровья: сад и 4 тапа в секунду с крещендо ×2', () {
      final f = makeFight(hp: 1000, dps: 10, tap: 5);
      // (10 + 5 × 4 × 2) × 40 = 2000 → вдвое больше здоровья.
      expect(f.expectedDamage().toDouble(), closeTo(2000, 1e-9));
      expect(f.powerRatio(), closeTo(2, 1e-9));
    });

    test('автопобеда, если сад сам выбивает здоровье с запасом 25%', () {
      expect(makeFight(hp: 320, dps: 10).canAutoWin, isTrue);
      expect(makeFight(hp: 321, dps: 10).canAutoWin, isFalse);
      final f = makeFight(hp: 320, dps: 10);
      expect(f.autoWin(), isTrue);
      expect(f.outcome, FightOutcome.won);
      expect(makeFight(hp: 500, dps: 10).autoWin(), isFalse);
    });
  });

  group('испытание и пик дохода', () {
    test('пик дохода цикла запоминается и переходит в прошлый при Долгой песни', () {
      final s = newGame(now, content: testContent)
        ..region = 2
        ..generators['observatory'] = 10
        ..guardiansBeaten = 3;
      tick(s, 1, now);
      final peak = s.cyclePeak.toDouble();
      expect(peak, greaterThan(0));
      longSong(s, now);
      expect(s.lastCyclePeak.toDouble(), closeTo(peak, 1e-6));
      expect(s.cyclePeak.isZero, isTrue);
      expect(s.guardiansBeaten, 0);
    });

    test('здоровье Тени — пик дохода прошлого цикла × 60 с', () {
      final s = newGame(now, content: testContent)
        ..years = 100
        ..lastCyclePeak = Big.from(100);
      final f = startTrialFight(s, now)!;
      expect(f.maxHp.toDouble(), closeTo(6000, 1e-6));
      expect(f.duration, 60);
    });

    test('чешуйки за испытание растут с возрастом: 10, потом 15', () {
      final s = newGame(now, content: testContent)
        ..years = 1000
        ..lastCyclePeak = Big.from(100);
      final f1 = startTrialFight(s, now)!..outcome = FightOutcome.won;
      expect(finishFight(s, f1, now)!.scales, 10);
      final f2 = startTrialFight(s, now)!..outcome = FightOutcome.won;
      expect(finishFight(s, f2, now)!.scales, 15);
      expect(s.age, 2);
      expect(s.whales, containsAll(['dawn', 'storm']));
    });

    test('Долгая песнь больше не будит китов — их будят испытания', () {
      final s = newGame(now, content: testContent)
        ..region = 2
        ..generators['observatory'] = 10
        ..guardiansBeaten = 3;
      expect(longSong(s, now), isEmpty);
      expect(s.whales, isEmpty);
    });
  });

  group('черты кита', () {
    test('цена черты удваивается: 2, 4, 8 чешуек', () {
      final s = newGame(now, content: testContent)..scales = 6;
      expect(traitCost(s, 'voice'), 2);
      expect(buyTrait(s, 'voice'), isTrue);
      expect(traitCost(s, 'voice'), 4);
      expect(buyTrait(s, 'voice'), isTrue);
      expect(s.scales, 0);
      expect(traitCost(s, 'voice'), 8);
      expect(buyTrait(s, 'voice'), isFalse);
      expect(s.traitLevel('voice'), 2);
    });

    test('на максимальном уровне черта больше не покупается', () {
      final s = newGame(now, content: testContent)
        ..scales = 1000
        ..traits['fins'] = 5;
      expect(traitCost(s, 'fins'), isNull);
      expect(buyTrait(s, 'fins'), isFalse);
    });

    test('«Голос» усиливает тап игрока, но не птиц', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 10
        ..birds = 1;
      final birdsBefore = birdsPerSecond(s, now).toDouble();
      s.traits['voice'] = 1;
      expect(playerTapValue(s, now).toDouble(), closeTo(1.12 * 1.5, 1e-9));
      expect(birdsPerSecond(s, now).toDouble(), closeTo(birdsBefore, 1e-9));
    });

    test('«Сердце» прибавляет к доходу, «Плавники» — к офлайну и бою, «Дух» — к криту', () {
      final s = newGame(now, content: testContent)..generators['flower'] = 10;
      s.traits['heart'] = 1;
      expect(productionPerSecond(s, now).toDouble(), closeTo(6, 1e-9));
      s.traits['fins'] = 2;
      expect(offlineCapHours(s), 10);
      expect(fightSeconds(s, testContent.bossById('fog')), 50);
      s.traits['spirit'] = 3;
      expect(fightCritChance(s), closeTo(0.3, 1e-9));
    });

    test('Мать-кит удваивает весь доход', () {
      final s = newGame(now, content: testContent)
        ..generators['flower'] = 10
        ..whales.add('mother');
      expect(productionPerSecond(s, now).toDouble(), closeTo(8, 1e-9));
    });
  });

  group('сохранение v2', () {
    test('новые поля переживают сохранение и загрузку', () {
      final s = newGame(now, content: testContent)
        ..years = 123.5
        ..age = 1
        ..scales = 7
        ..traits.addAll({'voice': 2, 'fins': 1})
        ..guardiansBeaten = 2
        ..guardianRef = Big.from(54)
        ..cyclePeak = Big.from(1e9)
        ..lastCyclePeak = Big.fromLog10(400)
        ..bossesMet.addAll({'fog', 'petrel'})
        ..fightCooldownUntilMs = now + 5000;
      final r = decodeSave(encodeSave(s), content: testContent)!;
      expect(r.years, closeTo(123.5, 1e-9));
      expect(r.age, 1);
      expect(r.scales, 7);
      expect(r.traitLevel('voice'), 2);
      expect(r.traitLevel('fins'), 1);
      expect(r.guardiansBeaten, 2);
      expect(r.guardianRef!.toDouble(), closeTo(54, 1e-9));
      expect(r.cyclePeak.toDouble(), closeTo(1e9, 1));
      expect(r.lastCyclePeak.exponent, 400);
      expect(r.bossesMet, {'fog', 'petrel'});
      expect(r.fightCooldownUntilMs, now + 5000);
    });

    test('сохранение v1 переходит в v2: годы по числу песен, стражи пройденных регионов побеждены', () {
      const v1 =
          '{"v":1,"echo":"5e3","runEarned":"5e3","totalEarned":"9e9","region":2,"generators":{"flower":40},'
          '"birds":3,"seeds":"12","songs":3,"memory":{},"whales":["dawn"],"lastSeenMs":0,"boostUntilMs":0,'
          '"taps":10,"playSeconds":100,"createdMs":0}';
      final r = decodeSave(v1)!; // боевой контент: регион 2 открыт только взрослому киту
      expect(r.years, closeTo(300, 1e-9));
      expect(r.guardiansBeaten, 2);
      expect(r.age, 2);
      expect(openRegions(r), greaterThan(r.region));
      expect(r.whales, {'dawn'});
      expect(r.scales, 0);
    });
  });
}

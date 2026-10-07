// ignore_for_file: avoid_print — консольный инструмент.
// Симулятор баланса: бот играет по простой стратегии и печатает, когда
// наступают ключевые события. Запуск: `dart run tool/simulate.dart`.
//
// Профили:
//   active — играет без перерывов (чистое игровое время);
//   casual — 10 минут каждые 2 часа с 8:00 до 22:00, 3 тапа/с, 1 буст за сессию;
//   lazy   — 5 минут в 9:00, 13:00, 18:00 и 22:00, 1 тап/с, без рекламы.
//
// Песня: первую бот поёт сразу, следующие — когда новые семена составят
// ≥ 75% всех уже заработанных.
//
// Бои: бот дерётся со стражем, как только тот появился, и с Тенью кита, как
// только открылось испытание. В бою тапает со своей частотой, лопает часть
// тёмных нот, бьёт по трещине щита, выпускает Песнь кита; casual берёт +15 с
// за рекламу. После поражения ждёт паузу и растит сад. Чешуйки тратит на черты.
import 'dart:math' as math;

import 'package:skywhale/core/big.dart';
import 'package:skywhale/core/economy.dart';
import 'package:skywhale/core/fight.dart';
import 'package:skywhale/core/format.dart';
import 'package:skywhale/core/game_state.dart';

class Profile {
  const Profile(
    this.name,
    this.sessionMinutes,
    this.sessionHours,
    this.tapsPerSecond,
    this.boostPerSession, {
    required this.fightTaps,
    required this.popChance,
    required this.popDelay,
    required this.crackDelay,
    required this.fightAds,
  });
  final String name;
  final int sessionMinutes;

  /// Часы начала сессий в течение суток; пусто — играет без перерывов.
  final List<int> sessionHours;
  final double tapsPerSecond;
  final bool boostPerSession;

  /// Бой: тапов в секунду, доля лопнутых нот, через сколько секунд лопает
  /// ноту и бьёт по трещине, берёт ли +15 с за рекламу.
  final double fightTaps;
  final double popChance;
  final double popDelay;
  final double crackDelay;
  final bool fightAds;
}

const profiles = [
  Profile('active', 0, [], 3, false, fightTaps: 6, popChance: 0.9, popDelay: 0.8, crackDelay: 0.3, fightAds: false),
  Profile(
    'casual',
    10,
    [8, 10, 12, 14, 16, 18, 20, 22],
    3,
    true,
    fightTaps: 4,
    popChance: 0.6,
    popDelay: 1.2,
    crackDelay: 0.6,
    fightAds: true,
  ),
  Profile(
    'lazy',
    5,
    [9, 13, 18, 22],
    1,
    false,
    fightTaps: 2,
    popChance: 0.3,
    popDelay: 1.6,
    crackDelay: 1.0,
    fightAds: false,
  ),
];

void main(List<String> args) {
  final hours = args.isNotEmpty ? double.parse(args.first) : 72.0;
  final only = args.length > 1 ? args[1] : null;
  for (final p in profiles) {
    if (only != null && p.name != only) continue;
    simulate(p, hours);
    print('');
  }
}

void simulate(Profile p, double calendarHours) {
  const start = 1700000000000;
  final s = newGame(start);
  var nowMs = start;
  var playSeconds = 0.0;
  final endMs = start + (calendarHours * 3600 * 1000).round();
  final log = <String>[];
  var lastRegion = 0;
  var tapCarry = 0.0;
  var seedsEarned = Big.zero;
  final rng = math.Random(7);
  final attempts = <String, int>{};
  final wins = <String, int>{};
  final winSeconds = <String, double>{};

  void event(String what) {
    log.add(
      '${_hms(playSeconds).padLeft(9)} игры | ${_dhm(nowMs - start).padLeft(10)} с начала | $what '
      '| доход ${formatRate(incomePerSecond(s, nowMs))}, лет ${s.years.toStringAsFixed(0)}, '
      'забег ${formatBig(s.runEarned)}',
    );
  }

  var nextTrialCheck = 0;

  void fightIfPossible() {
    var f = startGuardianFight(s, nowMs, random: rng);
    if (f == null && nowMs >= nextTrialCheck) {
      // На испытание бот идёт, когда подсказка силы обещает победу.
      f = startTrialFight(s, nowMs, random: rng);
      if (f != null && f.powerRatio(tapsPerSecond: p.fightTaps) < 1.0 && !f.canAutoWin) {
        f = null;
        nextTrialCheck = nowMs + 60 * 1000;
      }
    }
    if (f == null) return;
    final key = f.kind == FightKind.trial ? 'Тень → ${nextAge(s)!.name}' : f.boss.name;
    attempts[key] = (attempts[key] ?? 0) + 1;
    final statKey = f.kind == FightKind.trial ? 'Тень' : f.boss.name;
    attempts['$statKey (всего)'] = (attempts['$statKey (всего)'] ?? 0) + 1;
    _botFight(f, p);
    // Бой занимает игровое время: сад всё это время приносит доход.
    tick(s, f.elapsed, nowMs);
    nowMs += (f.elapsed * 1000).round();
    playSeconds += f.elapsed;
    final reward = finishFight(s, f, nowMs);
    if (reward != null) {
      final k = f.kind == FightKind.trial ? 'Тень' : f.boss.name;
      wins[k] = (wins[k] ?? 0) + 1;
      winSeconds[k] = (winSeconds[k] ?? 0) + f.elapsed;
      event(
        'победа: $key (попытка ${attempts[key]}, ${f.elapsed.toStringAsFixed(0)} с, '
        'HP ${formatBig(f.maxHp)}${f.canAutoWin ? ', автобой' : ''})'
        '${reward.newAge != null ? ' → ВОЗРАСТ: ${s.content.ages[reward.newAge!].name}' : ''}',
      );
      _spendScales(s);
    }
  }

  void playSecond() {
    tapCarry += p.tapsPerSecond;
    while (tapCarry >= 1) {
      tap(s, nowMs);
      tapCarry -= 1;
    }
    tick(s, 1, nowMs);
    nowMs += 1000;
    playSeconds += 1;
    touch(s, nowMs);
    _spend(s, nowMs);
    fightIfPossible();
    if (canMigrate(s)) {
      migrate(s);
    }
    if (s.region != lastRegion) {
      event('регион ${s.region + 1}: ${s.content.regions[s.region].name}');
      lastRegion = s.region;
    }
    if (canSing(s) && (s.songs == 0 || seedsForSong(s) >= seedsEarned.mulNum(0.75))) {
      final seeds = seedsForSong(s);
      seedsEarned = seedsEarned + seeds;
      event('перед песней №${s.songs + 1}: птиц ${s.birds}, растения ${s.generators}');
      final woke = longSong(s, nowMs)!;
      lastRegion = 0;
      event(
        'Долгая песнь №${s.songs}: +${formatBig(seeds)} семян'
        '${woke.isEmpty ? '' : ', проснулся: ${woke.map((w) => s.content.whaleById(w).name).join(', ')}'}',
      );
      _spendSeeds(s);
    }
  }

  if (p.sessionHours.isEmpty) {
    while (nowMs < endMs && playSeconds < calendarHours * 3600) {
      playSecond();
      if (s.songs >= 30) break;
    }
  } else {
    // Симуляция начинается в 8:00 первого дня.
    var day = 0;
    outer:
    while (true) {
      for (final h in p.sessionHours) {
        final sessionStart = start + ((day * 24 + h - 8) * 3600 * 1000);
        if (sessionStart >= endMs) break outer;
        if (sessionStart > nowMs) {
          nowMs = sessionStart;
          applyOffline(s, nowMs);
        }
        if (p.boostPerSession) addBoost(s, nowMs);
        for (var i = 0; i < p.sessionMinutes * 60; i++) {
          playSecond();
        }
      }
      day += 1;
    }
  }

  print(
    '=== профиль ${p.name}: сессии по ${p.sessionMinutes} мин в ${p.sessionHours.isEmpty ? 'без перерывов' : p.sessionHours.join(', ')} ч, '
    '${p.tapsPerSecond} тапа/с ===',
  );
  log.forEach(print);
  print(
    'итог: возраст ${s.content.ages[s.age].name}, лет ${s.years.toStringAsFixed(0)}, песен ${s.songs}, '
    'регион ${s.region + 1}, цвет ${(regionProgress(s) * 100).toStringAsFixed(0)}%, '
    'доход ${formatRate(incomePerSecond(s, nowMs))}, семян ${formatBig(s.seeds)}, чешуек ${s.scales}, '
    'черты ${s.traits}',
  );
  for (final k in wins.keys) {
    final total = attempts['$k (всего)'] ?? 0;
    print(
      'бои «$k»: побед ${wins[k]}, попыток на победу ${(total / wins[k]!).toStringAsFixed(1)}, '
      'победный бой в среднем ${(winSeconds[k]! / wins[k]!).toStringAsFixed(0)} с',
    );
  }
}

/// Бот в бою: автоигра с привычками профиля и +15 с за рекламу, если он их смотрит.
void _botFight(Fight f, Profile p) {
  if (f.autoWin()) return;
  while (true) {
    f.autoPlay(tapsPerSecond: p.fightTaps, popChance: p.popChance, popDelay: p.popDelay, crackDelay: p.crackDelay);
    if (f.outcome == FightOutcome.lost && p.fightAds && f.addTime()) continue;
    return;
  }
}

/// Чешуйки: сначала «Сердце» и «Голос» по очереди, «Плавники» до 4, «Дух» до 3.
void _spendScales(GameState s) {
  var bought = true;
  while (bought) {
    bought = false;
    for (final id in ['heart', 'voice']) {
      if (buyTrait(s, id)) bought = true;
    }
    if (s.traitLevel('fins') < 4 && buyTrait(s, 'fins')) bought = true;
    if (s.traitLevel('spirit') < 3 && buyTrait(s, 'spirit')) bought = true;
  }
}

/// Жадная покупка: всё, что окупается быстрее всего, пока хватает Эха.
void _spend(GameState s, int nowMs) {
  for (var guard = 0; guard < 500; guard++) {
    final base = incomePerSecond(s, nowMs);
    String? bestId;
    var bestPayback = double.infinity;
    Big? bestCost;
    for (final g in s.content.generators) {
      if (!isUnlocked(s, g.id)) continue;
      final cost = costOf(s, g.id, 1);
      s.generators[g.id] = s.owned(g.id) + 1;
      final gain = incomePerSecond(s, nowMs) - base;
      s.generators[g.id] = s.owned(g.id) - 1;
      final payback = _payback(cost, gain);
      if (payback < bestPayback) {
        bestPayback = payback;
        bestId = g.id;
        bestCost = cost;
      }
    }
    final birdPrice = birdCost(s, 1);
    s.birds += 1;
    final birdGain = incomePerSecond(s, nowMs) - base;
    s.birds -= 1;
    final birdPayback = _payback(birdPrice, birdGain);
    if (birdPayback < bestPayback) {
      bestPayback = birdPayback;
      bestId = 'bird';
      bestCost = birdPrice;
    }
    if (bestId == null || bestCost == null || s.echo < bestCost) return;
    if (bestId == 'bird') {
      buyBirds(s, 1);
    } else {
      buyGenerator(s, bestId, 1);
    }
  }
}

double _payback(Big cost, Big gain) {
  if (gain.isZero || gain.isNegative) return double.infinity;
  return (cost / gain).toDouble();
}

void _spendSeeds(GameState s) {
  const priority = ['sprouts', 'sleep', 'flock', 'voice', 'path', 'echo'];
  var bought = true;
  while (bought) {
    bought = false;
    for (final id in priority) {
      if (id == 'echo') continue;
      final cost = memoryCost(s, id);
      if (cost != null && cost.toDouble() <= 2 && buyMemory(s, id)) bought = true;
    }
    if (buyMemory(s, 'echo')) bought = true;
  }
}

String _hms(double seconds) {
  final t = seconds.round();
  final h = t ~/ 3600;
  final m = (t % 3600) ~/ 60;
  return h > 0 ? '$hч${m.toString().padLeft(2, '0')}м' : '$mм${(t % 60).toString().padLeft(2, '0')}с';
}

String _dhm(int ms) {
  final t = ms ~/ 1000;
  final d = t ~/ 86400;
  final h = (t % 86400) ~/ 3600;
  final m = (t % 3600) ~/ 60;
  return d > 0 ? '$dд $hч' : '$hч${m.toString().padLeft(2, '0')}м';
}

import 'dart:math' as math;

import 'big.dart';
import 'content.dart';
import 'fight.dart';
import 'game_state.dart';

/// Правила экономики. Числа баланса берутся из `s.content`, текущий момент
/// передаётся явно (`nowMs`) — поэтому правила легко тестировать.

GameState newGame(int nowMs, {GameContent content = defaultContent}) => GameState.fresh(nowMs, content: content);

// ---------------------------------------------------------------------------
// Покупка растений

bool isUnlocked(GameState s, String generatorId) => s.content.generatorById(generatorId).region <= s.region;

/// Цена `n` следующих штук: base × g^owned × (g^n − 1) / (g − 1).
Big costOf(GameState s, String generatorId, int n) {
  final g = s.content.generatorById(generatorId);
  return _geometricCost(g.baseCost, g.costGrowth, s.owned(generatorId), n);
}

/// Сколько штук можно купить прямо сейчас.
int maxAffordable(GameState s, String generatorId) {
  final g = s.content.generatorById(generatorId);
  return _maxAffordable(s.echo, g.baseCost, g.costGrowth, s.owned(generatorId));
}

bool buyGenerator(GameState s, String generatorId, int n) {
  if (n <= 0 || !isUnlocked(s, generatorId)) return false;
  final cost = costOf(s, generatorId, n);
  if (s.echo < cost) return false;
  s.echo = _clampZero(s.echo - cost);
  s.generators[generatorId] = s.owned(generatorId) + n;
  return true;
}

// ---------------------------------------------------------------------------
// Доход

/// Эхо в секунду от одной штуки растения с учётом порогов и общих множителей.
Big productionPerUnit(GameState s, String generatorId, int nowMs) {
  final g = s.content.generatorById(generatorId);
  final perUnit = g.baseProduction * _milestoneMultiplier(s.content, s.owned(generatorId));
  return Big.from(perUnit).mulNum(globalMultiplier(s, nowMs));
}

/// Эхо в секунду от растений (без птиц).
Big productionPerSecond(GameState s, int nowMs) {
  var total = Big.zero;
  for (final g in s.content.generators) {
    final owned = s.owned(g.id);
    if (owned == 0) continue;
    final perUnit = g.baseProduction * _milestoneMultiplier(s.content, owned);
    total = total + Big.from(perUnit * owned);
  }
  return total.mulNum(globalMultiplier(s, nowMs));
}

/// Общий множитель дохода: регион, память, «Сердце» кита, киты стаи, буст.
double globalMultiplier(GameState s, int nowMs) {
  final c = s.content;
  var m = math.pow(c.regionIncomeMultiplier, s.region).toDouble();
  m *= 1 + c.memoryEffects.echoIncomePerLevel * s.memoryLevel('echo');
  m *= 1 + c.traitEffects.heartIncomePerLevel * s.traitLevel('heart');
  if (s.whales.contains('night')) m *= c.whaleEffects.nightIncomeMultiplier;
  if (s.whales.contains('mother')) m *= c.whaleEffects.motherIncomeMultiplier;
  if (isBoosted(s, nowMs)) m *= 2;
  return m;
}

/// Сколько Эха даёт один тап: 1 + доля дохода в секунду.
Big tapValue(GameState s, int nowMs) {
  final c = s.content;
  final share = c.baseTapShare + c.memoryEffects.voiceTapSharePerLevel * s.memoryLevel('voice');
  final value = Big.one + productionPerSecond(s, nowMs).mulNum(share);
  return s.whales.contains('storm') ? value.mulNum(c.whaleEffects.stormTapAndBirdMultiplier) : value;
}

/// Тап игрока: сила тапа с «Голосом» кита (птицы поют без него).
Big playerTapValue(GameState s, int nowMs) =>
    tapValue(s, nowMs).mulNum(1 + s.content.traitEffects.voiceTapPerLevel * s.traitLevel('voice'));

void tap(GameState s, int nowMs) {
  _earn(s, playerTapValue(s, nowMs));
  s.taps += 1;
}

/// Эхо в секунду от поющих птиц: ноты × сила тапа.
Big birdsPerSecond(GameState s, int nowMs) {
  if (s.birds == 0) return Big.zero;
  final c = s.content;
  var notes = s.birds * c.bird.notesPerSecond * (1 + c.memoryEffects.flockNotesPerLevel * s.memoryLevel('flock'));
  if (s.whales.contains('storm')) notes *= c.whaleEffects.stormTapAndBirdMultiplier;
  return tapValue(s, nowMs).mulNum(notes);
}

/// Весь доход в секунду без тапов игрока.
Big incomePerSecond(GameState s, int nowMs) => productionPerSecond(s, nowMs) + birdsPerSecond(s, nowMs);

/// Продвинуть игру на `dtSeconds` активного времени.
void tick(GameState s, double dtSeconds, int nowMs) {
  if (dtSeconds <= 0) return;
  final income = incomePerSecond(s, nowMs);
  _earn(s, income.mulNum(dtSeconds));
  s.playSeconds += dtSeconds;
  s.years += dtSeconds * s.content.fight.yearsPerSecond;
  final base = isBoosted(s, nowMs) ? unboostedIncome(s) : income;
  if (base > s.cyclePeak) s.cyclePeak = base;
  if (guardianReady(s)) guardianReference(s, nowMs);
}

/// Доход в секунду без буста за рекламу: от него считается сила боссов.
Big unboostedIncome(GameState s) => incomePerSecond(s, s.boostUntilMs);

// ---------------------------------------------------------------------------
// Птицы

Big birdCost(GameState s, int n) => _geometricCost(s.content.bird.baseCost, s.content.bird.costGrowth, s.birds, n);

int maxAffordableBirds(GameState s) =>
    _maxAffordable(s.echo, s.content.bird.baseCost, s.content.bird.costGrowth, s.birds);

bool buyBirds(GameState s, int n) {
  if (n <= 0) return false;
  final cost = birdCost(s, n);
  if (s.echo < cost) return false;
  s.echo = _clampZero(s.echo - cost);
  s.birds += n;
  return true;
}

// ---------------------------------------------------------------------------
// Буст за rewarded-рекламу: доход ×2 на boostMinutes, копится до maxBoostMinutes.

bool isBoosted(GameState s, int nowMs) => s.boostUntilMs > nowMs;

void addBoost(GameState s, int nowMs) {
  final start = math.max(s.boostUntilMs, nowMs);
  final cap = nowMs + s.content.maxBoostMinutes * 60 * 1000;
  s.boostUntilMs = math.min(start + s.content.boostMinutes * 60 * 1000, cap);
}

// ---------------------------------------------------------------------------
// Регионы: «цвет неба» растёт вместе с садом

/// Накопленный цвет сада.
double bloom(GameState s) {
  var total = 0.0;
  for (final g in s.content.generators) {
    total += s.owned(g.id) * g.bloom;
  }
  return total * (1 + s.content.memoryEffects.pathBloomPerLevel * s.memoryLevel('path'));
}

/// Цель региона с учётом роста маршрута после каждой песни.
double regionTarget(GameState s, int regionIndex) =>
    s.content.regions[regionIndex].bloomTarget * (1 + s.content.routeGrowthPerSong * s.songs);

/// Прогресс текущего региона от 0 до 1.
double regionProgress(GameState s) {
  final target = regionTarget(s, s.region);
  final previous = s.region == 0 ? 0.0 : regionTarget(s, s.region - 1);
  final p = (bloom(s) - previous) / (target - previous);
  return p.clamp(0.0, 1.0);
}

/// Сколько регионов маршрута доступно киту его возраста.
int openRegions(GameState s) => math.min(s.content.regions.length, currentAge(s).regionsOpen);

bool isLastRegion(GameState s) => s.region >= openRegions(s) - 1;

bool canMigrate(GameState s) => !isLastRegion(s) && regionProgress(s) >= 1 && guardianBeaten(s);

/// Перелёт в следующий регион: сад и Эхо сохраняются, доход растёт.
bool migrate(GameState s) {
  if (!canMigrate(s)) return false;
  s.region += 1;
  s.guardianRef = null;
  return true;
}

// ---------------------------------------------------------------------------
// Возраст кита

AgeDef currentAge(GameState s) => s.content.ages[s.age];

/// Следующий возраст или `null`, если кит уже достиг последнего.
AgeDef? nextAge(GameState s) => s.age + 1 < s.content.ages.length ? s.content.ages[s.age + 1] : null;

/// Кит набрал годы для следующего возраста — открыто испытание.
bool trialUnlocked(GameState s) {
  final next = nextAge(s);
  return next != null && s.years >= next.years;
}

// ---------------------------------------------------------------------------
// Стражи регионов

BossDef guardianOf(GameState s, int region) => s.content.bossById(s.content.regions[region].guardian);

bool guardianBeaten(GameState s) => s.guardiansBeaten > s.region;

/// Регион восстановлен, а страж ещё не побеждён — он появился и ждёт боя.
bool guardianReady(GameState s) => !guardianBeaten(s) && regionProgress(s) >= 1;

/// Опорный доход стража фиксируется, когда страж появился: дальше сад растёт,
/// а страж остаётся прежним — поэтому рост помогает его победить.
Big? guardianReference(GameState s, int nowMs) {
  if (s.guardianRef == null && guardianReady(s)) s.guardianRef = unboostedIncome(s);
  return s.guardianRef;
}

Big guardianHp(GameState s, int nowMs) =>
    _bossHp(guardianReference(s, nowMs) ?? unboostedIncome(s), guardianOf(s, s.region), s);

/// Здоровье Тени кита — от пика дохода прошлого цикла (или этого, если он выше).
Big trialHp(GameState s) {
  final ref = s.lastCyclePeak > s.cyclePeak ? s.lastCyclePeak : s.cyclePeak;
  return _bossHp(ref, s.content.bossById(s.content.fight.trialBoss), s);
}

Big _bossHp(Big reference, BossDef b, GameState s) {
  final ref = reference < Big.one ? Big.one : reference;
  return ref.mulNum(b.seconds * b.hpFactor);
}

// ---------------------------------------------------------------------------
// Бои

bool onFightCooldown(GameState s, int nowMs) => nowMs < s.fightCooldownUntilMs;

double fightCooldownLeft(GameState s, int nowMs) => math.max(0, (s.fightCooldownUntilMs - nowMs) / 1000);

/// Повторить бой сразу (за рекламу).
void skipFightCooldown(GameState s) => s.fightCooldownUntilMs = 0;

/// Длительность боя с «Плавниками» кита.
double fightSeconds(GameState s, BossDef b) =>
    b.seconds + s.content.traitEffects.finsFightSecondsPerLevel * s.traitLevel('fins');

double fightCritChance(GameState s) =>
    s.content.fight.baseCrit + s.content.traitEffects.spiritCritPerLevel * s.traitLevel('spirit');

Fight _fight(GameState s, int nowMs, FightKind kind, BossDef boss, Big hp, math.Random? random) => Fight(
  kind: kind,
  boss: boss,
  rules: s.content.fight,
  maxHp: hp,
  duration: fightSeconds(s, boss),
  gardenDps: incomePerSecond(s, nowMs),
  tapDamage: playerTapValue(s, nowMs).mulNum(s.content.fight.tapMultiplier),
  critChance: fightCritChance(s),
  random: random,
);

Fight? startGuardianFight(GameState s, int nowMs, {math.Random? random}) {
  if (!guardianReady(s) || onFightCooldown(s, nowMs)) return null;
  return _fight(s, nowMs, FightKind.guardian, guardianOf(s, s.region), guardianHp(s, nowMs), random);
}

Fight? startTrialFight(GameState s, int nowMs, {math.Random? random}) {
  if (!trialUnlocked(s) || onFightCooldown(s, nowMs)) return null;
  final boss = s.content.bossById(s.content.fight.trialBoss);
  return _fight(s, nowMs, FightKind.trial, boss, trialHp(s), random);
}

class FightReward {
  const FightReward({
    required this.scales,
    required this.years,
    required this.echo,
    required this.firstWin,
    this.newAge,
    this.woke,
  });

  final int scales;
  final double years;
  final Big echo;

  /// Этого босса победили впервые (сюжетная сцена).
  final bool firstWin;

  /// Новый возраст кита после испытания.
  final int? newAge;

  /// Проснувшийся кит стаи.
  final String? woke;
}

/// Итог боя. Победа — награды (и новый возраст после испытания), поражение —
/// пауза перед повтором. Ничего не отнимается.
FightReward? finishFight(GameState s, Fight f, int nowMs) {
  if (f.outcome == FightOutcome.lost) {
    s.fightCooldownUntilMs = nowMs + (s.content.fight.retrySeconds * 1000).round();
    return null;
  }
  if (f.outcome != FightOutcome.won) return null;
  final scales = f.boss.scales + f.boss.scalesPerAge * s.age;
  s.scales += scales;
  s.years += f.boss.years;
  final echo = unboostedIncome(s).mulNum(f.boss.payoutSeconds);
  _earn(s, echo);
  final firstWin = s.bossesMet.add(f.boss.id);
  s.bossWins += 1;
  int? newAge;
  String? woke;
  if (f.kind == FightKind.guardian) {
    s.guardiansBeaten = math.max(s.guardiansBeaten, s.region + 1);
    s.guardianRef = null;
  } else if (nextAge(s) != null) {
    s.age += 1;
    newAge = s.age;
    final whale = currentAge(s).whale;
    if (whale != null && s.whales.add(whale)) woke = whale;
  }
  s.fightCooldownUntilMs = 0;
  return FightReward(scales: scales, years: f.boss.years, echo: echo, firstWin: firstWin, newAge: newAge, woke: woke);
}

// ---------------------------------------------------------------------------
// Черты кита (за чешуйки)

/// Цена следующего уровня черты или `null`, если уровень максимальный.
int? traitCost(GameState s, String traitId) {
  final t = s.content.traitById(traitId);
  final level = s.traitLevel(traitId);
  if (level >= t.maxLevel) return null;
  return (t.costBase * math.pow(t.costGrowth, level) - 1e-9).ceil();
}

bool buyTrait(GameState s, String traitId) {
  final cost = traitCost(s, traitId);
  if (cost == null || s.scales < cost) return false;
  s.scales -= cost;
  s.traits[traitId] = s.traitLevel(traitId) + 1;
  return true;
}

// ---------------------------------------------------------------------------
// Долгая песнь (престиж), Дерево памяти, киты

bool canSing(GameState s) => isLastRegion(s) && regionProgress(s) >= 1 && guardianBeaten(s);

/// Семена за песню: floor(scale × (заработано за забег / divisor)^power).
Big seedsForSong(GameState s) {
  if (s.runEarned.isZero) return Big.zero;
  final c = s.content;
  if (s.runEarned.exponent < 300) {
    // В диапазоне double степень считаем напрямую: √4 = 2, а не 1.9999999.
    final v = c.seedsScale * math.pow(s.runEarned.toDouble() / c.seedsDivisor, c.seedsPower);
    return _floor(Big.from(v));
  }
  final raw = s.runEarned.divNum(c.seedsDivisor).pow(c.seedsPower).mulNum(c.seedsScale);
  return _floor(raw);
}

/// Спеть Долгую песнь: кит засыпает на век (+годы), сад отцветает, жители
/// сменяются поколением. Возвращает id проснувшихся китов (теперь их будят
/// испытания, поэтому список пуст) или `null`, если петь ещё рано.
List<String>? longSong(GameState s, int nowMs) {
  if (!canSing(s)) return null;
  s.seeds = s.seeds + seedsForSong(s);
  s.years += songYears(s, nowMs);
  s.songs += 1;
  s.lastSongMs = nowMs;
  s.echo = Big.zero;
  s.runEarned = Big.zero;
  s.region = 0;
  s.birds = 0;
  s.generators.clear();
  s.guardiansBeaten = 0;
  s.guardianRef = null;
  s.lastCyclePeak = s.cyclePeak;
  s.cyclePeak = Big.zero;
  final sprouts = s.content.memoryEffects.sproutsFlowersPerLevel * s.memoryLevel('sprouts');
  if (sprouts > 0) s.generators[s.content.generators.first.id] = sprouts;
  return <String>[];
}

/// Сколько лет даст Долгая песнь сейчас: полный век, если с прошлой песни
/// прошло не меньше `songFullCycleHours`, иначе долю. Первая — всегда век.
double songYears(GameState s, int nowMs) {
  final rules = s.content.fight;
  if (s.songs == 0) return rules.yearsPerSong;
  final hours = (nowMs - s.lastSongMs) / 3.6e6;
  return rules.yearsPerSong * (hours / rules.songFullCycleHours).clamp(0.0, 1.0);
}

/// Цена следующего уровня узла или `null`, если уровень максимальный.
Big? memoryCost(GameState s, String nodeId) {
  final node = s.content.memoryById(nodeId);
  final level = s.memoryLevel(nodeId);
  if (level >= node.maxLevel) return null;
  return Big.from(node.costOfLevel(level));
}

bool buyMemory(GameState s, String nodeId) {
  final cost = memoryCost(s, nodeId);
  if (cost == null || s.seeds < cost) return false;
  s.seeds = _clampZero(s.seeds - cost);
  s.memory[nodeId] = s.memoryLevel(nodeId) + 1;
  return true;
}

// ---------------------------------------------------------------------------
// Офлайн-доход

class OfflineResult {
  const OfflineResult({required this.seconds, required this.earned, required this.capped});

  /// Сколько секунд засчитано (с учётом лимита).
  final double seconds;
  final Big earned;

  /// Игрок отсутствовал дольше лимита.
  final bool capped;
}

/// Лимит офлайна в часах.
double offlineCapHours(GameState s) =>
    s.content.baseOfflineHours +
    s.content.memoryEffects.sleepHoursPerLevel * s.memoryLevel('sleep') +
    s.content.traitEffects.finsOfflineHoursPerLevel * s.traitLevel('fins');

/// Начислить доход за время отсутствия и обновить отметку времени.
///
/// Буст за рекламу офлайн не действует. Если часы на устройстве переведены
/// назад, дохода нет, а отметка времени не сдвигается назад — иначе можно
/// «накрутить» офлайн, переводя часы туда-обратно.
OfflineResult applyOffline(GameState s, int nowMs) {
  final elapsedMs = nowMs - s.lastSeenMs;
  if (elapsedMs <= 0) {
    return OfflineResult(seconds: 0, earned: Big.zero, capped: false);
  }
  s.lastSeenMs = nowMs;
  final capSeconds = offlineCapHours(s) * 3600;
  final rawSeconds = elapsedMs / 1000;
  final seconds = math.min(rawSeconds, capSeconds);
  // nowMs = boostUntilMs гарантирует, что буст не учитывается.
  var earned = incomePerSecond(s, s.boostUntilMs).mulNum(seconds);
  if (s.whales.contains('dawn')) earned = earned.mulNum(s.content.whaleEffects.dawnOfflineMultiplier);
  _earn(s, earned);
  s.years += seconds * s.content.fight.yearsPerSecond;
  return OfflineResult(seconds: seconds, earned: earned, capped: rawSeconds > capSeconds);
}

/// Отметить, что игрок был в игре в момент `nowMs` (для расчёта офлайна).
void touch(GameState s, int nowMs) {
  if (nowMs > s.lastSeenMs) s.lastSeenMs = nowMs;
}

/// Добавить Эхо от бонуса (например, ×2 офлайна за рекламу).
void grantBonus(GameState s, Big amount) => _earn(s, amount);

// ---------------------------------------------------------------------------
// Внутреннее

void _earn(GameState s, Big amount) {
  if (amount.isZero) return;
  s.echo = s.echo + amount;
  s.runEarned = s.runEarned + amount;
  s.totalEarned = s.totalEarned + amount;
}

double _milestoneMultiplier(GameContent c, int owned) {
  var m = 1.0;
  for (final t in c.ownedMilestones) {
    if (owned >= t) m *= 2;
  }
  return m;
}

Big _geometricCost(double base, double growth, int owned, int n) {
  if (n <= 0) return Big.zero;
  final first = Big.from(base) * Big.from(growth).pow(owned);
  final factor = (math.pow(growth, n) - 1) / (growth - 1);
  if (factor.isFinite) return first.mulNum(factor);
  // Огромное n: (g^n − 1)/(g − 1) ≈ g^n/(g − 1).
  return first * Big.from(growth).pow(n) / Big.from(growth - 1);
}

int _maxAffordable(Big budget, double base, double growth, int owned) {
  if (budget.isZero) return 0;
  final first = Big.from(base) * Big.from(growth).pow(owned);
  // n = floor(log_g(budget × (g − 1) / first + 1))
  final ratio = budget.mulNum(growth - 1) / first;
  final logRatioPlusOne = ratio.exponent > 15 ? ratio.log10() : math.log(ratio.toDouble() + 1) / math.ln10;
  var n = (logRatioPlusOne / (math.log(growth) / math.ln10)).floor();
  // Защита от ошибок округления на границе.
  while (n > 0 && _geometricCost(base, growth, owned, n) > budget) {
    n -= 1;
  }
  while (_geometricCost(base, growth, owned, n + 1) <= budget) {
    n += 1;
  }
  return n;
}

Big _clampZero(Big x) => x.isNegative ? Big.zero : x;

/// Округление вниз с допуском на ошибку double (5.9999999999 → 6).
Big _floor(Big x) {
  if (x.isZero || x.exponent >= 15) return x;
  return Big.from((x.toDouble() * (1 + 1e-12)).floorToDouble());
}

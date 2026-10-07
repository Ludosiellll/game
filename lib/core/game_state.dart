import 'big.dart';
import 'content.dart';

/// Всё, что нужно сохранить между запусками. Логика — в `economy.dart`.
class GameState {
  GameState({
    required this.echo,
    required this.runEarned,
    required this.totalEarned,
    required this.region,
    required this.generators,
    required this.birds,
    required this.seeds,
    required this.songs,
    required this.memory,
    required this.whales,
    required this.lastSeenMs,
    required this.boostUntilMs,
    required this.taps,
    required this.playSeconds,
    required this.createdMs,
    this.years = 0,
    this.age = 0,
    this.scales = 0,
    Map<String, int>? traits,
    this.guardiansBeaten = 0,
    this.guardianRef,
    Big? cyclePeak,
    Big? lastCyclePeak,
    Set<String>? bossesMet,
    this.fightCooldownUntilMs = 0,
    this.lastSongMs = 0,
    this.content = defaultContent,
  }) : traits = traits ?? <String, int>{},
       cyclePeak = cyclePeak ?? Big.zero,
       lastCyclePeak = lastCyclePeak ?? Big.zero,
       bossesMet = bossesMet ?? <String>{};

  factory GameState.fresh(int nowMs, {GameContent content = defaultContent}) => GameState(
    echo: Big.zero,
    runEarned: Big.zero,
    totalEarned: Big.zero,
    region: 0,
    generators: <String, int>{},
    birds: 0,
    seeds: Big.zero,
    songs: 0,
    memory: <String, int>{},
    whales: <String>{},
    lastSeenMs: nowMs,
    boostUntilMs: 0,
    taps: 0,
    playSeconds: 0,
    createdMs: nowMs,
    content: content,
  );

  /// Текущее Эхо (основная валюта).
  Big echo;

  /// Заработано с последней Долгой песни — от этого считаются семена.
  Big runEarned;

  /// Заработано за всё время (статистика).
  Big totalEarned;

  /// Индекс текущего региона маршрута.
  int region;

  /// Сколько штук каждого растения куплено.
  final Map<String, int> generators;

  int birds;

  /// Семена памяти (мета-валюта престижа).
  Big seeds;

  /// Сколько Долгих песен спето.
  int songs;

  /// Уровни узлов Дерева памяти.
  final Map<String, int> memory;

  /// Проснувшиеся киты.
  final Set<String> whales;

  int lastSeenMs;
  int boostUntilMs;
  int taps;
  double playSeconds;
  final int createdMs;

  /// Возраст кита в годах. Кит не сбрасывается Долгой песнью.
  double years;

  /// Индекс возраста в `content.ages` (0 — китёнок).
  int age;

  /// Чешуйки — награда за боссов, тратятся на черты кита.
  int scales;

  /// Уровни черт кита.
  final Map<String, int> traits;

  /// Сколько стражей маршрута побеждено в этом цикле: страж региона `r`
  /// побеждён, если `guardiansBeaten > r`.
  int guardiansBeaten;

  /// Доход в секунду в момент, когда появился страж текущего региона:
  /// от него считается здоровье стража. `null` — страж ещё не появился.
  Big? guardianRef;

  /// Наибольший доход в секунду в этом цикле и в прошлом — от них считается
  /// здоровье Тени кита (испытание возраста).
  Big cyclePeak;
  Big lastCyclePeak;

  /// Боссы, которых кит уже побеждал хотя бы раз (для сюжетных сцен).
  final Set<String> bossesMet;

  /// До какого момента нельзя снова начать бой после поражения.
  int fightCooldownUntilMs;

  /// Когда спета последняя Долгая песнь (0 — ещё не пели).
  int lastSongMs;

  /// Баланс и контент, по которым играется эта партия (не сохраняется).
  final GameContent content;

  int owned(String generatorId) => generators[generatorId] ?? 0;
  int memoryLevel(String nodeId) => memory[nodeId] ?? 0;
  int traitLevel(String traitId) => traits[traitId] ?? 0;
}

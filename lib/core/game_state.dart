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
    this.content = defaultContent,
  });

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

  /// Баланс и контент, по которым играется эта партия (не сохраняется).
  final GameContent content;

  int owned(String generatorId) => generators[generatorId] ?? 0;
  int memoryLevel(String nodeId) => memory[nodeId] ?? 0;
}

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../core/big.dart';
import '../core/content.dart';
import '../core/economy.dart' as eco;
import '../core/fight.dart';
import '../core/game_state.dart';
import '../core/save.dart';
import 'ad_service.dart';
import 'save_store.dart';

enum BuyMode { one, ten, max }

/// Событие для интерфейса и сцены (окна, анимации).
sealed class GameEvent {
  const GameEvent();
}

class MigrationEvent extends GameEvent {
  const MigrationEvent(this.region);
  final int region;
}

class SongEvent extends GameEvent {
  const SongEvent({required this.seeds, required this.woke});
  final Big seeds;
  final List<String> woke;
}

/// Бой закончился: победа с наградой или отступление.
class FightEndEvent extends GameEvent {
  const FightEndEvent({required this.fight, this.reward});
  final Fight fight;
  final eco.FightReward? reward;
  bool get won => reward != null;
}

/// Тап по небу: где он был и сколько дал (для волны и всплывающего числа).
class TapEffect {
  const TapEffect({required this.x, required this.y, required this.value});
  final double x;
  final double y;
  final Big value;
}

/// Связывает правила экономики с временем, сохранением, рекламой и экраном.
class GameController extends ChangeNotifier {
  GameController({
    required this.store,
    int Function()? now,
    this.content = defaultContent,
    AdService? ads,
    this.fightRandom,
  }) : _now = now ?? _systemNow,
       ads = ads ?? const InstantAdService();

  /// Случайность боёв (в тестах — предсказуемая).
  final math.Random? fightRandom;

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  final SaveStore store;
  final int Function() _now;
  final GameContent content;
  AdService ads;

  late GameState state;
  bool _ready = false;
  bool get ready => _ready;

  /// Результат офлайна, о котором ещё не сказали игроку. Окно показываем
  /// только после отлучки от 5 минут, за короткие паузы доход идёт молча.
  eco.OfflineResult? pendingOffline;

  static const double offlineDialogSeconds = 300;

  BuyMode buyMode = BuyMode.one;

  void setBuyMode(BuyMode mode) {
    buyMode = mode;
    notifyListeners();
  }

  final StreamController<GameEvent> _events = StreamController<GameEvent>.broadcast();
  Stream<GameEvent> get events => _events.stream;

  final StreamController<TapEffect> _taps = StreamController<TapEffect>.broadcast();
  Stream<TapEffect> get taps => _taps.stream;

  double _sinceNotify = 0;
  double _sinceSave = 0;
  bool _saving = false;

  int get nowMs => _now();

  Future<void> init() async {
    final raw = await store.load();
    final loaded = raw == null ? null : decodeSave(raw, content: content);
    state = loaded ?? eco.newGame(_now(), content: content);
    _ready = true;
    _collectOffline();
    notifyListeners();
  }

  /// Вызывать при возвращении в игру из фона.
  void onResume() {
    if (!_ready) return;
    _collectOffline();
    notifyListeners();
  }

  /// Вызывать при уходе в фон.
  Future<void> onPause() => saveNow();

  void _collectOffline() {
    final r = eco.applyOffline(state, _now());
    if (r.seconds >= offlineDialogSeconds) pendingOffline = r;
  }

  Future<void> claimOffline({required bool doubled}) async {
    final r = pendingOffline;
    if (r == null) return;
    if (doubled && await ads.showRewarded()) {
      eco.grantBonus(state, r.earned);
    }
    pendingOffline = null;
    notifyListeners();
    await saveNow();
  }

  /// Один кадр игрового цикла. `dt` ограничен секундой: долгие паузы
  /// считаются через офлайн, а не через кадр.
  void onFrame(double dt) {
    if (!_ready) return;
    final step = dt.clamp(0.0, 1.0);
    final n = _now();
    eco.tick(state, step, n);
    eco.touch(state, n);
    final f = fight;
    if (f != null && !f.over && !fightPaused) {
      f.tick(step);
      _afterFightAction();
    }
    _sinceNotify += step;
    if (_sinceNotify >= 0.1) {
      _sinceNotify = 0;
      notifyListeners();
    }
    _sinceSave += step;
    if (_sinceSave >= 10) {
      _sinceSave = 0;
      unawaited(saveNow());
    }
  }

  void tapAt(double x, double y) {
    final n = _now();
    final value = eco.tapValue(state, n);
    eco.tap(state, n);
    _taps.add(TapEffect(x: x, y: y, value: value));
    notifyListeners();
  }

  // --- покупки -------------------------------------------------------------

  int _amountFor(int maxAffordable) => switch (buyMode) {
    BuyMode.one => 1,
    BuyMode.ten => 10,
    BuyMode.max => maxAffordable,
  };

  /// Сколько штук купит кнопка в текущем режиме (для «Макс.» — минимум 1).
  int generatorAmount(String id) {
    final n = _amountFor(eco.maxAffordable(state, id));
    return n < 1 ? 1 : n;
  }

  Big generatorPrice(String id) => eco.costOf(state, id, generatorAmount(id));

  bool buyGenerator(String id) {
    final n = _amountFor(eco.maxAffordable(state, id));
    if (n < 1 || !eco.buyGenerator(state, id, n)) return false;
    notifyListeners();
    return true;
  }

  int birdAmount() {
    final n = _amountFor(eco.maxAffordableBirds(state));
    return n < 1 ? 1 : n;
  }

  Big birdPrice() => eco.birdCost(state, birdAmount());

  bool buyBird() {
    final n = _amountFor(eco.maxAffordableBirds(state));
    if (n < 1 || !eco.buyBirds(state, n)) return false;
    notifyListeners();
    return true;
  }

  // --- регионы, песнь, память ---------------------------------------------

  bool migrate() {
    if (!eco.migrate(state)) return false;
    _events.add(MigrationEvent(state.region));
    notifyListeners();
    unawaited(saveNow());
    return true;
  }

  bool sing() {
    final seeds = eco.seedsForSong(state);
    final woke = eco.longSong(state, _now());
    if (woke == null) return false;
    _events.add(SongEvent(seeds: seeds, woke: woke));
    notifyListeners();
    unawaited(saveNow());
    return true;
  }

  bool buyMemory(String nodeId) {
    if (!eco.buyMemory(state, nodeId)) return false;
    notifyListeners();
    unawaited(saveNow());
    return true;
  }

  // --- бои -------------------------------------------------------------------

  /// Идущий бой (или законченный, пока игрок смотрит итог).
  Fight? fight;

  /// Время боя стоит (заставка с именем босса, окно итогов).
  bool fightPaused = false;

  /// Итог закрыт наградой или отступлением — повторно не применяется.
  bool _fightSettled = false;

  /// Награда последнего выигранного боя (для окна итогов).
  eco.FightReward? lastReward;

  bool get canFightGuardian => eco.guardianReady(state);
  bool get canFightTrial => eco.trialUnlocked(state);
  double get fightCooldownLeft => eco.fightCooldownLeft(state, _now());

  bool startGuardianFight() => _start(eco.startGuardianFight(state, _now(), random: fightRandom));

  bool startTrialFight() => _start(eco.startTrialFight(state, _now(), random: fightRandom));

  bool _start(Fight? f) {
    if (f == null || fight != null) return false;
    fight = f;
    _fightSettled = false;
    lastReward = null;
    notifyListeners();
    return true;
  }

  TapHit? fightTap() {
    final hit = fight?.tap();
    _afterFightAction();
    return hit;
  }

  Big? fightPop(int noteId) {
    final damage = fight?.pop(noteId);
    _afterFightAction();
    return damage;
  }

  bool fightCrack() {
    final ok = fight?.hitCrack() ?? false;
    _afterFightAction();
    return ok;
  }

  Big? fightSong() {
    final damage = fight?.releaseSong();
    _afterFightAction();
    return damage;
  }

  bool fightAutoWin() {
    final ok = fight?.autoWin() ?? false;
    _afterFightAction();
    return ok;
  }

  /// +15 секунд за рекламу — в бою или сразу после того, как время вышло.
  Future<bool> fightAddTime() async {
    final f = fight;
    if (f == null || f.extraTimeUsed || _fightSettled) return false;
    if (!await ads.showRewarded()) return false;
    final ok = f.addTime();
    notifyListeners();
    return ok;
  }

  /// Отступить после поражения: начинается пауза перед повтором.
  void retreatFight() {
    final f = fight;
    if (f == null) return;
    if (!_fightSettled && f.outcome == FightOutcome.lost) {
      eco.finishFight(state, f, _now());
      _fightSettled = true;
      _events.add(FightEndEvent(fight: f));
    }
    closeFight();
  }

  /// Закрыть экран боя (после итогов).
  void closeFight() {
    final f = fight;
    if (f != null && !_fightSettled && f.outcome == FightOutcome.ongoing) {
      // Выход посреди боя — как поражение, но без паузы: ничего не потеряно.
      _fightSettled = true;
    }
    fight = null;
    notifyListeners();
    unawaited(saveNow());
  }

  /// Повторить бой сразу, не дожидаясь паузы, — за рекламу.
  Future<bool> skipFightCooldownWithAd() async {
    if (!eco.onFightCooldown(state, _now())) return true;
    if (!await ads.showRewarded()) return false;
    eco.skipFightCooldown(state);
    notifyListeners();
    return true;
  }

  void _afterFightAction() {
    final f = fight;
    if (f == null) return;
    if (!_fightSettled && f.outcome == FightOutcome.won) {
      lastReward = eco.finishFight(state, f, _now());
      _fightSettled = true;
      _events.add(FightEndEvent(fight: f, reward: lastReward));
      unawaited(saveNow());
    }
    notifyListeners();
  }

  // --- черты кита ---------------------------------------------------------------

  bool buyTrait(String traitId) {
    if (!eco.buyTrait(state, traitId)) return false;
    notifyListeners();
    unawaited(saveNow());
    return true;
  }

  Future<bool> watchBoostAd() async {
    if (!await ads.showRewarded()) return false;
    eco.addBoost(state, _now());
    notifyListeners();
    return true;
  }

  // --- сохранение ----------------------------------------------------------

  Future<void> saveNow() async {
    if (!_ready || _saving) return;
    _saving = true;
    try {
      await store.save(encodeSave(state));
    } finally {
      _saving = false;
    }
  }

  String exportProgress() => exportCode(state);

  /// Загрузить прогресс из кода. Возвращает `false`, если код не подошёл.
  Future<bool> importProgress(String code) async {
    final imported = importCode(code, content: content);
    if (imported == null) return false;
    state = imported;
    pendingOffline = null;
    notifyListeners();
    await saveNow();
    return true;
  }

  Future<void> resetProgress() async {
    state = eco.newGame(_now(), content: content);
    pendingOffline = null;
    notifyListeners();
    await saveNow();
  }

  // --- инструменты для тестирования ---------------------------------------

  void debugAddEcho(num amount) {
    state.echo = state.echo + Big.from(amount);
    notifyListeners();
  }

  /// Состарить кита (для проверки превращений).
  void debugAddYears(double years) {
    state.years += years;
    notifyListeners();
  }

  /// Промотать активную игру вперёд (как будто игрок не нажимал, но был в игре).
  void debugSkip(double seconds) {
    eco.tick(state, seconds, _now());
    notifyListeners();
  }

  @override
  void dispose() {
    _events.close();
    _taps.close();
    super.dispose();
  }
}

import 'dart:math' as math;

import 'big.dart';
import 'content.dart';

enum FightKind { guardian, trial }

enum FightOutcome { ongoing, won, lost }

/// Тёмная нота босса: летит в сад, её можно лопнуть тапом.
class DarkNote {
  DarkNote(this.id, this.x);

  final int id;

  /// Куда упадёт, от 0 (левый край) до 1 (правый).
  final double x;

  /// Доля пути от босса до сада, от 0 до 1.
  double t = 0;
}

class TapHit {
  const TapHit({required this.damage, required this.crit});

  final Big damage;
  final bool crit;
}

/// Бой с боссом — чистая модель без Flutter: интерфейс зовёт [tick], [tap],
/// [pop], [hitCrack], [releaseSong]; итог применяет `finishFight` в экономике.
class Fight {
  Fight({
    required this.kind,
    required this.boss,
    required this.rules,
    required this.maxHp,
    required this.duration,
    required this.gardenDps,
    required this.tapDamage,
    required this.critChance,
    math.Random? random,
  }) : hp = maxHp,
       _rng = random ?? math.Random(),
       _nextVolleyAt = boss.volleySeconds,
       _nextShieldAt = boss.shield ? rules.shieldFirstAt : double.infinity;

  final FightKind kind;
  final BossDef boss;
  final FightRules rules;
  final Big maxHp;

  /// Сколько секунд длится бой (растёт на +15 с за рекламу).
  double duration;

  /// Урон в секунду от сада и птиц.
  final Big gardenDps;

  /// Урон одного тапа без множителей.
  final Big tapDamage;
  final double critChance;
  final math.Random _rng;

  Big hp;
  double elapsed = 0;
  FightOutcome outcome = FightOutcome.ongoing;

  /// Множитель крещендо (1…crescendoMax).
  double crescendo = 1;
  double _sinceTap = double.infinity;

  /// Заряд Песни кита (0…1) и сколько ещё длится её окно.
  double songCharge = 0;
  double songLeft = 0;

  /// Доля урона сада, которая осталась после долетевших нот.
  double mute = 1;
  int notesLanded = 0;
  final List<DarkNote> notes = [];
  int _nextNoteId = 0;
  double _nextVolleyAt;

  bool shieldUp = false;
  int shieldHitsLeft = 0;
  double _nextShieldAt;

  bool extraTimeUsed = false;

  double get timeLeft => math.max(0, duration - elapsed);
  bool get over => outcome != FightOutcome.ongoing;
  bool get songReady => songCharge >= 1 - 1e-9; // 0.1 × 10 в double — это 0.9999999
  double get hpFraction => maxHp.isZero ? 0 : (hp / maxHp).toDouble().clamp(0.0, 1.0);

  /// Ожидаемый урон за бой при обычной игре: сад плюс тапы с частотой
  /// `tapsPerSecond` и средним крещендо. Для подсказки «хватит ли сил».
  Big expectedDamage({double tapsPerSecond = 4, double avgCrescendo = 2}) =>
      (gardenDps + tapDamage.mulNum(tapsPerSecond * avgCrescendo)).mulNum(duration);

  /// Сила против здоровья босса: 1 — впритык, больше — с запасом.
  double powerRatio({double tapsPerSecond = 4}) =>
      maxHp.isZero ? double.infinity : (expectedDamage(tapsPerSecond: tapsPerSecond) / maxHp).toDouble();

  /// Сад сам выбьет здоровье с запасом — можно «пропеть» бой сразу.
  bool get canAutoWin => gardenDps.mulNum(duration) >= maxHp.mulNum(rules.autoWinMargin);

  double get _shieldFactor => shieldUp ? rules.shieldDamageFactor : 1.0;
  bool get _songActive => songLeft > 0;

  void tick(double dt) {
    if (over || dt <= 0) return;
    // Песнь действует только на свою часть шага.
    final songPart = math.min(dt, songLeft);
    final passive = gardenDps.mulNum(mute * _shieldFactor * (dt + songPart * (rules.songPassiveMultiplier - 1)));
    elapsed += dt;
    _sinceTap += dt;
    if (_sinceTap > rules.crescendoResetAfter) crescendo = 1;
    songLeft = math.max(0, songLeft - dt);
    _damage(passive);

    for (final n in List<DarkNote>.of(notes)) {
      n.t += dt / rules.noteFlightSeconds;
      if (n.t >= 1) {
        notes.remove(n);
        notesLanded += 1;
        mute = math.max(rules.muteFloor, mute - rules.mutePerNote);
      }
    }
    while (boss.notesPerVolley > 0 && elapsed >= _nextVolleyAt) {
      for (var i = 0; i < boss.notesPerVolley; i++) {
        notes.add(DarkNote(_nextNoteId++, 0.15 + 0.7 * _rng.nextDouble()));
      }
      _nextVolleyAt += boss.volleySeconds;
    }
    if (!shieldUp && elapsed >= _nextShieldAt) {
      shieldUp = true;
      shieldHitsLeft = rules.shieldHits;
      _nextShieldAt += rules.shieldEvery;
    }
    _checkEnd();
  }

  /// Тап по боссу: нота бьёт с крещендо, иногда критом.
  TapHit? tap() {
    if (over) return null;
    if (_sinceTap <= rules.crescendoWindow) {
      crescendo = math.min(rules.crescendoMax, crescendo + rules.crescendoStep);
    }
    _sinceTap = 0;
    final crit = _rng.nextDouble() < critChance;
    final damage = tapDamage.mulNum(
      crescendo * _shieldFactor * (crit ? rules.critMultiplier : 1) * (_songActive ? rules.songTapMultiplier : 1),
    );
    _damage(damage);
    songCharge = math.min(1, songCharge + rules.songChargePerTap);
    _checkEnd();
    return TapHit(damage: damage, crit: crit);
  }

  /// Лопнуть тёмную ноту. Щит на это не влияет — это награда за внимание.
  Big? pop(int noteId) {
    if (over) return null;
    final i = notes.indexWhere((n) => n.id == noteId);
    if (i < 0) return null;
    notes.removeAt(i);
    final damage = tapDamage.mulNum(rules.popDamageTaps * crescendo);
    _damage(damage);
    songCharge = math.min(1, songCharge + rules.songChargePerPop);
    _checkEnd();
    return damage;
  }

  /// Удар по трещине щита.
  bool hitCrack() {
    if (over || !shieldUp) return false;
    shieldHitsLeft -= 1;
    songCharge = math.min(1, songCharge + rules.songChargePerCrack);
    if (shieldHitsLeft <= 0) shieldUp = false;
    return true;
  }

  /// Песнь кита: мощный удар, щит разлетается, несколько секунд всё бьёт сильнее.
  Big? releaseSong() {
    if (over || !songReady) return null;
    songCharge = 0;
    songLeft = rules.songSeconds;
    shieldUp = false;
    final damage = tapDamage.mulNum(rules.songBurstTaps);
    _damage(damage);
    _checkEnd();
    return damage;
  }

  /// +секунды за рекламу: один раз за бой, в том числе сразу после того, как время вышло.
  bool addTime() {
    if (extraTimeUsed || outcome == FightOutcome.won) return false;
    extraTimeUsed = true;
    duration += rules.adExtraSeconds;
    if (outcome == FightOutcome.lost) outcome = FightOutcome.ongoing;
    return true;
  }

  /// «Пропеть» бой сразу, если сад заведомо сильнее.
  bool autoWin() {
    if (over || !canAutoWin) return false;
    hp = Big.zero;
    outcome = FightOutcome.won;
    return true;
  }

  void _damage(Big amount) {
    if (amount.isZero) return;
    final left = hp - amount;
    hp = left.isNegative ? Big.zero : left;
  }

  void _checkEnd() {
    if (hp.isZero) {
      outcome = FightOutcome.won;
    } else if (elapsed >= duration) {
      outcome = FightOutcome.lost;
    }
  }
}

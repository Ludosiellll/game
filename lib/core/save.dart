import 'dart:convert';
import 'dart:math' as math;

import 'big.dart';
import 'content.dart';
import 'game_state.dart';

/// Версия формата сохранения. Повышать при несовместимых изменениях и
/// добавлять миграцию в [decodeSave].
///
/// v2 (2026-10-07): возраст кита, годы, чешуйки, черты, стражи, бои.
const int saveVersion = 2;

String encodeSave(GameState s) => jsonEncode({
  'v': saveVersion,
  'echo': s.echo.toJson(),
  'runEarned': s.runEarned.toJson(),
  'totalEarned': s.totalEarned.toJson(),
  'region': s.region,
  'generators': s.generators,
  'birds': s.birds,
  'seeds': s.seeds.toJson(),
  'songs': s.songs,
  'memory': s.memory,
  'whales': s.whales.toList()..sort(),
  'lastSeenMs': s.lastSeenMs,
  'boostUntilMs': s.boostUntilMs,
  'taps': s.taps,
  'playSeconds': s.playSeconds,
  'createdMs': s.createdMs,
  'years': s.years,
  'age': s.age,
  'scales': s.scales,
  'traits': s.traits,
  'guardiansBeaten': s.guardiansBeaten,
  'guardianRef': s.guardianRef?.toJson(),
  'cyclePeak': s.cyclePeak.toJson(),
  'lastCyclePeak': s.lastCyclePeak.toJson(),
  'bossesMet': s.bossesMet.toList()..sort(),
  'bossWins': s.bossWins,
  'fightCooldownUntilMs': s.fightCooldownUntilMs,
  'lastSongMs': s.lastSongMs,
  'introSeen': s.introSeen,
});

/// Разбор сохранения. Возвращает `null`, если данные повреждены или созданы
/// более новой версией игры — тогда их нельзя перезаписывать молча.
GameState? decodeSave(String raw, {GameContent content = defaultContent}) {
  try {
    final data = jsonDecode(raw);
    if (data is! Map<String, dynamic>) return null;
    final version = (data['v'] as num?)?.toInt() ?? 1;
    if (version > saveVersion) return null;
    final region = (data['region'] as num? ?? 0).toInt();
    final songs = (data['songs'] as num? ?? 0).toInt();
    // v1 → v2: годы по числу песен; возраст — не меньше того, которому открыт
    // текущий регион; стражи пройденных регионов считаются побеждёнными.
    final years = (data['years'] as num?)?.toDouble() ?? songs * content.fight.yearsPerSong;
    final age = math.min((data['age'] as num?)?.toInt() ?? _ageForRegion(content, region), content.ages.length - 1);
    final guardianRef = data['guardianRef'];
    final bossesMet = {...((data['bossesMet'] as List?) ?? const []).map((e) => e.toString())};
    return GameState(
      echo: Big.fromJson(data['echo']),
      runEarned: Big.fromJson(data['runEarned']),
      totalEarned: Big.fromJson(data['totalEarned']),
      region: region,
      generators: _intMap(data['generators']),
      birds: (data['birds'] as num? ?? 0).toInt(),
      seeds: Big.fromJson(data['seeds']),
      songs: songs,
      memory: _intMap(data['memory']),
      whales: {...((data['whales'] as List?) ?? const []).map((e) => e.toString())},
      lastSeenMs: (data['lastSeenMs'] as num? ?? 0).toInt(),
      boostUntilMs: (data['boostUntilMs'] as num? ?? 0).toInt(),
      taps: (data['taps'] as num? ?? 0).toInt(),
      playSeconds: (data['playSeconds'] as num? ?? 0).toDouble(),
      createdMs: (data['createdMs'] as num? ?? 0).toInt(),
      years: years,
      age: age,
      scales: (data['scales'] as num? ?? 0).toInt(),
      traits: _intMap(data['traits']),
      guardiansBeaten: (data['guardiansBeaten'] as num?)?.toInt() ?? region,
      guardianRef: guardianRef == null ? null : Big.fromJson(guardianRef),
      cyclePeak: Big.fromJson(data['cyclePeak']),
      lastCyclePeak: Big.fromJson(data['lastCyclePeak']),
      bossesMet: bossesMet,
      bossWins: (data['bossWins'] as num?)?.toInt() ?? bossesMet.length,
      fightCooldownUntilMs: (data['fightCooldownUntilMs'] as num? ?? 0).toInt(),
      lastSongMs: (data['lastSongMs'] as num? ?? 0).toInt(),
      // В старых сохранениях отметки нет: кто уже играл, тому ролик не показываем.
      introSeen: data['introSeen'] as bool? ?? ((data['taps'] as num? ?? 0) > 0 || songs > 0),
      content: content,
    );
  } catch (_) {
    return null;
  }
}

/// Код для ручного переноса или бэкапа прогресса (base64url от JSON).
String exportCode(GameState s) => base64Url.encode(utf8.encode(encodeSave(s)));

GameState? importCode(String code, {GameContent content = defaultContent}) {
  try {
    final normalized = base64Url.normalize(code.trim());
    return decodeSave(utf8.decode(base64Url.decode(normalized)), content: content);
  } catch (_) {
    return null;
  }
}

/// Самый младший возраст, которому открыт регион `region`.
int _ageForRegion(GameContent c, int region) {
  for (var a = 0; a < c.ages.length; a++) {
    if (c.ages[a].regionsOpen > region) return a;
  }
  return c.ages.length - 1;
}

Map<String, int> _intMap(Object? v) {
  if (v is! Map) return <String, int>{};
  return {for (final e in v.entries) e.key.toString(): (e.value as num).toInt()};
}

import 'dart:convert';

import 'big.dart';
import 'content.dart';
import 'game_state.dart';

/// Версия формата сохранения. Повышать при несовместимых изменениях и
/// добавлять миграцию в [decodeSave].
const int saveVersion = 1;

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
});

/// Разбор сохранения. Возвращает `null`, если данные повреждены или созданы
/// более новой версией игры — тогда их нельзя перезаписывать молча.
GameState? decodeSave(String raw, {GameContent content = defaultContent}) {
  try {
    final data = jsonDecode(raw);
    if (data is! Map<String, dynamic>) return null;
    final version = (data['v'] as num?)?.toInt() ?? 1;
    if (version > saveVersion) return null;
    return GameState(
      echo: Big.fromJson(data['echo']),
      runEarned: Big.fromJson(data['runEarned']),
      totalEarned: Big.fromJson(data['totalEarned']),
      region: (data['region'] as num? ?? 0).toInt(),
      generators: _intMap(data['generators']),
      birds: (data['birds'] as num? ?? 0).toInt(),
      seeds: Big.fromJson(data['seeds']),
      songs: (data['songs'] as num? ?? 0).toInt(),
      memory: _intMap(data['memory']),
      whales: {...((data['whales'] as List?) ?? const []).map((e) => e.toString())},
      lastSeenMs: (data['lastSeenMs'] as num? ?? 0).toInt(),
      boostUntilMs: (data['boostUntilMs'] as num? ?? 0).toInt(),
      taps: (data['taps'] as num? ?? 0).toInt(),
      playSeconds: (data['playSeconds'] as num? ?? 0).toDouble(),
      createdMs: (data['createdMs'] as num? ?? 0).toInt(),
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

Map<String, int> _intMap(Object? v) {
  if (v is! Map) return <String, int>{};
  return {for (final e in v.entries) e.key.toString(): (e.value as num).toInt()};
}

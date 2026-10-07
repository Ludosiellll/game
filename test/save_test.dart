import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/core/big.dart';
import 'package:skywhale/core/economy.dart';

import 'test_content.dart';

import 'package:skywhale/core/save.dart';

const now = 1700000000000;

void main() {
  group('сохранение', () {
    final original = newGame(now, content: testContent)
      ..echo = Big.from(1.5) * Big.fromLog10(420)
      ..runEarned = Big.from(3e12)
      ..totalEarned = Big.from(7e15)
      ..region = 2
      ..generators.addAll({'flower': 120, 'observatory': 3})
      ..birds = 17
      ..seeds = Big.from(42)
      ..songs = 4
      ..memory.addAll({'echo': 5, 'sleep': 2})
      ..whales.addAll({'dawn', 'storm'})
      ..lastSeenMs = now + 5000
      ..boostUntilMs = now + 60000
      ..taps = 9001
      ..playSeconds = 12345.5;

    void expectSame(dynamic restored) {
      expect(restored, isNotNull);
      expect(restored.echo.exponent, 420);
      expect(restored.echo.mantissa, closeTo(1.5, 1e-12));
      expect(restored.runEarned.toDouble(), closeTo(3e12, 1));
      expect(restored.region, 2);
      expect(restored.owned('flower'), 120);
      expect(restored.owned('observatory'), 3);
      expect(restored.birds, 17);
      expect(restored.seeds.toDouble(), 42);
      expect(restored.songs, 4);
      expect(restored.memoryLevel('echo'), 5);
      expect(restored.whales, {'dawn', 'storm'});
      expect(restored.lastSeenMs, now + 5000);
      expect(restored.taps, 9001);
      expect(restored.playSeconds, closeTo(12345.5, 1e-9));
    }

    test('JSON: сохранение и загрузка возвращают то же состояние', () {
      expectSame(decodeSave(encodeSave(original)));
    });

    test('код экспорта переносит прогресс', () {
      final code = exportCode(original);
      expect(code, isNot(contains('{')));
      expectSame(importCode(code));
    });

    test('повреждённые данные не ломают загрузку', () {
      expect(decodeSave('это не json'), isNull);
      expect(decodeSave('{"v": 1, "echo": "abc"}'), isNull);
      expect(importCode('%%%'), isNull);
    });

    test('сохранение из более новой версии игры не загружается', () {
      expect(decodeSave('{"v": 999}'), isNull);
    });

    test('новая игра ещё не видела предысторию; отметка переживает сохранение', () {
      final fresh = newGame(now, content: testContent);
      expect(fresh.introSeen, isFalse);
      expect(decodeSave(encodeSave(fresh..introSeen = true), content: testContent)!.introSeen, isTrue);
    });

    test('старое сохранение без отметки: у кого есть прогресс, тому ролик не показываем', () {
      final old = encodeSave(original).replaceAll('"introSeen":false,', '').replaceAll(',"introSeen":false', '');
      expect(old, isNot(contains('introSeen')));
      expect(decodeSave(old, content: testContent)!.introSeen, isTrue);
      final empty = encodeSave(newGame(now, content: testContent))
          .replaceAll(',"introSeen":false', '')
          .replaceAll('"introSeen":false,', '');
      expect(decodeSave(empty, content: testContent)!.introSeen, isFalse);
    });
  });
}

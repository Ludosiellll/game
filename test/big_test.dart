import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/core/big.dart';

void main() {
  group('Big: арифметика за пределами double', () {
    test('2 + 3 = 5', () {
      expect((Big.from(2) + Big.from(3)).toDouble(), 5);
    });

    test('1e20 + 1 почти не меняет 1e20', () {
      expect((Big.from(1e20) + Big.one).toDouble(), 1e20);
    });

    test('1e200 × 1e200 = 1e400 (больше максимума double)', () {
      final x = Big.from(1e200) * Big.from(1e200);
      expect(x.log10(), closeTo(400, 1e-9));
    });

    test('сравнение больших и маленьких чисел', () {
      final huge = Big.fromLog10(400);
      expect(huge > Big.fromLog10(399), isTrue);
      expect(Big.from(5) < Big.from(1e300), isTrue);
      expect(Big.from(7) >= Big.from(7), isTrue);
    });

    test('1.1 в степени 1000 ≈ 2.47e41', () {
      final x = Big.from(1.1).pow(1000);
      expect(x.log10(), closeTo(41.3927, 1e-3));
    });

    test('вычитание: 10 − 3 = 7 и 1e400 − 1e399 = 9e399', () {
      expect((Big.from(10) - Big.from(3)).toDouble(), closeTo(7, 1e-12));
      final d = Big.fromLog10(400) - Big.fromLog10(399);
      expect(d.mantissa, closeTo(9, 1e-9));
      expect(d.exponent, 399);
    });

    test('деление: 1e400 / 4e399 = 2.5', () {
      final q = Big.fromLog10(400) / (Big.from(4) * Big.fromLog10(399));
      expect(q.toDouble(), closeTo(2.5, 1e-12));
    });

    test('ноль', () {
      expect(Big.from(0).isZero, isTrue);
      expect((Big.from(3) - Big.from(3)).isZero, isTrue);
      expect(Big.zero < Big.one, isTrue);
    });

    test('сохранение в строку и обратно не теряет значение', () {
      final x = Big.from(1.23) * Big.fromLog10(456);
      final y = Big.parse(x.toJson());
      expect(y.mantissa, closeTo(1.23, 1e-12));
      expect(y.exponent, 456);
    });
  });
}

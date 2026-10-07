import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/core/big.dart';
import 'package:skywhale/core/format.dart';

void main() {
  group('formatBig: короткая запись чисел', () {
    final cases = <num, String>{
      0: '0',
      7: '7',
      2.5: '2.5',
      999.9: '999',
      1000: '1.00K',
      1234: '1.23K',
      12345: '12.3K',
      123456: '123K',
      999999: '1.00M',
      1e6: '1.00M',
      4.567e9: '4.57B',
      2e12: '2.00T',
      1.5e36: '1.50e36',
    };
    cases.forEach((value, expected) {
      test('$value → $expected', () {
        expect(formatBig(Big.from(value)), expected);
      });
    });

    test('числа за пределами double пишутся через e', () {
      expect(formatBig(Big.from(3.21) * Big.fromLog10(500)), '3.21e500');
    });
  });
}

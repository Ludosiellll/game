import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:skywhale/ui/color_mix.dart';

void main() {
  group('смешивание цветов в OKLab', () {
    test('края отрезка совпадают с исходными цветами', () {
      const a = Color(0xFF7F868E);
      const b = Color(0xFF7C9BE0);
      expect(lerpOklab(a, b, 0).toARGB32(), a.toARGB32());
      expect(lerpOklab(a, b, 1).toARGB32(), b.toARGB32());
    });

    test('середина между чёрным и белым — #636363 (L = 0.5), а не #808080 как в RGB', () {
      final mid = lerpOklab(const Color(0xFF000000), const Color(0xFFFFFFFF), 0.5);
      expect((mid.r * 255).round(), closeTo(0x63, 1));
      expect((mid.g * 255).round(), closeTo(0x63, 1));
      expect((mid.b * 255).round(), closeTo(0x63, 1));
    });
  });
}

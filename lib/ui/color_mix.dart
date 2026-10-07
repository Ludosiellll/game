import 'dart:math' as math;
import 'dart:ui';

/// Смешивание цветов в перцептивном пространстве OKLab (Björn Ottosson, 2020).
/// В отличие от RGB, середина перехода «серое → цвет» не становится грязной.
Color lerpOklab(Color a, Color b, double t) {
  if (t <= 0) return a;
  if (t >= 1) return b;
  final la = _toOklab(a);
  final lb = _toOklab(b);
  final l = la[0] + (lb[0] - la[0]) * t;
  final aa = la[1] + (lb[1] - la[1]) * t;
  final bb = la[2] + (lb[2] - la[2]) * t;
  final alpha = a.a + (b.a - a.a) * t;
  return _fromOklab(l, aa, bb, alpha);
}

List<double> _toOklab(Color c) {
  final r = _toLinear(c.r);
  final g = _toLinear(c.g);
  final b = _toLinear(c.b);
  final l = _cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  final m = _cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  final s = _cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
  ];
}

Color _fromOklab(double l, double a, double b, double alpha) {
  final l1 = l + 0.3963377774 * a + 0.2158037573 * b;
  final m1 = l - 0.1055613458 * a - 0.0638541728 * b;
  final s1 = l - 0.0894841775 * a - 1.2914855480 * b;
  final ll = l1 * l1 * l1;
  final mm = m1 * m1 * m1;
  final ss = s1 * s1 * s1;
  final r = 4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss;
  final g = -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss;
  final bl = -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss;
  return Color.from(alpha: alpha.clamp(0.0, 1.0), red: _toSrgb(r), green: _toSrgb(g), blue: _toSrgb(bl));
}

double _toLinear(double c) => c <= 0.04045 ? c / 12.92 : math.pow((c + 0.055) / 1.055, 2.4).toDouble();

double _toSrgb(double c) {
  final v = c <= 0.0031308 ? 12.92 * c : 1.055 * math.pow(c, 1 / 2.4) - 0.055;
  return v.clamp(0.0, 1.0);
}

double _cbrt(double x) => x < 0 ? -math.pow(-x, 1 / 3).toDouble() : math.pow(x, 1 / 3).toDouble();

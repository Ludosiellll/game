import 'dart:math' as math;

/// Число вида `mantissa × 10^exponent` для idle-экономики: хранит значения
/// далеко за пределами `double` (1.8e308).
///
/// Нормализация: `1 <= |mantissa| < 10`, либо `mantissa == 0` (ноль).
/// Точность — как у `double` (≈ 15–16 значащих цифр); слагаемое меньше
/// другого более чем в 1e17 раз отбрасывается.
class Big implements Comparable<Big> {
  const Big._(this.mantissa, this.exponent);

  final double mantissa;
  final int exponent;

  static const Big zero = Big._(0, 0);
  static const Big one = Big._(1, 0);

  /// Порог разницы порядков, после которого меньшее слагаемое не влияет на сумму.
  static const int _maxSignificantGap = 17;

  factory Big.from(num value) {
    final v = value.toDouble();
    if (v == 0 || v.isNaN) return zero;
    if (v.isInfinite) {
      throw ArgumentError.value(value, 'value', 'Бесконечность не поддерживается');
    }
    final e = (math.log(v.abs()) / math.ln10).floor();
    return _normalize(v / _pow10(e), e);
  }

  /// Значение `10^l` (только положительные числа).
  factory Big.fromLog10(double l) {
    if (l.isNaN || l.isInfinite) {
      throw ArgumentError.value(l, 'l', 'Нужен конечный логарифм');
    }
    final e = l.floor();
    return _normalize(math.pow(10, l - e).toDouble(), e);
  }

  /// Разбор строки из [toJson] (`"1.23e456"`) или обычного числа.
  factory Big.parse(String s) {
    final t = s.trim();
    final i = t.lastIndexOf('e');
    if (i <= 0) return Big.from(double.parse(t));
    final m = double.parse(t.substring(0, i));
    final e = int.parse(t.substring(i + 1));
    return _normalize(m, e);
  }

  /// Принимает то, что лежало в JSON: строку или число.
  factory Big.fromJson(Object? v) {
    if (v == null) return zero;
    if (v is num) return Big.from(v);
    return Big.parse(v.toString());
  }

  String toJson() => '${mantissa}e$exponent';

  bool get isZero => mantissa == 0;
  bool get isNegative => mantissa < 0;

  /// Десятичный логарифм (только для положительных значений).
  double log10() {
    if (mantissa <= 0) {
      throw StateError('log10 определён только для положительных чисел');
    }
    return exponent + math.log(mantissa) / math.ln10;
  }

  double toDouble() {
    if (isZero) return 0;
    if (exponent > 308) return mantissa.isNegative ? double.negativeInfinity : double.infinity;
    if (exponent < -323) return 0;
    return mantissa * _pow10(exponent);
  }

  Big operator +(Big o) {
    if (isZero) return o;
    if (o.isZero) return this;
    final gap = exponent - o.exponent;
    if (gap > _maxSignificantGap) return this;
    if (gap < -_maxSignificantGap) return o;
    if (gap >= 0) {
      return _normalize(mantissa + o.mantissa / _pow10(gap), exponent);
    }
    return _normalize(o.mantissa + mantissa / _pow10(-gap), o.exponent);
  }

  Big operator -(Big o) => this + o.negate();

  Big operator *(Big o) {
    if (isZero || o.isZero) return zero;
    return _normalize(mantissa * o.mantissa, exponent + o.exponent);
  }

  Big operator /(Big o) {
    if (o.isZero) throw UnsupportedError('Деление на ноль');
    if (isZero) return zero;
    return _normalize(mantissa / o.mantissa, exponent - o.exponent);
  }

  Big mulNum(num k) => this * Big.from(k);
  Big divNum(num k) => this / Big.from(k);

  Big negate() => isZero ? zero : Big._(-mantissa, exponent);

  /// Возведение положительного числа в степень `p`.
  Big pow(num p) {
    if (isZero) return p == 0 ? one : zero;
    if (p == 0) return one;
    return Big.fromLog10(log10() * p.toDouble());
  }

  static Big max(Big a, Big b) => a >= b ? a : b;
  static Big min(Big a, Big b) => a <= b ? a : b;

  @override
  int compareTo(Big o) {
    final s1 = mantissa.sign;
    final s2 = o.mantissa.sign;
    if (s1 != s2) return s1 < s2 ? -1 : 1;
    if (s1 == 0) return 0;
    if (exponent != o.exponent) {
      final c = exponent < o.exponent ? -1 : 1;
      return s1 > 0 ? c : -c;
    }
    return mantissa.compareTo(o.mantissa);
  }

  bool operator <(Big o) => compareTo(o) < 0;
  bool operator <=(Big o) => compareTo(o) <= 0;
  bool operator >(Big o) => compareTo(o) > 0;
  bool operator >=(Big o) => compareTo(o) >= 0;

  @override
  bool operator ==(Object other) => other is Big && other.mantissa == mantissa && other.exponent == exponent;

  @override
  int get hashCode => Object.hash(mantissa, exponent);

  @override
  String toString() => toJson();

  static Big _normalize(double m, int e) {
    if (m == 0 || m.isNaN) return zero;
    if (m.isInfinite) throw StateError('Переполнение мантиссы');
    var mm = m;
    var ee = e;
    // Сдвиг на нужное число порядков за один шаг, затем доводка из-за округлений.
    final shift = (math.log(mm.abs()) / math.ln10).floor();
    if (shift != 0) {
      mm = mm / _pow10(shift);
      ee += shift;
    }
    while (mm.abs() >= 10) {
      mm /= 10;
      ee += 1;
    }
    while (mm.abs() < 1) {
      mm *= 10;
      ee -= 1;
    }
    return Big._(mm, ee);
  }

  // База обязательно double: math.pow(int, int) в Dart возвращает int и
  // переполняется после 10^18.
  static double _pow10(int n) => math.pow(10.0, n).toDouble();
}

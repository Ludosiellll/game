import 'big.dart';

const List<String> _suffixes = ['', 'K', 'M', 'B', 'T', 'Qa', 'Qi', 'Sx', 'Sp', 'Oc', 'No', 'Dc'];

/// Короткая запись числа для интерфейса.
///
/// * меньше 10 с дробной частью — одна цифра после точки (`2.5`);
/// * меньше 1000 — целое без округления вверх (`999`);
/// * до 1e36 — три значащие цифры и суффикс (`1.23K`, `45.6M`, `789B`);
/// * дальше — научная запись (`1.50e36`).
String formatBig(Big x) {
  if (x.isZero) return '0';
  if (x.isNegative) return '-${formatBig(x.negate())}';

  if (x.exponent < 3) {
    final v = x.toDouble();
    if (v < 10 && v != v.truncateToDouble()) {
      return ((v * 10).floor() / 10).toStringAsFixed(1);
    }
    return v.floor().toString();
  }

  var group = x.exponent ~/ 3;
  var scaled = x.mantissa * _pow10Small(x.exponent - group * 3);
  var decimals = _decimalsFor(scaled);
  // Округление может перескочить в следующую тысячу: 999.999K → 1.00M.
  if (double.parse(scaled.toStringAsFixed(decimals)) >= 1000) {
    group += 1;
    scaled /= 1000;
    decimals = _decimalsFor(scaled);
  }
  if (group < _suffixes.length) {
    return '${scaled.toStringAsFixed(decimals)}${_suffixes[group]}';
  }
  return _scientific(x);
}

/// Запись в секунду: `+1.23K/с`.
String formatRate(Big perSecond) => '${formatBig(perSecond)}/с';

String _scientific(Big x) {
  var m = double.parse(x.mantissa.toStringAsFixed(2));
  var e = x.exponent;
  if (m >= 10) {
    m /= 10;
    e += 1;
  }
  return '${m.toStringAsFixed(2)}e$e';
}

int _decimalsFor(double scaled) {
  if (scaled < 10) return 2;
  if (scaled < 100) return 1;
  return 0;
}

double _pow10Small(int n) {
  switch (n) {
    case 0:
      return 1;
    case 1:
      return 10;
    default:
      return 100;
  }
}

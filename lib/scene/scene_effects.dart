import 'dart:math' as math;
import 'dart:ui';

/// Короткоживущие эффекты сцены: волны от нот, всплывающие числа, лепестки.
/// Обновляются каждый кадр и не сохраняются.
class Ripple {
  Ripple(this.center);
  final Offset center;
  double age = 0;
  static const double life = 0.9;
  bool get dead => age >= life;
}

class FloatingText {
  FloatingText(this.position, this.text);
  final Offset position;
  final String text;
  double age = 0;
  static const double life = 1.1;
  bool get dead => age >= life;
}

class Petal {
  Petal({required this.position, required this.velocity, required this.color, required this.spin});
  Offset position;
  Offset velocity;
  final Color color;
  final double spin;
  double age = 0;
  static const double life = 1.4;
  bool get dead => age >= life;
}

class SceneEffects {
  SceneEffects({math.Random? random}) : _random = random ?? math.Random();

  final math.Random _random;
  final List<Ripple> ripples = [];
  final List<FloatingText> texts = [];
  final List<Petal> petals = [];

  /// Время сцены в секундах (для анимаций без состояния: волна тела, облака).
  double time = 0;

  static const int _maxRipples = 24;
  static const int _maxTexts = 18;
  static const int _maxPetals = 90;

  static const List<Color> _petalColors = [Color(0xFFFFC8A2), Color(0xFFFFE29A), Color(0xFFF7A1B5), Color(0xFFBDE0FE)];

  void addTap(Offset at, String label) {
    ripples.add(Ripple(at));
    texts.add(FloatingText(at, label));
    for (var i = 0; i < 4; i++) {
      final angle = _random.nextDouble() * math.pi * 2;
      final speed = 40 + _random.nextDouble() * 70;
      petals.add(
        Petal(
          position: at,
          velocity: Offset(math.cos(angle) * speed, math.sin(angle) * speed - 30),
          color: _petalColors[_random.nextInt(_petalColors.length)],
          spin: (_random.nextDouble() - 0.5) * 6,
        ),
      );
    }
    _trim(ripples, _maxRipples);
    _trim(texts, _maxTexts);
    _trim(petals, _maxPetals);
  }

  void update(double dt) {
    time += dt;
    for (final r in ripples) {
      r.age += dt;
    }
    for (final t in texts) {
      t.age += dt;
    }
    for (final p in petals) {
      p.age += dt;
      p.velocity = Offset(p.velocity.dx * (1 - 1.5 * dt), p.velocity.dy * (1 - 1.5 * dt) + 25 * dt);
      p.position += p.velocity * dt;
    }
    ripples.removeWhere((r) => r.dead);
    texts.removeWhere((t) => t.dead);
    petals.removeWhere((p) => p.dead);
  }

  static void _trim<T>(List<T> list, int max) {
    if (list.length > max) list.removeRange(0, list.length - max);
  }
}

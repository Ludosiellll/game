import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/widgets.dart';

import '../core/economy.dart' as eco;
import '../core/game_state.dart';
import '../ui/color_mix.dart';
import '../ui/palette.dart';
import 'garden_glyphs.dart';
import 'scene_effects.dart';

/// Рисует сцену целиком кодом: небо, облака, кита сверху, сад на его спине,
/// птиц, проснувшихся китов стаи и эффекты тапов.
///
/// Арт-направление «Акварельная дымка»: мягкие градиенты без обводок, цвет
/// возвращается вместе с прогрессом региона, кит — тяжёлый и спокойный.
class SkyScenePainter extends CustomPainter {
  SkyScenePainter({required this.stateOf, required this.effects, required Listenable repaint})
    : super(repaint: repaint);

  final GameState Function() stateOf;
  final SceneEffects effects;

  @override
  void paint(Canvas canvas, Size size) {
    final s = stateOf();
    final t = effects.time;
    final look = Palette.region(s.region);
    final mix = _ease(eco.regionProgress(s));
    final p = look.at(mix);
    // Сад всегда немного ярче неба: он и возвращает цвет.
    final garden = look.at(0.35 + 0.65 * mix);

    _paintSky(canvas, size, p, mix, t);
    if (s.region == 2) _paintStars(canvas, size, mix, t);
    _paintClouds(canvas, size, p, t, far: true);

    final layout = WhaleLayout.forScene(size);
    _paintHerd(canvas, size, layout, s, t);
    _paintShadow(canvas, layout);
    final whale = WhaleShape(cx: layout.cx, top: layout.top, length: layout.length, width: layout.width, time: t);
    paintWhale(canvas, whale, WhaleColors(back: p.whaleBack, rim: p.whaleRim), t);
    _paintGarden(canvas, whale, s, garden, t);
    _paintBirds(canvas, layout, s, p, mix, t);
    _paintClouds(canvas, size, p, t, far: false);
    _paintEffects(canvas, p);
  }

  @override
  bool shouldRepaint(covariant SkyScenePainter oldDelegate) => true;

  // --- небо -----------------------------------------------------------------

  void _paintSky(Canvas canvas, Size size, ScenePalette p, double mix, double t) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..shader = ui.Gradient.linear(
          rect.topCenter,
          rect.bottomCenter,
          [p.skyTop, lerpOklab(p.skyTop, p.skyBottom, 0.55), p.skyBottom],
          const [0, 0.5, 1],
        ),
    );
    // Акварельные пятна: большие мягкие кляксы медленно дрейфуют.
    final blobs = [
      (0.18, 0.22, 0.55, p.glow, 0.20),
      (0.85, 0.35, 0.50, p.bloom, 0.10 + 0.08 * mix),
      (0.25, 0.78, 0.60, p.cloud, 0.18),
      (0.78, 0.86, 0.45, p.glow, 0.12 + 0.10 * mix),
    ];
    for (final (fx, fy, fr, color, alpha) in blobs) {
      final c = Offset(
        size.width * (fx + 0.03 * math.sin(t * 0.05 + fx * 6)),
        size.height * (fy + 0.02 * math.cos(t * 0.04 + fy * 5)),
      );
      final r = size.width * fr;
      canvas.drawCircle(
        c,
        r,
        Paint()..shader = ui.Gradient.radial(c, r, [color.withValues(alpha: alpha), color.withValues(alpha: 0)]),
      );
    }
  }

  void _paintStars(Canvas canvas, Size size, double mix, double t) {
    final rng = math.Random(23);
    for (var i = 0; i < 46; i++) {
      final x = rng.nextDouble() * size.width;
      final y = rng.nextDouble() * size.height * 0.7;
      final twinkle = 0.5 + 0.5 * math.sin(t * (1 + rng.nextDouble() * 2) + i);
      final alpha = (0.15 + 0.6 * mix) * twinkle;
      canvas.drawCircle(
        Offset(x, y),
        0.8 + rng.nextDouble() * 1.2,
        Paint()..color = const Color(0xFFFFF6E0).withValues(alpha: alpha),
      );
    }
  }

  void _paintClouds(Canvas canvas, Size size, ScenePalette p, double t, {required bool far}) {
    final rng = math.Random(far ? 7 : 11);
    final count = far ? 7 : 4;
    final speed = far ? 9.0 : 22.0;
    final baseAlpha = far ? 0.5 : 0.24;
    for (var i = 0; i < count; i++) {
      final scale = (far ? 0.6 : 1.0) * (0.7 + rng.nextDouble() * 0.6);
      final cloudHeight = 90 * scale;
      final span = size.height + cloudHeight * 2;
      final x = rng.nextDouble() * size.width;
      final y0 = rng.nextDouble() * span;
      final y = (y0 + t * speed * scale) % span - cloudHeight;
      _paintCloud(canvas, Offset(x, y), 70 * scale, p.cloud.withValues(alpha: baseAlpha), rng.nextInt(1 << 20));
    }
  }

  void _paintCloud(Canvas canvas, Offset c, double r, Color color, int seed) {
    final rng = math.Random(seed);
    for (var k = 0; k < 5; k++) {
      final pos = c + Offset((rng.nextDouble() - 0.5) * r * 1.8, (rng.nextDouble() - 0.5) * r * 0.7);
      final rr = r * (0.45 + rng.nextDouble() * 0.4);
      canvas.drawCircle(pos, rr, Paint()..shader = ui.Gradient.radial(pos, rr, [color, color.withValues(alpha: 0)]));
    }
  }

  // --- кит и стая -------------------------------------------------------------

  void _paintShadow(Canvas canvas, WhaleLayout l) {
    final c = Offset(l.cx + l.width * 0.32, l.top + l.length * 0.56);
    canvas.drawOval(
      Rect.fromCenter(center: c, width: l.width * 1.05, height: l.length * 0.82),
      Paint()
        ..color = const Color(0xFF1B2030).withValues(alpha: 0.14)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 26),
    );
  }

  static const _herd = [
    ('dawn', 0.14, 0.30, WhaleColors(back: Color(0xFFB9705C), rim: Color(0xFFF6D3C2))),
    ('storm', 0.86, 0.58, WhaleColors(back: Color(0xFF2C6676), rim: Color(0xFFB8E4EC))),
    ('night', 0.15, 0.80, WhaleColors(back: Color(0xFF1D2150), rim: Color(0xFF9DFFD0), glowSpots: true)),
  ];

  void _paintHerd(Canvas canvas, Size size, WhaleLayout l, GameState s, double t) {
    for (final (id, fx, fy, colors) in _herd) {
      if (!s.whales.contains(id)) continue;
      final length = l.length * 0.34;
      final shape = WhaleShape(
        cx: size.width * fx,
        top: size.height * fy - length * 0.45,
        length: length,
        width: length * 0.31,
        time: t * 0.85 + fx * 3,
      );
      paintWhale(canvas, shape, colors, t + fx * 2);
    }
  }

  // --- сад на спине -----------------------------------------------------------

  static const List<(double, double)> _slots = [
    (0.36, 0.0),
    (0.43, -0.52),
    (0.43, 0.52),
    (0.29, -0.46),
    (0.29, 0.46),
    (0.50, 0.0),
    (0.57, -0.48),
    (0.57, 0.48),
    (0.36, -0.62),
    (0.36, 0.62),
    (0.64, 0.0),
    (0.50, -0.64),
    (0.50, 0.64),
    (0.70, -0.42),
    (0.70, 0.42),
    (0.43, 0.0),
    (0.29, 0.0),
    (0.64, -0.5),
    (0.64, 0.5),
    (0.76, 0.0),
    (0.57, 0.0),
  ];

  static const List<int> _visualCaps = [6, 5, 4, 3, 3, 2];

  void _paintGarden(Canvas canvas, WhaleShape w, GameState s, ScenePalette g, double t) {
    final items = <int>[];
    final gens = s.content.generators;
    for (var tier = gens.length - 1; tier >= 0; tier--) {
      final owned = s.owned(gens[tier].id);
      if (owned == 0) continue;
      final cap = _visualCaps[tier.clamp(0, _visualCaps.length - 1)];
      final visible = math.min(cap, (math.log(owned + 1) / math.ln2).ceil());
      for (var i = 0; i < visible; i++) {
        items.add(tier);
      }
    }
    final unit = w.width * 0.075;
    for (var i = 0; i < items.length && i < _slots.length; i++) {
      final (st, side) = _slots[i];
      final pos = w.center(st) + w.normal(st) * (side * (w.halfWidth(st) * 0.84 - unit * 1.1));
      canvas.save();
      canvas.translate(pos.dx, pos.dy);
      canvas.rotate(math.sin(t * 1.1 + i) * 0.1);
      paintGardenGlyph(canvas, items[i], unit, t, i, g);
      canvas.restore();
    }
  }

  // --- птицы ------------------------------------------------------------------

  void _paintBirds(Canvas canvas, WhaleLayout l, GameState s, ScenePalette p, double mix, double t) {
    final count = math.min(s.birds, 12);
    final center = Offset(l.cx, l.top + l.length * 0.45);
    final color = lerpOklab(const Color(0xFFB9BBBF), const Color(0xFFFFFFFF), mix);
    final paint = Paint()
      ..color = color.withValues(alpha: 0.92)
      ..strokeWidth = 1.8
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    for (var i = 0; i < count; i++) {
      final rx = l.width * 0.95 + i * l.width * 0.1;
      final ry = l.length * 0.36 + (i % 3) * l.width * 0.12;
      final dir = i.isEven ? 1.0 : -1.0;
      final speed = (0.2 + (i % 4) * 0.045) * dir;
      final a = t * speed + i * 0.9;
      final pos = center + Offset(math.cos(a) * rx, math.sin(a) * ry);
      // Птица — узнаваемая «галочка» под любым углом полёта: не поворачиваем
      // её по курсу, только слегка наклоняем в сторону движения.
      final vx = -math.sin(a) * rx * dir;
      // 60–70% времени птица парит, затем делает несколько взмахов.
      final cycle = (t * 0.35 + i * 0.37) % 1.0;
      final lift = cycle > 0.65 ? math.sin(t * 13 + i) : 0.35;
      final size = l.width * 0.07;
      canvas.save();
      canvas.translate(pos.dx, pos.dy);
      canvas.rotate((vx.sign) * 0.18);
      for (final side in [-1.0, 1.0]) {
        final tip = Offset(side * size, -lift * size * 0.5);
        final ctrl = Offset(side * size * 0.5, -size * 0.55 - lift * size * 0.15);
        canvas.drawPath(
          Path()
            ..moveTo(0, 0)
            ..quadraticBezierTo(ctrl.dx, ctrl.dy, tip.dx, tip.dy),
          paint,
        );
      }
      canvas.restore();
    }
  }

  // --- эффекты тапа -------------------------------------------------------------

  void _paintEffects(Canvas canvas, ScenePalette p) {
    for (final r in effects.ripples) {
      final k = r.age / Ripple.life;
      final radius = 14 + k * 84;
      canvas.drawCircle(
        r.center,
        radius,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3.5 * (1 - k)
          ..color = p.glow.withValues(alpha: 0.9 * (1 - k)),
      );
      canvas.drawCircle(
        r.center,
        radius * 0.6,
        Paint()
          ..shader = ui.Gradient.radial(r.center, radius * 0.6, [
            p.glow.withValues(alpha: 0.25 * (1 - k)),
            p.glow.withValues(alpha: 0),
          ]),
      );
    }
    for (final petal in effects.petals) {
      final k = petal.age / Petal.life;
      canvas.save();
      canvas.translate(petal.position.dx, petal.position.dy);
      canvas.rotate(petal.spin * petal.age);
      canvas.drawOval(
        const Rect.fromLTWH(-4, -2.5, 8, 5),
        Paint()..color = lerpOklab(petal.color, p.bloom, 0.5).withValues(alpha: 1 - k),
      );
      canvas.restore();
    }
    for (final ft in effects.texts) {
      final k = ft.age / FloatingText.life;
      final painter = TextPainter(
        text: TextSpan(
          text: ft.text,
          style: TextStyle(
            fontFamily: 'Nunito',
            color: const Color(0xFFFFFFFF).withValues(alpha: 1 - k),
            fontSize: 18,
            fontWeight: FontWeight.w800,
            shadows: [Shadow(color: const Color(0xFF1B2030).withValues(alpha: 0.45 * (1 - k)), blurRadius: 5)],
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout();
      painter.paint(canvas, ft.position + Offset(-painter.width / 2, -28 - k * 62));
    }
  }

  static double _ease(double x) => x * x * (3 - 2 * x);
}

/// Положение и размер главного кита в сцене.
class WhaleLayout {
  const WhaleLayout({required this.cx, required this.top, required this.length, required this.width});

  factory WhaleLayout.forScene(Size size) {
    final length = size.height * 0.7;
    final width = math.min(length * 0.34, size.width * 0.44);
    return WhaleLayout(cx: size.width / 2, top: size.height * 0.1, length: length, width: width);
  }

  final double cx;
  final double top;
  final double length;
  final double width;
}

class WhaleColors {
  const WhaleColors({required this.back, required this.rim, this.glowSpots = false});

  final Color back;

  /// Брюхо, видимое каймой, и плавники.
  final Color rim;
  final bool glowSpots;
}

/// Форма кита сверху: контур строится вдоль изгибающегося хребта.
///
/// Пропорции по правилам арт-направления: голова ≈ треть длины, широкая и
/// тупая; хвостовой стебель ≈ 10% ширины; размах хвоста ≈ ширине тела;
/// длинные плавники как у горбача (≈ 30% длины).
class WhaleShape {
  WhaleShape({required this.cx, required this.top, required this.length, required this.width, required this.time});

  final double cx;
  final double top;
  final double length;
  final double width;
  final double time;

  /// Период волны тела, секунды. Кит тяжёлый — волна медленная.
  static const double wavePeriod = 5;

  /// «Дыхание»: ±1.5% ширины с периодом 4 с.
  double get _breath => 1 + 0.015 * math.sin(time * 2 * math.pi / 4);

  /// Точка хребта: `t` от 0 (кончик головы) до 1 (основание хвоста).
  Offset center(double t) {
    final amp = width * 0.14;
    final x = cx + amp * math.sin(2 * math.pi * (t * 0.85) - time * 2 * math.pi / wavePeriod) * t * t;
    return Offset(x, top + t * length);
  }

  Offset tangent(double t) {
    final a = center(t - 0.004);
    final b = center(t + 0.004);
    final d = b - a;
    return d / d.distance;
  }

  /// Нормаль влево от направления движения.
  Offset normal(double t) {
    final tg = tangent(t);
    return Offset(tg.dy, -tg.dx);
  }

  double halfWidth(double t) {
    final w = width / 2 * _breath;
    if (t < 0.22) {
      // Тупая широкая голова: суперэллипс.
      final u = (0.22 - t) / 0.22;
      return w * math.pow(math.max(0, 1 - math.pow(u, 2.2)), 1 / 2.2);
    }
    if (t < 0.40) return w * (1 + 0.02 * math.sin((t - 0.22) / 0.18 * math.pi));
    if (t < 0.88) {
      final u = (t - 0.40) / 0.48;
      return w * (1 - 0.9 * u * u * (3 - 2 * u));
    }
    final u = (t - 0.88) / 0.12;
    return w * 0.1 * (1 - 0.3 * u);
  }

  Path body({double scale = 1}) {
    const n = 80;
    final left = <Offset>[];
    final right = <Offset>[];
    for (var i = 0; i <= n; i++) {
      final t = 0.003 + i / n * 0.975;
      final c = center(t);
      final nm = normal(t);
      final h = halfWidth(t) * scale;
      left.add(c + nm * h);
      right.add(c - nm * h);
    }
    final path = Path()..moveTo(left.first.dx, left.first.dy);
    for (final p in left.skip(1)) {
      path.lineTo(p.dx, p.dy);
    }
    for (final p in right.reversed) {
      path.lineTo(p.dx, p.dy);
    }
    return path..close();
  }

  Path flukes() {
    const t = 0.975;
    final c = center(t);
    final tg = tangent(t);
    final nm = normal(t);
    final w = width;
    final path = Path();
    for (final side in [1.0, -1.0]) {
      final n = nm * side;
      Offset at(double across, double along) => c + n * (w * across) + tg * (w * along);
      final tip = at(0.56, 0.17);
      final c1 = at(0.22, -0.02);
      final c2 = at(0.50, 0.02);
      final c3 = at(0.40, 0.12);
      final c4 = at(0.16, 0.18);
      final notch = at(0.0, 0.08);
      path
        ..moveTo(c.dx, c.dy)
        ..cubicTo(c1.dx, c1.dy, c2.dx, c2.dy, tip.dx, tip.dy)
        ..cubicTo(c3.dx, c3.dy, c4.dx, c4.dy, notch.dx, notch.dy)
        ..close();
    }
    return path;
  }

  /// Длинный плавник-весло, как у горбача. `phase` отстаёт от волны тела —
  /// плавники «тянутся» за кивком корпуса.
  Path fin(double side, double phase) {
    const t = 0.31;
    final c = center(t);
    final tg = tangent(t);
    final nm = normal(t) * side;
    final base = c + nm * (halfWidth(t) * 0.78);
    final angle = 0.95 + math.sin(phase) * 0.12;
    final dir = nm * math.cos(angle) + tg * math.sin(angle);
    final perp = Offset(-dir.dy, dir.dx) * side;
    final len = length * 0.29;
    final tip = base + dir * len;
    // Передний край — плавная дуга, задний — с лёгкой волной, кончик округлый.
    final front1 = base + dir * (len * 0.35) - perp * (width * 0.13);
    final front2 = base + dir * (len * 0.85) - perp * (width * 0.1);
    final tipRound = tip + dir * (width * 0.04);
    final back1 = base + dir * (len * 0.8) + perp * (width * 0.07);
    final back2 = base + dir * (len * 0.3) + perp * (width * 0.12);
    final root = base + tg * (width * 0.2);
    return Path()
      ..moveTo(base.dx, base.dy)
      ..cubicTo(front1.dx, front1.dy, front2.dx, front2.dy, tipRound.dx, tipRound.dy)
      ..cubicTo(back1.dx, back1.dy, back2.dx, back2.dy, root.dx, root.dy)
      ..close();
  }
}

/// Нарисовать кита по форме и цветам (используется и для стаи).
void paintWhale(Canvas canvas, WhaleShape w, WhaleColors colors, double t) {
  final finPhase = (t - 0.4) * 2 * math.pi / WhaleShape.wavePeriod;
  final rimPaint = Paint()..color = colors.rim;
  canvas.drawPath(w.fin(1, finPhase), rimPaint);
  canvas.drawPath(w.fin(-1, finPhase + 0.5), rimPaint);
  canvas.drawPath(w.flukes(), Paint()..color = colors.back);

  // Кайма брюха (противотень), затем тёмная спина.
  canvas.drawPath(w.body(), rimPaint);
  final back = w.body(scale: 0.84);
  final bounds = back.getBounds();
  final edge = lerpOklab(colors.back, colors.rim, 0.22);
  canvas.drawPath(
    back,
    Paint()
      ..shader = ui.Gradient.linear(
        Offset(bounds.left, 0),
        Offset(bounds.right, 0),
        [edge, colors.back, colors.back, edge],
        const [0, 0.3, 0.7, 1],
      ),
  );

  // Мягкий блик со стороны света (сверху слева).
  final highlight = w.center(0.24) + w.normal(0.24) * (w.halfWidth(0.24) * 0.35);
  final hr = w.width * 0.42;
  canvas.drawCircle(
    highlight,
    hr,
    Paint()
      ..shader = ui.Gradient.radial(highlight, hr, [
        const Color(0xFFFFFFFF).withValues(alpha: 0.13),
        const Color(0x00FFFFFF),
      ]),
  );

  // Пятна-«ракушки»: на них потом вырастают цветы.
  final rng = math.Random(5);
  for (var i = 0; i < 14; i++) {
    final st = 0.12 + rng.nextDouble() * 0.66;
    final side = (rng.nextDouble() - 0.5) * 1.3;
    final r = w.width * (0.018 + rng.nextDouble() * 0.026);
    final p = w.center(st) + w.normal(st) * (side * w.halfWidth(st) * 0.8);
    if (colors.glowSpots) {
      final pulse = 0.55 + 0.45 * math.sin(t * 2 + i);
      canvas.drawCircle(
        p,
        r * 2.8,
        Paint()
          ..shader = ui.Gradient.radial(p, r * 2.8, [
            colors.rim.withValues(alpha: 0.55 * pulse),
            colors.rim.withValues(alpha: 0),
          ]),
      );
    }
    canvas.drawCircle(p, r, Paint()..color = colors.rim.withValues(alpha: colors.glowSpots ? 0.85 : 0.16));
  }

  // Дыхало: две короткие черты.
  final blow = w.center(0.09);
  final dash = Paint()
    ..color = Palette.eye.withValues(alpha: 0.6)
    ..strokeWidth = w.width * 0.022
    ..strokeCap = StrokeCap.round;
  for (final side in [-1.0, 1.0]) {
    final p = blow + w.normal(0.09) * (side * w.width * 0.035);
    canvas.drawLine(p - w.tangent(0.09) * (w.width * 0.025), p + w.tangent(0.09) * (w.width * 0.025), dash);
  }

  // Глаза: на контуре головы, маленькие (≈ 7.5% ширины), с тяжёлым веком.
  const eyeT = 0.18;
  final r = w.width * 0.0375;
  final cover = _lidCover(t);
  for (final side in [1.0, -1.0]) {
    final p = w.center(eyeT) + w.normal(eyeT) * (side * (w.halfWidth(eyeT) - r * 0.4));
    canvas.save();
    canvas.translate(p.dx, p.dy);
    final eyeRect = Rect.fromCircle(center: Offset.zero, radius: r);
    canvas.drawOval(eyeRect, Paint()..color = Palette.eye);
    canvas.drawCircle(Offset(-r * 0.32, r * 0.05), r * 0.25, Paint()..color = const Color(0xFFFFFFFF));
    canvas.clipPath(Path()..addOval(eyeRect.inflate(0.5)));
    canvas.drawRect(Rect.fromLTRB(-r - 1, -r - 1, r + 1, -r + 2 * r * cover), Paint()..color = colors.back);
    canvas.restore();
  }
}

/// Доля глаза под верхним веком: обычно 40%, моргание раз в ≈ 5.3 с
/// (закрыть за 0.1 с, открыть за 0.2 с).
double _lidCover(double t) {
  const rest = 0.4;
  final phase = t % 5.3;
  if (phase < 0.1) return rest + (1 - rest) * (phase / 0.1);
  if (phase < 0.3) return 1 - (1 - rest) * ((phase - 0.1) / 0.2);
  return rest;
}

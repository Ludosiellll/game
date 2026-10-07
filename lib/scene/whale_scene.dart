import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import '../core/game_state.dart';
import '../ui/juicy.dart';

/// Сцена: небо, облака, кит по слоям с анимацией, сад на спине, птицы, отклик
/// на тапы. Владеет игровым циклом: каждый кадр продвигает экономику.
class WhaleScene extends StatefulWidget {
  const WhaleScene({super.key, required this.controller, required this.atlas, this.centerY = 0.5});

  final GameController controller;
  final SpriteAtlas atlas;

  /// Где по высоте экрана центр кита (0…1).
  final double centerY;

  @override
  State<WhaleScene> createState() => _WhaleSceneState();
}

class _WhaleSceneState extends State<WhaleScene> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final _Repaint _repaint = _Repaint();
  final SceneFx fx = SceneFx();
  final math.Random _rng = math.Random();
  StreamSubscription<TapEffect>? _taps;
  Duration _last = Duration.zero;

  double t = 0;
  double mouthUntil = 0;
  double blinkUntil = 0;
  double _nextBlink = 2.5;

  /// Когда постройка на месте `i` появилась (для анимации «выскакивания»).
  final Map<int, double> appeared = {};
  int _age = -1;

  GameState get state => widget.controller.state;
  AgeArt get age => widget.atlas.age(state.age);

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick)..start();
    _taps = widget.controller.taps.listen((e) {
      fx.addTap(Offset(e.x, e.y), '+${formatBig(e.value)}', _rng);
      mouthUntil = t + 0.22;
    });
  }

  void _onTick(Duration elapsed) {
    final dt = _last == Duration.zero ? 0.0 : (elapsed - _last).inMicroseconds / 1e6;
    _last = elapsed;
    final step = dt.clamp(0.0, 0.1);
    widget.controller.onFrame(dt);
    t += step;
    if (t >= _nextBlink) {
      blinkUntil = t + 0.13;
      _nextBlink = t + 2.5 + _rng.nextDouble() * 4;
    }
    _updateGarden();
    fx.update(step);
    _repaint.tick();
  }

  void _updateGarden() {
    final s = state;
    if (s.age != _age) {
      _age = s.age;
      appeared.clear();
      for (var i = 0; i < age.slots.length; i++) {
        if (slotVisible(age.slots[i], s)) appeared[i] = -10; // уже стояли — без анимации
      }
      return;
    }
    final slots = age.slots;
    for (var i = 0; i < slots.length; i++) {
      final visible = slotVisible(slots[i], s);
      if (visible && !appeared.containsKey(i)) appeared[i] = t;
      if (!visible) appeared.remove(i);
    }
  }

  @override
  void dispose() {
    _taps?.cancel();
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) => widget.controller.tapAt(e.localPosition.dx, e.localPosition.dy),
      child: RepaintBoundary(
        child: CustomPaint(painter: _ScenePainter(this, _repaint), size: Size.infinite),
      ),
    );
  }
}

class _Repaint extends ChangeNotifier {
  void tick() => notifyListeners();
}

/// Стоит ли предмет сада: декор стоит всегда, остальное — с нужного числа растений.
bool slotVisible(GardenSlot slot, GameState s) => slot.gen == null || s.owned(slot.gen!) >= slot.need;

/// Предмет сада в координатах кадра кита; [pop] — масштаб анимации появления.
void paintGardenItem(Canvas canvas, AgeArt age, GardenSlot slot, ItemArt art, Paint paint, {double pop = 1}) {
  canvas.save();
  canvas.translate(slot.pos.dx, slot.pos.dy);
  canvas.scale(slot.scale * age.ppu / art.ppu * pop);
  canvas.translate(-art.anchor.dx, -art.anchor.dy);
  canvas.drawImage(art.image, Offset.zero, paint);
  canvas.restore();
}

/// Тёмный двойник кита — Тень кита.
const ColorFilter shadowWhaleFilter = ColorFilter.matrix([
  0.2, 0, 0, 0, 18, //
  0, 0.12, 0, 0, 8,
  0, 0, 0.4, 0, 36,
  0, 0, 0, 1, 0,
]);

/// Кит целиком без анимаций, в координатах кадра: плавники, хвост, тело и сад
/// на спине. У тёмного двойника вместо глаз — свет.
void paintWhaleStill(Canvas canvas, SpriteAtlas atlas, AgeArt age, GameState s, {bool dark = false}) {
  final paint = Paint()..filterQuality = FilterQuality.medium;
  if (dark) paint.colorFilter = shadowWhaleFilter;
  for (final l in [age.finFar, age.fluke, age.body, age.finNear]) {
    canvas.drawImageRect(
      l.image,
      Rect.fromLTWH(0, 0, l.image.width.toDouble(), l.image.height.toDouble()),
      l.rect,
      paint,
    );
  }
  for (final slot in age.slots) {
    final art = atlas.items[slot.item];
    if (art != null && slotVisible(slot, s)) paintGardenItem(canvas, age, slot, art, paint);
  }
  if (!dark) return;
  for (final (c, r) in age.eyes) {
    canvas.drawCircle(
      c,
      r * 1.8,
      Paint()..shader = ui.Gradient.radial(c, r * 1.8, [const Color(0xCCFF5AE6), const Color(0x00FF5AE6)]),
    );
    canvas.drawCircle(c, r * 0.55, Paint()..color = const Color(0xFFFFD2FA));
  }
}

/// Прямоугольник кита вместе с садом, в координатах кадра.
Rect whaleStillBounds(SpriteAtlas atlas, AgeArt age, GameState s) {
  var b = age.bounds;
  for (final slot in age.slots) {
    final art = atlas.items[slot.item];
    if (art == null || !slotVisible(slot, s)) continue;
    final k = slot.scale * age.ppu / art.ppu;
    b = b.expandToInclude(
      Rect.fromLTWH(
        slot.pos.dx - art.anchor.dx * k,
        slot.pos.dy - art.anchor.dy * k,
        art.image.width * k,
        art.image.height * k,
      ),
    );
  }
  return b;
}

/// Портрет кита с садом, вписанный в квадрат: для листов, окон и кнопок.
class WhalePortrait extends StatelessWidget {
  const WhalePortrait({
    super.key,
    required this.atlas,
    required this.state,
    required this.age,
    this.size = 96,
    this.dark = false,
  });

  final SpriteAtlas atlas;
  final GameState state;
  final int age;
  final double size;
  final bool dark;

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: size,
    child: CustomPaint(painter: _PortraitPainter(atlas, state, age, dark)),
  );
}

class _PortraitPainter extends CustomPainter {
  _PortraitPainter(this.atlas, this.state, this.age, this.dark);

  final SpriteAtlas atlas;
  final GameState state;
  final int age;
  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final art = atlas.age(age);
    final b = whaleStillBounds(atlas, art, state);
    final k = math.min(size.width / b.width, size.height / b.height);
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    canvas.scale(k);
    canvas.translate(-b.center.dx, -b.center.dy);
    paintWhaleStill(canvas, atlas, art, state, dark: dark);
    canvas.restore();
  }

  // Сад растёт между перерисовками листа — рисовать заново дёшево.
  @override
  bool shouldRepaint(_PortraitPainter old) => true;
}

/// Цвет неба по региону; пока регион не восстановлен, небо сероватое.
const List<List<Color>> _skies = [
  [Color(0xFF52B4FF), Color(0xFFCFF0FF)],
  [Color(0xFF5C74E8), Color(0xFFC6CEFF)],
  [Color(0xFF4A3FA8), Color(0xFFA2E2EA)],
];
const Color _greyTop = Color(0xFF8E9AB2);
const Color _greyBottom = Color(0xFFD6DAE4);

class _ScenePainter extends CustomPainter {
  _ScenePainter(this.s, Listenable repaint) : super(repaint: repaint);

  final _WhaleSceneState s;
  final Paint _paint = Paint()..filterQuality = FilterQuality.medium;

  @override
  void paint(Canvas canvas, Size size) {
    final state = s.state;
    final age = s.age;
    final atlas = s.widget.atlas;
    final world = math.pow(0.85 / age.scale, 0.8).toDouble();
    _sky(canvas, size, state);
    _clouds(canvas, size, atlas, world, back: true);

    final b = age.bounds.inflate(age.frame.width * 0.02);
    final k = math.min(size.width * 0.98 / b.width, size.height * 0.56 / b.height);
    final center = Offset(size.width / 2, size.height * s.widget.centerY);
    final t = s.t;
    canvas.save();
    canvas.translate(center.dx, center.dy + math.sin(t * 0.9) * 5);
    canvas.rotate(math.sin(t * 0.9 + 0.6) * 0.012);
    canvas.scale(k * (1 + 0.006 * math.sin(t * 0.9 + 1.4)));
    canvas.translate(-b.center.dx, -b.center.dy);
    _rotated(canvas, age.finFar, math.sin(t * 1.7 + 1.2) * 0.09);
    _rotated(canvas, age.fluke, math.sin(t * 1.25) * 0.08);
    _layer(canvas, age.body);
    if (t < s.blinkUntil && age.eyesClosed != null) _layer(canvas, age.eyesClosed!);
    if (t < s.mouthUntil && age.mouthOpen != null) _layer(canvas, age.mouthOpen!);
    _rotated(canvas, age.finNear, math.sin(t * 1.7) * 0.11);
    _garden(canvas, age, atlas, t);
    if (age.halo != null) {
      canvas.save();
      canvas.translate(0, math.sin(t * 1.4) * 6);
      _layer(canvas, age.halo!, alpha: 0.8 + 0.2 * math.sin(t * 2.2));
      canvas.restore();
    }
    canvas.restore();

    _birds(canvas, size, atlas, state, world, center, t);
    _clouds(canvas, size, atlas, world, back: false);
    s.fx.paint(canvas);
  }

  void _sky(Canvas canvas, Size size, GameState state) {
    final pal = _skies[state.region.clamp(0, _skies.length - 1)];
    final p = 0.35 + 0.65 * eco.regionProgress(state);
    final top = Color.lerp(_greyTop, pal[0], p)!;
    final bottom = Color.lerp(_greyBottom, pal[1], p)!;
    final rect = Offset.zero & size;
    canvas.drawRect(rect, Paint()..shader = ui.Gradient.linear(Offset.zero, Offset(0, size.height), [top, bottom]));
    canvas.drawCircle(
      Offset(size.width * 0.82, size.height * 0.08),
      size.width * 0.55,
      Paint()
        ..shader = ui.Gradient.radial(Offset(size.width * 0.82, size.height * 0.08), size.width * 0.55, [
          const Color(0x66FFFFF0),
          const Color(0x00FFFFF0),
        ]),
    );
  }

  void _clouds(Canvas canvas, Size size, SpriteAtlas atlas, double world, {required bool back}) {
    if (atlas.clouds.isEmpty) return;
    final t = s.t;
    // (облако, высота, ширина в долях экрана, скорость, прозрачность)
    final specs = back
        ? const [(2, 0.20, 0.55, 6.0, 0.85), (1, 0.36, 0.38, 9.0, 0.75), (0, 0.62, 0.32, 5.0, 0.6)]
        : const [(0, 0.86, 0.75, 12.0, 1.0), (2, 0.93, 0.8, 8.0, 1.0)];
    for (var i = 0; i < specs.length; i++) {
      final (idx, y, w, speed, alpha) = specs[i];
      final img = atlas.clouds[idx % atlas.clouds.length];
      final width = size.width * w * world;
      final height = width * img.height / img.width;
      final span = size.width + width;
      final x = ((i * 0.37 + 0.2) * span - t * speed * world) % span - width;
      _paint.color = Color.fromRGBO(255, 255, 255, alpha);
      canvas.drawImageRect(
        img,
        Rect.fromLTWH(0, 0, img.width.toDouble(), img.height.toDouble()),
        Rect.fromLTWH(x, size.height * y - height / 2, width, height),
        _paint,
      );
    }
    _paint.color = const Color(0xFFFFFFFF);
  }

  void _layer(Canvas canvas, SpriteLayer l, {double alpha = 1}) {
    _paint.color = Color.fromRGBO(255, 255, 255, alpha.clamp(0.0, 1.0));
    canvas.drawImageRect(
      l.image,
      Rect.fromLTWH(0, 0, l.image.width.toDouble(), l.image.height.toDouble()),
      l.rect,
      _paint,
    );
    _paint.color = const Color(0xFFFFFFFF);
  }

  void _rotated(Canvas canvas, SpriteLayer l, double angle) {
    final p = l.pivot ?? l.rect.center;
    canvas.save();
    canvas.translate(p.dx, p.dy);
    canvas.rotate(angle);
    canvas.translate(-p.dx, -p.dy);
    _layer(canvas, l);
    canvas.restore();
  }

  void _garden(Canvas canvas, AgeArt age, SpriteAtlas atlas, double t) {
    for (var i = 0; i < age.slots.length; i++) {
      final since = s.appeared[i];
      if (since == null) continue;
      final slot = age.slots[i];
      final art = atlas.items[slot.item];
      if (art == null) continue;
      paintGardenItem(canvas, age, slot, art, _paint, pop: _easeOutBack(((t - since) / 0.35).clamp(0.0, 1.0)));
    }
  }

  void _birds(Canvas canvas, Size size, SpriteAtlas atlas, GameState state, double world, Offset c, double t) {
    final n = math.min(state.birds, 6);
    if (n == 0 || atlas.birds.isEmpty) return;
    final rx = size.width * 0.44;
    final ry = size.height * 0.1;
    for (var i = 0; i < n; i++) {
      final a = t * 0.35 + i * 2 * math.pi / n;
      final pos = Offset(
        c.dx + rx * math.cos(a),
        c.dy - size.height * 0.2 + ry * math.sin(a) + math.sin(t * 3 + i) * 4,
      );
      final img = atlas.birds[((t * 7 + i * 1.3).floor()) % atlas.birds.length];
      final w = size.width * 0.1 * world;
      final h = w * img.height / img.width;
      final movingLeft = math.sin(a) > 0;
      canvas.save();
      canvas.translate(pos.dx, pos.dy);
      if (movingLeft) canvas.scale(-1, 1);
      canvas.drawImageRect(
        img,
        Rect.fromLTWH(0, 0, img.width.toDouble(), img.height.toDouble()),
        Rect.fromCenter(center: Offset.zero, width: w, height: h),
        _paint,
      );
      canvas.restore();
    }
  }

  static double _easeOutBack(double x) {
    const c1 = 1.70158;
    const c3 = c1 + 1;
    final p = x - 1;
    return 1 + c3 * p * p * p + c1 * p * p;
  }

  @override
  bool shouldRepaint(_ScenePainter old) => false;
}

// ---------------------------------------------------------------------------
// Отклик на тапы: волна, всплывающее число, ноты.

class _FloatText {
  _FloatText(this.pos, this.stroke, this.fill);
  final Offset pos;
  final TextPainter stroke;
  final TextPainter fill;
  double age = 0;
}

class _Note {
  _Note(this.pos, this.vel, this.size, this.spin);
  Offset pos;
  final Offset vel;
  final double size;
  final double spin;
  double age = 0;
}

class _Ripple {
  _Ripple(this.pos);
  final Offset pos;
  double age = 0;
}

class SceneFx {
  final List<_FloatText> _texts = [];
  final List<_Note> _notes = [];
  final List<_Ripple> _ripples = [];
  final Map<double, Path> _notePaths = {};

  void addTap(Offset p, String text, math.Random rng) {
    const style = TextStyle(fontFamily: 'Nunito', fontSize: 30, fontWeight: FontWeight.w900);
    final stroke = TextPainter(
      text: TextSpan(
        text: text,
        style: style.copyWith(
          foreground: Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = 5
            ..strokeJoin = StrokeJoin.round
            ..color = J.navy,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final fill = TextPainter(
      text: TextSpan(
        text: text,
        style: style.copyWith(color: const Color(0xFFFFF178)),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    _texts.add(_FloatText(p + Offset((rng.nextDouble() - 0.5) * 30, -20), stroke, fill));
    if (_texts.length > 24) _texts.removeAt(0);
    for (var i = 0; i < 2; i++) {
      _notes.add(
        _Note(
          p,
          Offset((rng.nextDouble() - 0.5) * 120, -140 - rng.nextDouble() * 80),
          20 + rng.nextDouble() * 12,
          (rng.nextDouble() - 0.5) * 2,
        ),
      );
    }
    if (_notes.length > 40) _notes.removeRange(0, _notes.length - 40);
    _ripples.add(_Ripple(p));
    if (_ripples.length > 10) _ripples.removeAt(0);
  }

  void update(double dt) {
    for (final x in _texts) {
      x.age += dt;
    }
    _texts.removeWhere((x) => x.age > 1.0);
    for (final n in _notes) {
      n.age += dt;
      n.pos += n.vel * dt;
    }
    _notes.removeWhere((n) => n.age > 1.1);
    for (final r in _ripples) {
      r.age += dt;
    }
    _ripples.removeWhere((r) => r.age > 0.5);
  }

  void paint(Canvas canvas) {
    for (final r in _ripples) {
      final p = r.age / 0.5;
      canvas.drawCircle(
        r.pos,
        14 + 46 * p,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 5 * (1 - p)
          ..color = Color.fromRGBO(255, 255, 255, 0.8 * (1 - p)),
      );
    }
    for (final n in _notes) {
      final a = (1 - n.age / 1.1).clamp(0.0, 1.0);
      final path = _notePaths.putIfAbsent(n.size, () => NotePainter.notePath(Size.square(n.size)));
      canvas.save();
      canvas.translate(n.pos.dx - n.size / 2, n.pos.dy - n.size / 2);
      canvas.rotate(n.spin * n.age);
      canvas.drawPath(path.shift(const Offset(1.5, 2.5)), Paint()..color = Color.fromRGBO(30, 38, 80, 0.6 * a));
      canvas.drawPath(path, Paint()..color = Color.fromRGBO(255, 255, 255, a));
      canvas.restore();
    }
    for (final x in _texts) {
      final p = x.age;
      final rise = -70 * p;
      final scale = p < 0.12 ? 0.6 + p / 0.12 * 0.5 : 1.1 - (p - 0.12) * 0.15;
      final alpha = p > 0.7 ? (1 - (p - 0.7) / 0.3) : 1.0;
      canvas.saveLayer(null, Paint()..color = Color.fromRGBO(0, 0, 0, alpha.clamp(0.0, 1.0)));
      canvas.translate(x.pos.dx, x.pos.dy + rise);
      canvas.scale(scale);
      final o = Offset(-x.fill.width / 2, -x.fill.height / 2);
      x.stroke.paint(canvas, o + const Offset(0, 2.5));
      x.stroke.paint(canvas, o);
      x.fill.paint(canvas, o);
      canvas.restore();
    }
  }
}

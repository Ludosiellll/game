import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../core/big.dart';
import '../core/fight.dart';
import '../core/format.dart';
import '../core/story.dart';
import '../scene/whale_scene.dart';
import 'juicy.dart';

/// Экран боя со стражем или Тенью кита. Сам продвигает игру по кадрам
/// (сцена с китом в это время скрыта), тапы делит на ноты, трещину щита и удар.
class FightScreen extends StatefulWidget {
  const FightScreen({super.key, required this.controller, required this.atlas});

  final GameController controller;
  final SpriteAtlas atlas;

  @override
  State<FightScreen> createState() => _FightScreenState();
}

/// Небо боя по боссу: верх, низ.
const Map<String, List<Color>> _skies = {
  'fog': [Color(0xFF5A6E9C), Color(0xFFB8C8E8)],
  'petrel': [Color(0xFF221E50), Color(0xFF7894E6)],
  'eater': [Color(0xFF140E30), Color(0xFF5A4CA8)],
  'shadow': [Color(0xFF0E0A24), Color(0xFF3C2E78)],
};

class _Shot {
  _Shot(this.from, this.to);
  final Offset from;
  final Offset to;
  double age = 0;
}

class _Num {
  _Num(this.pos, this.text, this.crit);
  final Offset pos;
  final String text;
  final bool crit;
  double age = 0;
}

class _FightScreenState extends State<FightScreen> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final _Repaint _repaint = _Repaint();
  final math.Random _rng = math.Random();
  Duration _last = Duration.zero;
  double t = 0;
  double intro = 1.6;
  double shake = 0;
  double flash = 0;
  double beam = 0;
  double winTimer = -1;
  final List<_Shot> shots = [];
  final List<_Num> nums = [];
  Offset crack = const Offset(0.5, 0.45);
  bool _hintShown = false;
  Size _size = Size.zero;

  GameController get c => widget.controller;
  Fight? get f => c.fight;

  @override
  void initState() {
    super.initState();
    c.fightPaused = true;
    _ticker = createTicker(_onTick)..start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    c.fightPaused = false;
    super.dispose();
  }

  void _onTick(Duration elapsed) {
    final dt = _last == Duration.zero ? 0.0 : (elapsed - _last).inMicroseconds / 1e6;
    _last = elapsed;
    final step = dt.clamp(0.0, 0.1);
    t += step;
    if (intro > 0) {
      intro -= step;
      if (intro <= 0) c.fightPaused = false;
    }
    c.onFrame(dt);
    shake = math.max(0, shake - step * 3);
    flash = math.max(0, flash - step * 4);
    beam = math.max(0, beam - step * 2);
    for (final s in shots) {
      s.age += step;
    }
    shots.removeWhere((s) => s.age > 0.35);
    for (final n in nums) {
      n.age += step;
    }
    nums.removeWhere((n) => n.age > 0.9);
    final fight = f;
    if (fight != null && fight.outcome == FightOutcome.won) {
      intro = 0;
      if (winTimer < 0) {
        winTimer = 1.4;
        c.fightPaused = true;
      }
      winTimer -= step;
      if (winTimer <= 0 && mounted) {
        winTimer = 1e9;
        c.closeFight();
        Navigator.of(context).pop();
        return;
      }
    }
    _repaint.tick();
    if (mounted) setState(() {});
  }

  // --- геометрия поля -------------------------------------------------------------

  Offset get _bossCenter => Offset(_size.width * 0.56, _size.height * 0.33);
  double get _bossWidth => _size.width * 0.74;

  // Наш кит внизу, зеркально — смотрит вправо, на босса; сад на спине,
  // морда выше кнопки Песни.
  AgeArt get _age => widget.atlas.age(c.state.age);
  double get _whaleK => _size.width * 0.72 / _age.body.rect.width;
  Offset get _whaleCenter => Offset(_size.width * 0.4, _size.height * 0.79 + math.sin(t * 1.6) * 5);

  /// Точка кадра кита → точка экрана.
  Offset _whalePoint(Offset p) {
    final b = _age.body.rect.center;
    return _whaleCenter + Offset(-(p.dx - b.dx), p.dy - b.dy) * _whaleK;
  }

  Offset get _mouth => _whalePoint(_age.mouth);

  Offset _notePos(DarkNote n) =>
      Offset(n.x * _size.width, ui.lerpDouble(_size.height * 0.45, _size.height * 0.76, n.t)!);

  Offset get _crackPos => Offset(
    _bossCenter.dx + (crack.dx - 0.5) * _bossWidth * 0.6,
    _bossCenter.dy + (crack.dy - 0.5) * _bossWidth * 0.5,
  );

  void _onTapDown(Offset p) {
    final fight = f;
    if (fight == null || fight.over || intro > 0) return;
    for (final n in fight.notes) {
      if ((_notePos(n) - p).distance < 46) {
        final dmg = c.fightPop(n.id);
        if (dmg != null) _hit(_notePos(n), dmg, false, big: false);
        _hintShown = true;
        return;
      }
    }
    if (fight.shieldUp && (_crackPos - p).distance < 54) {
      c.fightCrack();
      shake = 0.5;
      crack = Offset(0.25 + _rng.nextDouble() * 0.5, 0.3 + _rng.nextDouble() * 0.4);
      return;
    }
    final hit = c.fightTap();
    if (hit != null) {
      shots.add(_Shot(_mouth, _bossCenter + Offset((_rng.nextDouble() - 0.5) * 80, (_rng.nextDouble() - 0.5) * 60)));
      _hit(_bossCenter + Offset((_rng.nextDouble() - 0.5) * 160, -40 - _rng.nextDouble() * 60), hit.damage, hit.crit);
    }
  }

  void _hit(Offset at, Big damage, bool crit, {bool big = true}) {
    nums.add(_Num(at, crit ? 'КРИТ! -${formatBig(damage)}' : '-${formatBig(damage)}', crit));
    if (nums.length > 18) nums.removeAt(0);
    flash = math.max(flash, crit ? 1 : 0.6);
    shake = math.max(shake, crit ? 0.8 : (big ? 0.25 : 0.15));
  }

  void _song() {
    final dmg = c.fightSong();
    if (dmg == null) return;
    beam = 1;
    shake = 1.2;
    _hit(_bossCenter + const Offset(0, -80), dmg, true);
  }

  @override
  Widget build(BuildContext context) {
    final fight = f;
    final boss = fight?.boss;
    return PopScope(
      canPop: false,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: LayoutBuilder(
          builder: (context, box) {
            _size = box.biggest;
            if (fight == null || boss == null) return const SizedBox();
            return Stack(
              children: [
                Positioned.fill(
                  child: Listener(
                    behavior: HitTestBehavior.opaque,
                    onPointerDown: (e) => _onTapDown(e.localPosition),
                    child: CustomPaint(painter: _FightPainter(this, _repaint)),
                  ),
                ),
                SafeArea(child: _hud(fight)),
                if (intro > 0) _introCard(fight),
                if (fight.outcome == FightOutcome.lost) _lostPanel(fight),
                if (fight.outcome == FightOutcome.won) _wonBanner(),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _hud(Fight fight) {
    final title = fight.kind == FightKind.trial ? 'ТЕНЬ КИТА' : fight.boss.name.split(' ').first.toUpperCase();
    return Column(
      children: [
        const SizedBox(height: 6),
        Row(
          children: [
            const SizedBox(width: 64),
            Expanded(
              child: Center(child: Ribbon(title, colors: J.purpleButton, size: 24)),
            ),
            SizedBox(
              width: 64,
              child: _Timer(left: fight.timeLeft, total: fight.duration),
            ),
          ],
        ),
        Text(
          fight.boss.title,
          style: const TextStyle(color: J.white, fontWeight: FontWeight.w900, fontSize: 14),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 6, 18, 0),
          child: ChunkyBar(
            value: fight.hpFraction,
            label: '${formatBig(fight.hp)} / ${formatBig(fight.maxHp)}',
            colors: const [Color(0xFFFF786E), Color(0xFFE2284E)],
          ),
        ),
        // Подсказки — под боссом, над садом: морду босса не закрывают.
        const Spacer(flex: 5),
        if (!_hintShown && fight.notes.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: JuicyCard(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
              child: Text(
                'Лопай тёмные ноты, пока они не упали в сад!',
                style: TextStyle(color: J.navy, fontWeight: FontWeight.w900, fontSize: 15),
              ),
            ),
          ),
        if (fight.shieldUp)
          const Padding(
            padding: EdgeInsets.only(bottom: 8),
            child: OutlinedText('Щит! Бей по трещине', size: 20, color: Color(0xFFB4E8FF)),
          ),
        const Spacer(flex: 4),
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              SizedBox(
                width: 112,
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ChunkyButton(
                      colors: J.coralButton,
                      onPressed: null,
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      child: Column(
                        children: [
                          const OutlinedText('КРЕЩЕНДО', size: 12, strokeWidth: 2.5, shadow: false),
                          OutlinedText(
                            '×${fight.crescendo.toStringAsFixed(1)}',
                            size: 22,
                            color: const Color(0xFFFFF178),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const Spacer(),
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (fight.canAutoWin && !fight.over)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: ChunkyButton(
                        colors: J.goldButton,
                        onPressed: () => c.fightAutoWin(),
                        child: const OutlinedText('ПРОПЕТЬ', size: 18, strokeWidth: 3),
                      ),
                    ),
                  _SongButton(charge: fight.songCharge, ready: fight.songReady, onPressed: _song),
                ],
              ),
              const Spacer(),
              SizedBox(
                width: 112,
                child: (!fight.extraTimeUsed && fight.timeLeft < 12)
                    ? ChunkyCircle(
                        size: 70,
                        colors: J.tealButton,
                        onPressed: () => c.fightAddTime(),
                        child: const OutlinedText('+15с', size: 22),
                      )
                    : const SizedBox(),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _introCard(Fight fight) {
    final text = bossIntro[fight.boss.id] ?? '';
    return Positioned.fill(
      child: IgnorePointer(
        child: Container(
          color: Color.fromRGBO(10, 8, 30, (intro.clamp(0.0, 1.0)) * 0.55),
          alignment: Alignment.center,
          padding: const EdgeInsets.all(24),
          child: Opacity(
            opacity: intro.clamp(0.0, 1.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                OutlinedText(fight.boss.name.toUpperCase(), size: 30, maxLines: 2, color: const Color(0xFFFFE25C)),
                const SizedBox(height: 10),
                OutlinedText(text, size: 18, maxLines: 4, strokeWidth: 3),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _lostPanel(Fight fight) {
    return Positioned.fill(
      child: Container(
        color: const Color(0x99080820),
        alignment: Alignment.center,
        padding: const EdgeInsets.all(24),
        child: JuicyPanel(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const OutlinedText('Время вышло', size: 28),
              const SizedBox(height: 8),
              const Text(
                'Безмолвие отступило… пока. Ничего не потеряно: подрасти сад и возвращайся.',
                textAlign: TextAlign.center,
                style: TextStyle(color: J.navy, fontWeight: FontWeight.w800, fontSize: 16),
              ),
              const SizedBox(height: 14),
              if (!fight.extraTimeUsed)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: ChunkyButton(
                    expand: true,
                    colors: J.tealButton,
                    onPressed: () => c.fightAddTime(),
                    child: const OutlinedText('+15 С ЗА РЕКЛАМУ', size: 18, strokeWidth: 3),
                  ),
                ),
              ChunkyButton(
                expand: true,
                colors: J.whiteButton,
                onPressed: () {
                  c.retreatFight();
                  Navigator.of(context).pop();
                },
                child: const Text(
                  'Отступить',
                  style: TextStyle(color: J.navy, fontWeight: FontWeight.w900, fontSize: 18),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _wonBanner() => const Positioned.fill(
    child: IgnorePointer(
      child: Center(child: OutlinedText('ПОБЕДА!', size: 54, color: Color(0xFFFFE25C))),
    ),
  );
}

class _Repaint extends ChangeNotifier {
  void tick() => notifyListeners();
}

class _Timer extends StatelessWidget {
  const _Timer({required this.left, required this.total});

  final double left;
  final double total;

  @override
  Widget build(BuildContext context) {
    final s = left.ceil();
    return SizedBox(
      width: 58,
      height: 58,
      child: Stack(
        alignment: Alignment.center,
        children: [
          Container(
            decoration: const BoxDecoration(color: J.navy, shape: BoxShape.circle),
          ),
          Container(
            margin: const EdgeInsets.all(3),
            decoration: const BoxDecoration(color: J.white, shape: BoxShape.circle),
          ),
          SizedBox(
            width: 48,
            height: 48,
            child: CircularProgressIndicator(
              value: (left / total).clamp(0.0, 1.0),
              strokeWidth: 5,
              color: left < 10 ? J.red : const Color(0xFFFF9628),
              backgroundColor: const Color(0xFFDCE4F4),
            ),
          ),
          Text(
            '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}',
            style: const TextStyle(color: J.navy, fontWeight: FontWeight.w900, fontSize: 15),
          ),
        ],
      ),
    );
  }
}

class _SongButton extends StatelessWidget {
  const _SongButton({required this.charge, required this.ready, required this.onPressed});

  final double charge;
  final bool ready;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Stack(
      alignment: Alignment.center,
      children: [
        SizedBox(
          width: 132,
          height: 132,
          child: CircularProgressIndicator(
            value: charge.clamp(0.0, 1.0),
            strokeWidth: 8,
            color: ready ? const Color(0xFFFFF178) : J.white,
            backgroundColor: const Color(0x55FFFFFF),
          ),
        ),
        ChunkyCircle(
          size: 104,
          depth: 10,
          colors: ready ? J.goldButton : J.greyButton,
          glow: ready ? const Color(0xAAFFE28C) : null,
          onPressed: ready ? onPressed : null,
          child: const Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              NoteGlyph(size: 44, shadow: Color(0xFF8C4A0A)),
              OutlinedText('ПЕСНЬ', size: 15, strokeWidth: 2.5, shadow: false),
            ],
          ),
        ),
      ],
    );
  }
}

class _FightPainter extends CustomPainter {
  _FightPainter(this.s, Listenable repaint) : super(repaint: repaint);

  final _FightScreenState s;
  final Paint _p = Paint()..filterQuality = FilterQuality.medium;

  @override
  void paint(Canvas canvas, Size size) {
    final fight = s.f;
    if (fight == null) return;
    final atlas = s.widget.atlas;
    final sky = _skies[fight.boss.id] ?? _skies['petrel']!;
    canvas.drawRect(Offset.zero & size, Paint()..shader = ui.Gradient.linear(Offset.zero, Offset(0, size.height), sky));
    // Тучи и дождь.
    final cloudPaint = Paint()
      ..filterQuality = FilterQuality.medium
      ..colorFilter = const ColorFilter.mode(Color(0xFF7A7EB4), BlendMode.modulate);
    for (var i = 0; i < atlas.clouds.length; i++) {
      final img = atlas.clouds[i];
      final w = size.width * (0.7 + i * 0.1);
      final h = w * img.height / img.width;
      final x = ((i * 0.45 + 0.1) * size.width - s.t * (6 + i * 3)) % (size.width + w) - w * 0.5;
      canvas.drawImageRect(img, _src(img), Rect.fromLTWH(x, size.height * (0.12 + i * 0.1), w, h), cloudPaint);
    }
    final rain = Paint()
      ..color = const Color(0x40C8D2FF)
      ..strokeWidth = 2;
    for (var i = 0; i < 70; i++) {
      final x = (i * 97.0 + s.t * 260) % size.width;
      final y = (i * 53.0 + s.t * 900) % (size.height * 0.9);
      canvas.drawLine(Offset(x, y), Offset(x - 10, y + 36), rain);
    }

    // Свет за боссом.
    final bc = s._bossCenter;
    canvas.drawCircle(
      bc,
      s._bossWidth * 0.7,
      Paint()..shader = ui.Gradient.radial(bc, s._bossWidth * 0.7, [const Color(0x55FFF0B4), const Color(0x00FFF0B4)]),
    );

    // Босс: покачивание, тряска, вспышка от ударов.
    final shake = Offset(math.sin(s.t * 61) * 10 * s.shake, math.cos(s.t * 53) * 8 * s.shake);
    final bob = math.sin(s.t * 2.2) * 8;
    canvas.save();
    canvas.translate(bc.dx + shake.dx, bc.dy + bob + shake.dy);
    final squash = 1 + 0.06 * s.flash;
    canvas.scale(squash, 2 - squash);
    if (fight.kind == FightKind.trial) {
      _shadowWhale(canvas, atlas, s._bossWidth);
    } else {
      final img = atlas.bosses[fight.boss.id];
      if (img != null) {
        final w = s._bossWidth;
        final h = w * img.height / img.width;
        canvas.drawImageRect(img, _src(img), Rect.fromCenter(center: Offset.zero, width: w, height: h), _p);
        if (s.flash > 0) {
          canvas.drawImageRect(
            img,
            _src(img),
            Rect.fromCenter(center: Offset.zero, width: w, height: h),
            Paint()..colorFilter = ColorFilter.mode(Color.fromRGBO(255, 255, 255, 0.55 * s.flash), BlendMode.srcATop),
          );
        }
      }
    }
    canvas.restore();

    // Щит тишины с трещиной.
    if (fight.shieldUp) {
      canvas.drawCircle(
        bc,
        s._bossWidth * 0.52,
        Paint()
          ..shader = ui.Gradient.radial(bc, s._bossWidth * 0.52, [const Color(0x1078C8FF), const Color(0x6078C8FF)]),
      );
      canvas.drawCircle(
        bc,
        s._bossWidth * 0.52,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4
          ..color = const Color(0xCCB4E8FF),
      );
      final cp = s._crackPos;
      final pulse = 1 + 0.12 * math.sin(s.t * 8);
      canvas.drawCircle(cp, 34 * pulse, Paint()..color = const Color(0x88FFFFFF));
      final crack = Path()
        ..moveTo(cp.dx - 18, cp.dy - 20)
        ..lineTo(cp.dx - 4, cp.dy - 4)
        ..lineTo(cp.dx - 14, cp.dy + 4)
        ..lineTo(cp.dx + 2, cp.dy + 22)
        ..moveTo(cp.dx - 4, cp.dy - 4)
        ..lineTo(cp.dx + 16, cp.dy - 12);
      canvas.drawPath(
        crack,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = 5
          ..strokeCap = StrokeCap.round
          ..color = J.navy,
      );
      for (var i = 0; i < fight.shieldHitsLeft; i++) {
        canvas.drawCircle(Offset(cp.dx - 14 + i * 14, cp.dy + 44), 5, Paint()..color = const Color(0xFFB4E8FF));
      }
    }

    // Наш кит с садом на спине — туда и падают тёмные ноты.
    final age = s._age;
    final k = s._whaleK;
    final wc = s._whaleCenter;
    canvas.save();
    canvas.translate(wc.dx, wc.dy);
    canvas.scale(-k, k);
    canvas.translate(-age.body.rect.center.dx, -age.body.rect.center.dy);
    paintWhaleStill(canvas, atlas, age, s.c.state);
    canvas.restore();

    // Тёмные ноты.
    final notePath = NotePainter.notePath(const Size.square(40));
    var first = true;
    for (final n in fight.notes) {
      final p = s._notePos(n);
      canvas.drawCircle(
        p,
        52,
        Paint()..shader = ui.Gradient.radial(p, 52, [const Color(0x99B05AFF), const Color(0x00B05AFF)]),
      );
      canvas.drawCircle(p, 34, Paint()..color = const Color(0xFFC896FF));
      canvas.drawCircle(
        p,
        30,
        Paint()
          ..shader = ui.Gradient.linear(p - const Offset(0, 30), p + const Offset(0, 30), [
            const Color(0xFF46287A),
            const Color(0xFF140A28),
          ]),
      );
      canvas.save();
      canvas.translate(p.dx - 20, p.dy - 21);
      canvas.drawPath(notePath, Paint()..color = const Color(0xFFBE8CFF));
      canvas.restore();
      if (first && !s._hintShown) {
        canvas.drawCircle(
          p,
          46 + 4 * math.sin(s.t * 8),
          Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = 4
            ..color = J.white,
        );
      }
      first = false;
    }

    // Ноты кита летят в босса.
    final shot = NotePainter.notePath(const Size.square(30));
    for (final sh in s.shots) {
      final q = sh.age / 0.35;
      final p = Offset.lerp(sh.from, sh.to, q)! + Offset(0, -math.sin(q * math.pi) * 60);
      canvas.save();
      canvas.translate(p.dx - 15, p.dy - 15);
      canvas.drawPath(shot.shift(const Offset(2, 3)), Paint()..color = const Color(0x881E2650));
      canvas.drawPath(shot, Paint()..color = J.white);
      canvas.restore();
    }

    // Луч Песни кита.
    if (s.beam > 0) {
      canvas.drawLine(
        s._mouth,
        bc,
        Paint()
          ..strokeWidth = 60 * s.beam
          ..strokeCap = StrokeCap.round
          ..color = Color.fromRGBO(255, 241, 160, 0.75 * s.beam),
      );
      canvas.drawLine(
        s._mouth,
        bc,
        Paint()
          ..strokeWidth = 24 * s.beam
          ..strokeCap = StrokeCap.round
          ..color = Color.fromRGBO(255, 255, 255, s.beam),
      );
    }

    // Урон.
    for (final n in s.nums) {
      final rise = -60 * n.age;
      final alpha = (1 - (n.age - 0.6) / 0.3).clamp(0.0, 1.0);
      final pop = 1 + 0.45 * (1 - (n.age / 0.12).clamp(0.0, 1.0));
      final size0 = (n.crit ? 38.0 : 28.0) * pop;
      _text(canvas, n.text, n.pos + Offset(0, rise), size0, n.crit ? const Color(0xFFFFE25C) : J.white, alpha);
    }

    if (fight.outcome == FightOutcome.won) {
      final rnd = math.Random(7);
      for (var i = 0; i < 60; i++) {
        final x = rnd.nextDouble() * size.width;
        final y = (rnd.nextDouble() * size.height * 0.6 + s.t * 220) % size.height;
        canvas.drawRect(
          Rect.fromCenter(center: Offset(x, y), width: 10, height: 6),
          Paint()..color = [const Color(0xFFFFE25C), const Color(0xFF7CE250), const Color(0xFFFF8CB4), J.white][i % 4],
        );
      }
    }
  }

  /// Тень кита — тёмный двойник нашего кита вместе с садом.
  void _shadowWhale(Canvas canvas, SpriteAtlas atlas, double width) {
    final age = s._age;
    final b = age.body.rect;
    canvas.save();
    canvas.scale(width / b.width);
    canvas.translate(-b.center.dx, -b.center.dy);
    paintWhaleStill(canvas, atlas, age, s.c.state, dark: true);
    canvas.restore();
  }

  static Rect _src(ui.Image img) => Rect.fromLTWH(0, 0, img.width.toDouble(), img.height.toDouble());

  static void _text(Canvas canvas, String text, Offset c, double size, Color color, double alpha) {
    final style = TextStyle(fontFamily: 'Nunito', fontSize: size, fontWeight: FontWeight.w900);
    final stroke = TextPainter(
      text: TextSpan(
        text: text,
        style: style.copyWith(
          foreground: Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = size * 0.18
            ..strokeJoin = StrokeJoin.round
            ..color = Color.fromRGBO(30, 38, 80, alpha),
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final fill = TextPainter(
      text: TextSpan(
        text: text,
        style: style.copyWith(color: color.withValues(alpha: alpha)),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    final o = c - Offset(fill.width / 2, fill.height / 2);
    stroke.paint(canvas, o);
    fill.paint(canvas, o);
  }

  @override
  bool shouldRepaint(_FightPainter old) => false;
}

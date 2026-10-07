import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Сочный мультяшный интерфейс: текст-наклейки с обводкой, кнопки-«леденцы»
/// с нижней гранью, полосы, ленты. Цвета — из доски стиля
/// (`docs/art/style_frame_2026-10-07.jpg`).
abstract final class J {
  static const navy = Color(0xFF1E2650);
  static const white = Color(0xFFFFFFFF);
  static const panelTop = Color(0xFFECF6FF);
  static const panelBottom = Color(0xFFCEE4FF);
  static const card = Color(0xFFFFFFFF);
  static const cardBottom = Color(0xFFEEF5FF);
  static const sky = Color(0xFFB2E2FF);
  static const skyDeep = Color(0xFF78C2FF);
  static const muted = Color(0xFF5A6896);
  static const green = Color(0xFF2E9E3E);
  static const red = Color(0xFFE8284A);

  static const goldButton = ButtonColors(Color(0xFFFFE25C), Color(0xFFFFAA1E), Color(0xFFCE780A));
  static const greenButton = ButtonColors(Color(0xFF7CE250), Color(0xFF34B83C), Color(0xFF22802E));
  static const coralButton = ButtonColors(Color(0xFFFF8C5C), Color(0xFFFF4860), Color(0xFFBE284A));
  static const purpleButton = ButtonColors(Color(0xFFCD8CFF), Color(0xFF8046EB), Color(0xFF5628AA));
  static const tealButton = ButtonColors(Color(0xFF78ECA0), Color(0xFF28BE78), Color(0xFF18825A));
  static const whiteButton = ButtonColors(Color(0xFFFFFFFF), Color(0xFFD4E4FF), Color(0xFF8CA6DC));
  static const greyButton = ButtonColors(Color(0xFFD6DEEE), Color(0xFFB4C0D6), Color(0xFF828EAA));
  static const redButton = ButtonColors(Color(0xFFFF786E), Color(0xFFE8324C), Color(0xFFA01E3C));

  /// Тема приложения: один шрифт на всё — в игре и в тестах вёрстки.
  static ThemeData theme() => ThemeData(
    useMaterial3: true,
    fontFamily: 'Nunito',
    scaffoldBackgroundColor: panelTop,
    colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3D8BFF), primary: const Color(0xFF3D8BFF)),
  );
}

class ButtonColors {
  const ButtonColors(this.top, this.bottom, this.depth);
  final Color top;
  final Color bottom;
  final Color depth;
}

final RegExp _diacritics = RegExp('[ЙйЁё]');

/// Текст-наклейка: заливка, толстая тёмная обводка и тень вниз.
class OutlinedText extends StatelessWidget {
  const OutlinedText(
    this.text, {
    super.key,
    this.size = 20,
    this.color = J.white,
    this.stroke = J.navy,
    this.strokeWidth,
    this.shadow = true,
    this.weight = FontWeight.w900,
    this.align = TextAlign.center,
    this.maxLines = 1,
  });

  final String text;
  final double size;
  final Color color;
  final Color? stroke;
  final double? strokeWidth;
  final bool shadow;
  final FontWeight weight;
  final TextAlign align;
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    final base = TextStyle(fontFamily: 'Nunito', fontSize: size, fontWeight: weight, height: 1.05);
    // Толстая обводка заливает просвет под краткой и точками: «Й» читается
    // как «И», «ё» — как «е». Для таких надписей обводка тоньше.
    final sw = (strokeWidth ?? size * 0.16) * (_diacritics.hasMatch(text) ? 0.65 : 1);
    Widget layer(TextStyle style) => Text(
      text,
      style: style,
      textAlign: align,
      maxLines: maxLines,
      overflow: TextOverflow.visible,
      softWrap: maxLines > 1,
    );
    if (stroke == null) return layer(base.copyWith(color: color));
    final strokePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = sw
      ..strokeJoin = StrokeJoin.round
      ..color = stroke!;
    return Stack(
      children: [
        if (shadow)
          Transform.translate(
            offset: Offset(0, size * 0.09),
            child: layer(base.copyWith(foreground: strokePaint)),
          ),
        layer(base.copyWith(foreground: strokePaint)),
        layer(base.copyWith(color: color)),
      ],
    );
  }
}

/// Кнопка-«леденец»: тёмная обводка, нижняя грань глубины, градиент и блик.
/// При нажатии лицевая часть «проседает».
class ChunkyButton extends StatefulWidget {
  const ChunkyButton({
    super.key,
    required this.child,
    required this.onPressed,
    this.colors = J.greenButton,
    this.radius = 18,
    this.depth = 7,
    this.border = 3.5,
    this.padding = const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
    this.gloss = true,
    this.expand = false,
  });

  final Widget child;
  final VoidCallback? onPressed;
  final ButtonColors colors;
  final double radius;
  final double depth;
  final double border;
  final EdgeInsets padding;
  final bool gloss;
  final bool expand;

  @override
  State<ChunkyButton> createState() => _ChunkyButtonState();
}

class _ChunkyButtonState extends State<ChunkyButton> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onPressed != null;
    final c = enabled ? widget.colors : J.greyButton;
    final sink = _down ? widget.depth * 0.7 : 0.0;
    final face = Container(
      padding: widget.padding,
      decoration: BoxDecoration(
        gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [c.top, c.bottom]),
        borderRadius: BorderRadius.circular(widget.radius),
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          if (widget.gloss)
            Positioned(
              left: 4,
              right: 4,
              top: -widget.padding.top + 4,
              height: 10,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.28),
                  borderRadius: BorderRadius.circular(widget.radius),
                ),
              ),
            ),
          widget.child,
        ],
      ),
    );
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: enabled ? (_) => setState(() => _down = true) : null,
      onTapCancel: enabled ? () => setState(() => _down = false) : null,
      onTapUp: enabled
          ? (_) {
              setState(() => _down = false);
              widget.onPressed!();
            }
          : null,
      child: Padding(
        padding: EdgeInsets.only(top: sink),
        child: Container(
          decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(widget.radius + widget.border)),
          padding: EdgeInsets.all(widget.border),
          child: Container(
            decoration: BoxDecoration(color: c.depth, borderRadius: BorderRadius.circular(widget.radius)),
            padding: EdgeInsets.only(bottom: widget.depth - sink),
            child: widget.expand ? SizedBox(width: double.infinity, child: face) : face,
          ),
        ),
      ),
    );
  }
}

/// Круглая кнопка-«леденец» с картинкой или значком и необязательным значком «!».
class ChunkyCircle extends StatefulWidget {
  const ChunkyCircle({
    super.key,
    required this.size,
    required this.child,
    required this.onPressed,
    this.colors = J.whiteButton,
    this.depth = 7,
    this.badge = false,
    this.glow,
  });

  final double size;
  final Widget child;
  final VoidCallback? onPressed;
  final ButtonColors colors;
  final double depth;
  final bool badge;
  final Color? glow;

  @override
  State<ChunkyCircle> createState() => _ChunkyCircleState();
}

class _ChunkyCircleState extends State<ChunkyCircle> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final c = widget.onPressed != null ? widget.colors : J.greyButton;
    final sink = _down ? widget.depth * 0.7 : 0.0;
    final s = widget.size;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: widget.onPressed != null ? (_) => setState(() => _down = true) : null,
      onTapCancel: () => setState(() => _down = false),
      onTapUp: widget.onPressed != null
          ? (_) {
              setState(() => _down = false);
              widget.onPressed!();
            }
          : null,
      child: SizedBox(
        width: s + 8,
        height: s + widget.depth + 8,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            if (widget.glow != null)
              Positioned(
                left: -s * 0.25,
                top: -s * 0.25,
                width: s * 1.5 + 8,
                height: s * 1.5 + 8,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: RadialGradient(colors: [widget.glow!, widget.glow!.withValues(alpha: 0)]),
                  ),
                ),
              ),
            Positioned(
              left: 0,
              top: widget.depth,
              width: s + 8,
              height: s + 8,
              child: const DecoratedBox(
                decoration: BoxDecoration(color: J.navy, shape: BoxShape.circle),
              ),
            ),
            Positioned(
              left: 0,
              top: sink,
              width: s + 8,
              height: s + 8,
              child: const DecoratedBox(
                decoration: BoxDecoration(color: J.navy, shape: BoxShape.circle),
              ),
            ),
            Positioned(
              left: 4,
              top: 4 + widget.depth,
              width: s,
              height: s,
              child: DecoratedBox(
                decoration: BoxDecoration(color: c.depth, shape: BoxShape.circle),
              ),
            ),
            Positioned(
              left: 4,
              top: 4 + sink,
              width: s,
              height: s,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [c.top, c.bottom],
                  ),
                ),
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Positioned(
                      top: s * 0.06,
                      left: s * 0.2,
                      right: s * 0.2,
                      height: s * 0.36,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.3),
                          borderRadius: BorderRadius.circular(s),
                        ),
                      ),
                    ),
                    widget.child,
                  ],
                ),
              ),
            ),
            if (widget.badge) Positioned(right: -2, top: sink - 2, child: const AlertBadge()),
          ],
        ),
      ),
    );
  }
}

/// Красный кружок «!» — есть что сделать.
class AlertBadge extends StatelessWidget {
  const AlertBadge({super.key, this.text = '!'});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 24,
      height: 24,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(color: J.white, width: 2.5),
        gradient: const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFFF6E6E), Color(0xFFE82846)],
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        text,
        style: const TextStyle(color: J.white, fontWeight: FontWeight.w900, fontSize: 13, height: 1),
      ),
    );
  }
}

/// Толстая полоса прогресса с тёмной обводкой, бликом и подписью внутри.
class ChunkyBar extends StatelessWidget {
  const ChunkyBar({
    super.key,
    required this.value,
    this.label,
    this.height = 26,
    this.colors = const [Color(0xFFFF96C8), Color(0xFFFF6E5A)],
    this.track = const Color(0xFF1A265C),
  });

  final double value;
  final String? label;
  final double height;
  final List<Color> colors;
  final Color track;

  @override
  Widget build(BuildContext context) {
    final v = value.clamp(0.0, 1.0);
    return Container(
      height: height,
      decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(height)),
      padding: const EdgeInsets.all(3),
      child: Container(
        decoration: BoxDecoration(color: track, borderRadius: BorderRadius.circular(height)),
        padding: const EdgeInsets.all(2),
        child: Stack(
          children: [
            FractionallySizedBox(
              widthFactor: v == 0 ? 0.001 : v,
              child: Container(
                decoration: BoxDecoration(
                  gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: colors),
                  borderRadius: BorderRadius.circular(height),
                ),
                alignment: Alignment.topCenter,
                padding: const EdgeInsets.fromLTRB(6, 2, 6, 0),
                child: Container(
                  height: math.max(2, height * 0.18),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.4),
                    borderRadius: BorderRadius.circular(height),
                  ),
                ),
              ),
            ),
            if (label != null) Center(child: OutlinedText(label!, size: height * 0.52, strokeWidth: 3, shadow: false)),
          ],
        ),
      ),
    );
  }
}

/// Лента-заголовок окна, как в мультяшных играх.
class Ribbon extends StatelessWidget {
  const Ribbon(this.text, {super.key, this.colors = J.goldButton, this.size = 30});

  final String text;
  final ButtonColors colors;
  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      painter: _RibbonTailsPainter(colors.depth),
      child: Padding(
        padding: EdgeInsets.symmetric(horizontal: size * 1.1),
        child: Container(
          decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.all(4),
          child: Container(
            padding: EdgeInsets.fromLTRB(size * 0.9, size * 0.2, size * 0.9, size * 0.3),
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [colors.top, colors.bottom],
              ),
              borderRadius: BorderRadius.circular(9),
            ),
            child: OutlinedText(text, size: size),
          ),
        ),
      ),
    );
  }
}

class _RibbonTailsPainter extends CustomPainter {
  _RibbonTailsPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final h = size.height;
    final w = size.width;
    for (final side in [-1.0, 1.0]) {
      final xIn = side < 0 ? h * 0.9 : w - h * 0.9;
      final xOut = side < 0 ? 0.0 : w;
      // Хвост ленты с вырезом-«ласточкиным хвостом» на внешнем краю.
      final path = Path()
        ..moveTo(xIn, h * 0.22)
        ..lineTo(xOut, h * 0.22)
        ..lineTo(xOut - side * h * 0.35, h * 0.6)
        ..lineTo(xOut, h * 0.98)
        ..lineTo(xIn, h * 0.98)
        ..close();
      canvas.drawPath(path, Paint()..color = J.navy);
      final inner = Path()
        ..moveTo(xIn, h * 0.3)
        ..lineTo(xOut - side * 5, h * 0.3)
        ..lineTo(xOut - side * h * 0.42, h * 0.6)
        ..lineTo(xOut - side * 5, h * 0.9)
        ..lineTo(xIn, h * 0.9)
        ..close();
      canvas.drawPath(inner, Paint()..color = color);
    }
  }

  @override
  bool shouldRepaint(_RibbonTailsPainter old) => old.color != color;
}

/// Нота (восьмая) — значок песни.
class NoteGlyph extends StatelessWidget {
  const NoteGlyph({super.key, required this.size, this.color = J.white, this.shadow});

  final double size;
  final Color color;
  final Color? shadow;

  @override
  Widget build(BuildContext context) => CustomPaint(
    size: Size.square(size),
    painter: NotePainter(color: color, shadow: shadow),
  );
}

class NotePainter extends CustomPainter {
  NotePainter({required this.color, this.shadow});
  final Color color;
  final Color? shadow;

  static Path notePath(Size s) {
    final w = s.width;
    final head = Path()
      ..addOval(Rect.fromCenter(center: Offset(w * 0.36, w * 0.74), width: w * 0.44, height: w * 0.31));
    final m = Matrix4.identity()
      ..translateByDouble(w * 0.36, w * 0.74, 0, 1)
      ..rotateZ(-0.38)
      ..translateByDouble(-w * 0.36, -w * 0.74, 0, 1);
    final rotated = head.transform(m.storage);
    final sx = w * 0.36 + w * 0.22 - w * 0.07;
    final stem = Path()..addRect(Rect.fromLTRB(sx - w * 0.04, w * 0.12, sx + w * 0.04, w * 0.72));
    final flag = Path()
      ..moveTo(sx - w * 0.04, w * 0.1)
      ..lineTo(sx + w * 0.34, w * 0.3)
      ..lineTo(sx + w * 0.3, w * 0.47)
      ..lineTo(sx + w * 0.04, w * 0.3)
      ..close();
    return Path.combine(PathOperation.union, Path.combine(PathOperation.union, rotated, stem), flag);
  }

  @override
  void paint(Canvas canvas, Size size) {
    final p = notePath(size);
    if (shadow != null) {
      canvas.drawPath(p.shift(Offset(size.width * 0.03, size.width * 0.05)), Paint()..color = shadow!);
    }
    canvas.drawPath(p, Paint()..color = color);
  }

  @override
  bool shouldRepaint(NotePainter old) => old.color != color || old.shadow != shadow;
}

/// Золотая монета с нотой — значок Эха.
class CoinNote extends StatelessWidget {
  const CoinNote({super.key, this.size = 28});

  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: const BoxDecoration(color: J.navy, shape: BoxShape.circle),
      padding: EdgeInsets.all(size * 0.06),
      child: Container(
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFFFFD64D), Color(0xFFFF9E1B)],
          ),
        ),
        padding: EdgeInsets.all(size * 0.1),
        child: Container(
          decoration: const BoxDecoration(
            shape: BoxShape.circle,
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xFFFFEC8C), Color(0xFFFFBE32)],
            ),
          ),
          alignment: Alignment.center,
          child: NoteGlyph(size: size * 0.5, shadow: const Color(0xFFC46E0A)),
        ),
      ),
    );
  }
}

/// Чешуйка — валюта боссов (синий кристаллик).
class ScaleIcon extends StatelessWidget {
  const ScaleIcon({super.key, this.size = 24});

  final double size;

  @override
  Widget build(BuildContext context) => CustomPaint(size: Size.square(size), painter: _ScalePainter());
}

class _ScalePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final outer = Path()
      ..moveTo(w * 0.5, 0)
      ..quadraticBezierTo(w * 1.05, w * 0.35, w * 0.5, w)
      ..quadraticBezierTo(-w * 0.05, w * 0.35, w * 0.5, 0)
      ..close();
    canvas.drawPath(outer, Paint()..color = J.navy);
    final inner = Path()
      ..moveTo(w * 0.5, w * 0.12)
      ..quadraticBezierTo(w * 0.9, w * 0.38, w * 0.5, w * 0.86)
      ..quadraticBezierTo(w * 0.1, w * 0.38, w * 0.5, w * 0.12)
      ..close();
    canvas.drawPath(
      inner,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFF9CF6FF), Color(0xFF3F8CFF)],
        ).createShader(Offset.zero & size),
    );
    canvas.drawOval(
      Rect.fromLTWH(w * 0.36, w * 0.24, w * 0.14, w * 0.2),
      Paint()..color = Colors.white.withValues(alpha: 0.8),
    );
  }

  @override
  bool shouldRepaint(_ScalePainter old) => false;
}

/// Светлое окно с тёмной рамкой — основа всех панелей.
class JuicyPanel extends StatelessWidget {
  const JuicyPanel({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.radius = 26});

  final Widget child;
  final EdgeInsets padding;
  final double radius;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(radius + 4)),
      padding: const EdgeInsets.all(4),
      child: Container(
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [J.panelTop, J.panelBottom],
          ),
          borderRadius: BorderRadius.circular(radius),
        ),
        padding: padding,
        child: child,
      ),
    );
  }
}

/// Белая карточка с тёмной обводкой и тенью — строка магазина.
class JuicyCard extends StatelessWidget {
  const JuicyCard({super.key, required this.child, this.dark = false, this.padding = const EdgeInsets.all(10)});

  final Widget child;
  final bool dark;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: J.navy,
        borderRadius: BorderRadius.circular(24),
        boxShadow: const [BoxShadow(color: Color(0x332040A0), offset: Offset(0, 6), blurRadius: 8)],
      ),
      padding: const EdgeInsets.all(3),
      child: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: dark ? const [Color(0xFF46528C), Color(0xFF303A6C)] : const [J.card, J.cardBottom],
          ),
          borderRadius: BorderRadius.circular(21),
        ),
        padding: padding,
        child: child,
      ),
    );
  }
}

/// Плашка «неба» под иконкой предмета.
class IconTile extends StatelessWidget {
  const IconTile({super.key, required this.child, this.size = 72, this.dark = false});

  final Widget child;
  final double size;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: dark ? const [Color(0xFF5A68AA), Color(0xFF46528C)] : const [Color(0xFFB2E2FF), Color(0xFF78C2FF)],
        ),
        borderRadius: BorderRadius.circular(18),
      ),
      padding: EdgeInsets.all(size * 0.06),
      child: child,
    );
  }
}

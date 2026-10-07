import 'package:flutter/material.dart';

import '../scene/garden_glyphs.dart';
import 'palette.dart';

/// Кремовая «бумажная» карточка.
class PaperCard extends StatelessWidget {
  const PaperCard({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.color});

  final Widget child;
  final EdgeInsets padding;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: color ?? Palette.paper,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Palette.line),
      ),
      child: child,
    );
  }
}

/// Полупрозрачная плашка поверх сцены: текст читается на любом небе.
class ScenePill extends StatelessWidget {
  const ScenePill({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
  });

  final Widget child;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: Palette.paper.withValues(alpha: 0.86),
        borderRadius: BorderRadius.circular(18),
        boxShadow: [
          BoxShadow(color: const Color(0xFF1B2030).withValues(alpha: 0.12), blurRadius: 12, offset: const Offset(0, 3)),
        ],
      ),
      child: child,
    );
  }
}

/// Значок растения, нарисованный тем же кодом, что и сад на ките.
class GlyphIcon extends StatelessWidget {
  const GlyphIcon({super.key, required this.tier, this.size = 44, this.isBird = false});

  final int tier;
  final double size;
  final bool isBird;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: Palette.paperDeep, borderRadius: BorderRadius.circular(14)),
      child: CustomPaint(
        painter: _GlyphPainter(tier: tier, isBird: isBird),
      ),
    );
  }
}

class _GlyphPainter extends CustomPainter {
  _GlyphPainter({required this.tier, required this.isBird});

  final int tier;
  final bool isBird;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.translate(size.width / 2, size.height / 2);
    if (isBird) {
      final paint = Paint()
        ..color = Palette.calm
        ..strokeWidth = 2.2
        ..strokeCap = StrokeCap.round
        ..style = PaintingStyle.stroke;
      final u = size.width * 0.32;
      for (final side in [-1.0, 1.0]) {
        canvas.drawPath(
          Path()
            ..moveTo(0, u * 0.25)
            ..quadraticBezierTo(side * u * 0.45, -u * 0.35, side * u, u * 0.05),
          paint,
        );
      }
    } else {
      final palette = Palette.region(0).restored;
      paintGardenGlyph(canvas, tier, size.width * 0.2, 0, tier, palette);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _GlyphPainter oldDelegate) => oldDelegate.tier != tier || oldDelegate.isBird != isBird;
}

/// «1 ч 05 мин», «12 мин», «40 с».
String formatDuration(double seconds) {
  final s = seconds.round();
  final h = s ~/ 3600;
  final m = (s % 3600) ~/ 60;
  if (h > 0) return '$h ч ${m.toString().padLeft(2, '0')} мин';
  if (m > 0) return '$m мин';
  return '$s с';
}

/// «9:41» для таймера буста.
String formatClock(int ms) {
  final s = (ms / 1000).ceil();
  return '${s ~/ 60}:${(s % 60).toString().padLeft(2, '0')}';
}

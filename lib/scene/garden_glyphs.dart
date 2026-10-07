import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/painting.dart';

import '../ui/color_mix.dart';
import '../ui/palette.dart';

/// Растения и постройки сада, вид сверху. Рисуются и на спине кита, и в
/// значках списка покупок. `tier` — индекс растения в контенте, `u` — единица
/// размера, `t` — время сцены (вращение мельниц и обсерватории).
void paintGardenGlyph(Canvas canvas, int tier, double u, double t, int index, ScenePalette g) {
  final flowers = [g.bloom, lerpOklab(g.bloom, g.glow, 0.55), lerpOklab(g.bloom, g.whaleRim, 0.45)];
  switch (tier) {
    case 0: // цветок
      final petal = Paint()..color = flowers[index % flowers.length];
      for (var k = 0; k < 5; k++) {
        final a = k * math.pi * 2 / 5 + index;
        canvas.drawCircle(Offset(math.cos(a), math.sin(a)) * (u * 0.42), u * 0.38, petal);
      }
      canvas.drawCircle(Offset.zero, u * 0.3, Paint()..color = g.glow);
    case 1: // куст
      canvas.drawCircle(Offset(-u * 0.35, u * 0.1), u * 0.55, Paint()..color = g.green);
      canvas.drawCircle(Offset(u * 0.35, u * 0.12), u * 0.5, Paint()..color = g.green);
      canvas.drawCircle(Offset(0, -u * 0.3), u * 0.55, Paint()..color = lerpOklab(g.green, g.glow, 0.2));
      canvas.drawCircle(
        Offset(-u * 0.12, -u * 0.42),
        u * 0.17,
        Paint()..color = lerpOklab(g.green, const Color(0xFFFFFFFF), 0.5),
      );
    case 2: // улей
      canvas.drawOval(
        Rect.fromCenter(center: Offset.zero, width: u * 1.2, height: u * 0.95),
        Paint()..color = lerpOklab(g.glow, const Color(0xFFE0A030), 0.5),
      );
      final stripe = Paint()
        ..color = lerpOklab(g.glow, const Color(0xFF8A5A10), 0.6)
        ..style = PaintingStyle.stroke
        ..strokeWidth = u * 0.12;
      canvas.drawLine(Offset(-u * 0.45, -u * 0.12), Offset(u * 0.45, -u * 0.12), stripe);
      canvas.drawLine(Offset(-u * 0.4, u * 0.18), Offset(u * 0.4, u * 0.18), stripe);
    case 3: // мельница
      canvas.drawCircle(Offset.zero, u * 0.45, Paint()..color = lerpOklab(g.cloud, const Color(0xFFD9C3A0), 0.5));
      final blade = Paint()
        ..color = g.cloud
        ..strokeWidth = u * 0.26
        ..strokeCap = StrokeCap.round;
      for (var k = 0; k < 4; k++) {
        final a = t * 1.4 + k * math.pi / 2;
        canvas.drawLine(Offset.zero, Offset(math.cos(a), math.sin(a)) * (u * 1.1), blade);
      }
      canvas.drawCircle(Offset.zero, u * 0.16, Paint()..color = const Color(0xFF9C7B5B));
    case 4: // фонарный домик
      canvas.drawCircle(
        Offset.zero,
        u * 2.3,
        Paint()
          ..shader = ui.Gradient.radial(Offset.zero, u * 2.3, [
            g.glow.withValues(alpha: 0.55),
            g.glow.withValues(alpha: 0),
          ]),
      );
      final roof = Rect.fromCenter(center: Offset.zero, width: u * 1.3, height: u * 1.1);
      canvas.drawRRect(
        RRect.fromRectAndRadius(roof, Radius.circular(u * 0.18)),
        Paint()..color = lerpOklab(g.bloom, const Color(0xFF9B4E3A), 0.6),
      );
      canvas.drawLine(
        Offset(-u * 0.65, 0),
        Offset(u * 0.65, 0),
        Paint()
          ..color = const Color(0xFF6E3A2B).withValues(alpha: 0.6)
          ..strokeWidth = u * 0.1,
      );
      canvas.drawCircle(Offset(u * 0.3, -u * 0.25), u * 0.14, Paint()..color = g.glow);
    default: // обсерватория
      canvas.drawCircle(Offset.zero, u * 0.95, Paint()..color = lerpOklab(g.cloud, g.whaleRim, 0.3));
      canvas.drawCircle(
        Offset.zero,
        u * 0.95,
        Paint()
          ..color = g.whaleBack.withValues(alpha: 0.35)
          ..style = PaintingStyle.stroke
          ..strokeWidth = u * 0.08,
      );
      canvas.save();
      canvas.rotate(t * 0.25);
      canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(-u * 0.09, -u * 0.95, u * 0.18, u * 0.95), Radius.circular(u * 0.05)),
        Paint()..color = g.whaleBack,
      );
      canvas.restore();
  }
}

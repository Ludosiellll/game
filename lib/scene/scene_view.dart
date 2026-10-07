import 'dart:async';

import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../app/game_controller.dart';
import '../core/format.dart';
import 'scene_effects.dart';
import 'sky_scene_painter.dart';

/// Сцена с китом. Владеет игровым циклом: каждый кадр продвигает экономику
/// и анимации. Тап любым пальцем по небу — нота.
class SceneView extends StatefulWidget {
  const SceneView({super.key, required this.controller});

  final GameController controller;

  @override
  State<SceneView> createState() => _SceneViewState();
}

class _SceneViewState extends State<SceneView> with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  final SceneEffects _effects = SceneEffects();
  final _FrameClock _frame = _FrameClock();
  StreamSubscription<TapEffect>? _taps;
  Duration _last = Duration.zero;

  @override
  void initState() {
    super.initState();
    _ticker = createTicker(_onTick)..start();
    _taps = widget.controller.taps.listen((e) {
      _effects.addTap(Offset(e.x, e.y), '+${formatBig(e.value)}');
    });
  }

  void _onTick(Duration elapsed) {
    final dt = _last == Duration.zero ? 0.0 : (elapsed - _last).inMicroseconds / 1e6;
    _last = elapsed;
    final step = dt.clamp(0.0, 0.1);
    widget.controller.onFrame(dt);
    _effects.update(step);
    _frame.tick();
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
        child: CustomPaint(
          painter: SkyScenePainter(stateOf: () => widget.controller.state, effects: _effects, repaint: _frame),
          size: Size.infinite,
        ),
      ),
    );
  }
}

/// Сигнал «новый кадр» для перерисовки сцены без пересборки виджетов.
class _FrameClock extends ChangeNotifier {
  void tick() => notifyListeners();
}

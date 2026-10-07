import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// Цвет рисовой бумаги из ролика (такой же у заставки Android).
const Color paperColor = Color(0xFFECE3CD);
const Color inkColor = Color(0xFF181513);

const _brush = TextStyle(fontFamily: 'MarckScript', color: inkColor);

/// Загрузочный экран: свиток с названием и китами, полоса загрузки — мазок туши.
class LoadingScreen extends StatelessWidget {
  const LoadingScreen({super.key, required this.progress});

  /// 0…1.
  final double progress;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: paperColor,
      child: Stack(
        fit: StackFit.expand,
        children: [
          Image.asset('assets/intro/loading.jpg', fit: BoxFit.cover, gaplessPlayback: true),
          Align(
            alignment: const Alignment(0, 0.86),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(width: 240, height: 22, child: CustomPaint(painter: _BrushBarPainter(progress))),
                const SizedBox(height: 8),
                const Text(
                  'Небо просыпается…',
                  style: TextStyle(fontFamily: 'MarckScript', color: inkColor, fontSize: 22),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Мазок кисти слева направо: толще в середине, сужается к концам, край неровный.
class _BrushBarPainter extends CustomPainter {
  _BrushBarPainter(this.progress);

  final double progress;

  @override
  void paint(Canvas canvas, Size size) {
    final faint = Paint()..color = inkColor.withValues(alpha: 0.12);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, size.height * 0.35, size.width, size.height * 0.3),
        const Radius.circular(6),
      ),
      faint,
    );
    final p = progress.clamp(0.0, 1.0);
    if (p <= 0) return;
    final ink = Paint()..color = inkColor.withValues(alpha: 0.88);
    final end = size.width * p;
    final path = Path()..moveTo(0, size.height / 2);
    const steps = 40;
    final top = <Offset>[];
    final bottom = <Offset>[];
    for (var i = 0; i <= steps; i++) {
      final x = end * i / steps;
      final t = x / size.width;
      final taper = math.min(1.0, math.min(t / 0.08 + 0.2, (p - t) / 0.06 + 0.25));
      final w = size.height * 0.42 * taper * (0.9 + 0.1 * math.sin(i * 1.7));
      top.add(Offset(x, size.height / 2 - w));
      bottom.add(Offset(x, size.height / 2 + w * 0.8));
    }
    for (final o in top) {
      path.lineTo(o.dx, o.dy);
    }
    for (final o in bottom.reversed) {
      path.lineTo(o.dx, o.dy);
    }
    path.close();
    canvas.drawPath(path, ink);
  }

  @override
  bool shouldRepaint(_BrushBarPainter old) => old.progress != progress;
}

/// Ролик-предыстория. Его можно пропустить; если видео не запускается
/// (нет кодека, тест, веб без поддержки), экран сразу уступает место игре.
class IntroScreen extends StatefulWidget {
  const IntroScreen({super.key, required this.onDone});

  final VoidCallback onDone;

  static const asset = 'assets/video/intro.mp4';

  @override
  State<IntroScreen> createState() => _IntroScreenState();
}

class _IntroScreenState extends State<IntroScreen> {
  VideoPlayerController? _video;
  Timer? _timeout;
  Timer? _skipTimer;
  bool _done = false;
  bool _showSkip = false;

  @override
  void initState() {
    super.initState();
    final video = VideoPlayerController.asset(IntroScreen.asset);
    _video = video;
    video.addListener(_onVideo);
    video.initialize().then((_) {
      if (!mounted || _done) return;
      _timeout?.cancel();
      setState(() {});
      video.play();
    }, onError: (_) => _finish());
    // Видео не завелось за 5 секунд — не держим игрока.
    _timeout = Timer(const Duration(seconds: 5), () {
      if (!(_video?.value.isInitialized ?? false)) _finish();
    });
    _skipTimer = Timer(const Duration(milliseconds: 900), () {
      if (mounted) setState(() => _showSkip = true);
    });
  }

  void _onVideo() {
    final v = _video!.value;
    if (v.hasError) {
      _finish();
    } else if (v.isInitialized &&
        v.duration > Duration.zero &&
        v.position >= v.duration - const Duration(milliseconds: 120)) {
      _finish();
    }
  }

  void _finish() {
    if (_done) return;
    _done = true;
    _timeout?.cancel();
    _video?.pause();
    widget.onDone();
  }

  @override
  void dispose() {
    _timeout?.cancel();
    _skipTimer?.cancel();
    _video?.removeListener(_onVideo);
    _video?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final video = _video;
    final ready = video != null && video.value.isInitialized;
    return Scaffold(
      backgroundColor: paperColor,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (ready)
            FittedBox(
              fit: BoxFit.cover,
              clipBehavior: Clip.hardEdge,
              child: SizedBox(
                width: video.value.size.width,
                height: video.value.size.height,
                child: VideoPlayer(video),
              ),
            ),
          SafeArea(
            child: Align(
              alignment: Alignment.topRight,
              child: AnimatedOpacity(
                opacity: _showSkip ? 1 : 0,
                duration: const Duration(milliseconds: 400),
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Material(
                    color: paperColor.withValues(alpha: 0.85),
                    shape: const StadiumBorder(side: BorderSide(color: inkColor, width: 1.5)),
                    child: InkWell(
                      key: const ValueKey('intro-skip'),
                      customBorder: const StadiumBorder(),
                      onTap: _finish,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                        child: Text('Пропустить »', style: _brush.copyWith(fontSize: 22)),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

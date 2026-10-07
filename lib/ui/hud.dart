import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import 'palette.dart';
import 'widgets.dart';

/// Верх сцены: Эхо, доход и буст.
class Hud extends StatelessWidget {
  const Hud({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final now = controller.nowMs;
        return Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ScenePill(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          formatBig(s.echo),
                          style: const TextStyle(
                            fontSize: 30,
                            fontWeight: FontWeight.w800,
                            color: Palette.ink,
                            height: 1.05,
                          ),
                        ),
                        Text(
                          'Эхо · +${formatRate(eco.incomePerSecond(s, now))}',
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Palette.inkSoft),
                        ),
                      ],
                    ),
                  ),
                  const Spacer(),
                  _BoostChip(controller: controller),
                ],
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Низ сцены: регион, цвет неба, кнопки перелёта и песни.
class RegionBar extends StatelessWidget {
  const RegionBar({super.key, required this.controller, required this.onOpenSong});

  final GameController controller;
  final VoidCallback onOpenSong;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final progress = eco.regionProgress(s);
        final region = s.content.regions[s.region];
        return Padding(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (eco.canMigrate(s))
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: FilledButton.icon(
                      onPressed: controller.migrate,
                      style: FilledButton.styleFrom(backgroundColor: Palette.accent, foregroundColor: Colors.white),
                      icon: const Icon(Icons.arrow_upward_rounded, size: 18),
                      label: const Text('Лететь дальше', style: TextStyle(fontWeight: FontWeight.w800)),
                    ),
                  ),
                ),
              if (eco.canSing(s))
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: FilledButton.icon(
                      onPressed: onOpenSong,
                      style: FilledButton.styleFrom(backgroundColor: Palette.calm, foregroundColor: Colors.white),
                      icon: const Icon(Icons.music_note_rounded, size: 18),
                      label: const Text('Можно спеть Долгую песнь', style: TextStyle(fontWeight: FontWeight.w800)),
                    ),
                  ),
                ),
              ScenePill(
                padding: const EdgeInsets.fromLTRB(14, 8, 14, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            region.name,
                            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Palette.ink),
                          ),
                        ),
                        Text(
                          'Цвет неба ${(progress * 100).floor()}%',
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Palette.inkSoft),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    _ProgressBar(value: progress, region: s.region),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ProgressBar extends StatelessWidget {
  const _ProgressBar({required this.value, required this.region});

  final double value;
  final int region;

  @override
  Widget build(BuildContext context) {
    final look = Palette.region(region).restored;
    return ClipRRect(
      borderRadius: BorderRadius.circular(6),
      child: SizedBox(
        height: 9,
        child: Stack(
          fit: StackFit.expand,
          children: [
            const ColoredBox(color: Palette.paperDeep),
            FractionallySizedBox(
              alignment: Alignment.centerLeft,
              widthFactor: value.clamp(0.0, 1.0),
              child: DecoratedBox(
                decoration: BoxDecoration(gradient: LinearGradient(colors: [look.whaleRim, look.bloom, look.glow])),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BoostChip extends StatelessWidget {
  const _BoostChip({required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final now = controller.nowMs;
    if (eco.isBoosted(s, now)) {
      return ScenePill(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Text(
          '×2 · ${formatClock(s.boostUntilMs - now)}',
          style: const TextStyle(fontWeight: FontWeight.w800, color: Palette.accentDeep),
        ),
      );
    }
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: controller.watchBoostAd,
        child: const ScenePill(
          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.play_circle_outline_rounded, size: 18, color: Palette.accentDeep),
              SizedBox(width: 6),
              Text(
                '×2 на 10 мин',
                style: TextStyle(fontWeight: FontWeight.w800, color: Palette.ink),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

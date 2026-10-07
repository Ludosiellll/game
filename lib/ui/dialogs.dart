import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../core/format.dart';
import '../core/story.dart';
import 'palette.dart';
import 'widgets.dart';

const _bodyStyle = TextStyle(fontSize: 15, height: 1.35, color: Palette.ink);
const _noteStyle = TextStyle(fontSize: 13.5, height: 1.35, color: Palette.inkSoft, fontStyle: FontStyle.italic);

Future<void> showOfflineDialog(BuildContext context, GameController controller) async {
  final r = controller.pendingOffline;
  if (r == null) return;
  final s = controller.state;
  final note = diaryNotes[s.taps % diaryNotes.length];
  final capHours = r.capped ? (r.seconds / 3600).round() : 0;
  await showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      backgroundColor: Palette.paper,
      title: const Text('Пока тебя не было'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Кит летел ${formatDuration(r.seconds)} и напел ${formatBig(r.earned)} Эха.', style: _bodyStyle),
          if (r.capped) ...[
            const SizedBox(height: 6),
            Text(
              'Засчитано $capHours ч — это предел. «Долгий сон» в Дереве памяти его увеличит.',
              style: const TextStyle(fontSize: 13, color: Palette.inkSoft),
            ),
          ],
          const SizedBox(height: 10),
          Text(note, style: _noteStyle),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () async {
            Navigator.of(context).pop();
            await controller.claimOffline(doubled: false);
          },
          child: const Text('Забрать'),
        ),
        FilledButton(
          onPressed: () async {
            Navigator.of(context).pop();
            await controller.claimOffline(doubled: true);
          },
          child: const Text('Забрать ×2 за рекламу'),
        ),
      ],
    ),
  );
}

Future<void> showMigrationDialog(BuildContext context, GameController controller, int region) {
  final c = controller.state.content;
  final def = c.regions[region];
  final unlocked = c.generators.where((g) => g.region == region).map((g) => g.name).join(', ');
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: Palette.paper,
      title: Text('Кит летит дальше: ${def.name}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Доход ×${c.regionIncomeMultiplier}.${unlocked.isEmpty ? '' : ' Новые растения: $unlocked.'}',
            style: _bodyStyle,
          ),
          if (regionArrival[def.id] != null) ...[
            const SizedBox(height: 10),
            Text(regionArrival[def.id]!, style: _noteStyle),
          ],
        ],
      ),
      actions: [FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Дальше'))],
    ),
  );
}

Future<void> showSongDialog(BuildContext context, GameController controller, SongEvent e) {
  final c = controller.state.content;
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      backgroundColor: Palette.paper,
      title: const Text('Долгая песнь'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Сад отцвёл и оставил ${formatBig(e.seeds)} семян памяти. Потрать их во вкладке «Песнь».',
            style: _bodyStyle,
          ),
          for (final id in e.woke) ...[
            const SizedBox(height: 12),
            Text(
              'Проснулся ${c.whaleById(id).name}: ${c.whaleById(id).description}',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: Palette.calm),
            ),
            const SizedBox(height: 4),
            Text(whaleMemory[id] ?? '', style: _noteStyle),
          ],
        ],
      ),
      actions: [FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Петь дальше'))],
    ),
  );
}

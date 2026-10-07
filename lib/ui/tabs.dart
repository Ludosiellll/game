import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/game_controller.dart';
import '../core/big.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import '../core/game_state.dart';
import '../core/story.dart';
import 'palette.dart';
import 'widgets.dart';

const _titleStyle = TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: Palette.ink);
const _captionStyle = TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: Palette.inkSoft, height: 1.3);

// ---------------------------------------------------------------------------
// Сад

class GardenTab extends StatelessWidget {
  const GardenTab({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final gens = s.content.generators;
        final unlocked = [
          for (var i = 0; i < gens.length; i++)
            if (eco.isUnlocked(s, gens[i].id)) i,
        ];
        final nextLocked = gens.indexWhere((g) => !eco.isUnlocked(s, g.id));
        return ListView(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 16),
          children: [
            Row(
              children: [
                const Text('Покупать', style: _captionStyle),
                const Spacer(),
                SegmentedButton<BuyMode>(
                  showSelectedIcon: false,
                  style: SegmentedButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    selectedBackgroundColor: Palette.calm,
                    selectedForegroundColor: Colors.white,
                  ),
                  segments: const [
                    ButtonSegment(value: BuyMode.one, label: Text('×1')),
                    ButtonSegment(value: BuyMode.ten, label: Text('×10')),
                    ButtonSegment(value: BuyMode.max, label: Text('Макс.')),
                  ],
                  selected: {controller.buyMode},
                  onSelectionChanged: (set) => controller.setBuyMode(set.first),
                ),
              ],
            ),
            const SizedBox(height: 10),
            _ShopTile(
              icon: const GlyphIcon(tier: 0, isBird: true),
              title: '${s.content.bird.name} · ${s.birds}',
              subtitle: 'Поёт сама: ${formatRate(_birdGain(controller))} за птицу',
              amount: controller.birdAmount(),
              price: controller.birdPrice(),
              affordable: s.echo >= controller.birdPrice(),
              onBuy: controller.buyBird,
            ),
            for (final i in unlocked) ...[
              const SizedBox(height: 8),
              _ShopTile(
                icon: GlyphIcon(tier: i),
                title: '${gens[i].name} · ${s.owned(gens[i].id)}',
                subtitle: _generatorSubtitle(controller, i),
                amount: controller.generatorAmount(gens[i].id),
                price: controller.generatorPrice(gens[i].id),
                affordable: s.echo >= controller.generatorPrice(gens[i].id),
                onBuy: () => controller.buyGenerator(gens[i].id),
              ),
            ],
            if (nextLocked >= 0) ...[
              const SizedBox(height: 8),
              PaperCard(
                color: Palette.paperDeep,
                child: Row(
                  children: [
                    Opacity(opacity: 0.45, child: GlyphIcon(tier: nextLocked)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        '${gens[nextLocked].name} откроется в регионе «${s.content.regions[gens[nextLocked].region].name}»',
                        style: _captionStyle,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        );
      },
    );
  }

  static Big _birdGain(GameController c) {
    final s = c.state;
    final perBird = s.content.bird.notesPerSecond;
    return eco.tapValue(s, c.nowMs).mulNum(perBird);
  }

  static String _generatorSubtitle(GameController c, int index) {
    final s = c.state;
    final g = s.content.generators[index];
    final owned = s.owned(g.id);
    final per = eco.productionPerUnit(s, g.id, c.nowMs);
    final next = s.content.ownedMilestones.where((m) => m > owned).toList();
    final milestone = next.isEmpty ? '' : ' · до ×2 ещё ${next.first - owned}';
    return '+${formatRate(per)} каждый$milestone';
  }
}

class _ShopTile extends StatelessWidget {
  const _ShopTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.amount,
    required this.price,
    required this.affordable,
    required this.onBuy,
  });

  final Widget icon;
  final String title;
  final String subtitle;
  final int amount;
  final Big price;
  final bool affordable;
  final VoidCallback onBuy;

  @override
  Widget build(BuildContext context) {
    return PaperCard(
      padding: const EdgeInsets.fromLTRB(10, 10, 10, 10),
      child: Row(
        children: [
          icon,
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: _titleStyle),
                const SizedBox(height: 2),
                Text(subtitle, style: _captionStyle),
              ],
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 96,
            child: FilledButton(
              onPressed: affordable ? onBuy : null,
              style: FilledButton.styleFrom(
                backgroundColor: Palette.accent,
                disabledBackgroundColor: Palette.paperDeep,
                foregroundColor: Colors.white,
                disabledForegroundColor: Palette.inkSoft,
                padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 6),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('Купить ×$amount', style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w700)),
                  Text(formatBig(price), style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Песнь

class SongTab extends StatelessWidget {
  const SongTab({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final c = s.content;
        final canSing = eco.canSing(s);
        return ListView(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 16),
          children: [
            PaperCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      const Expanded(child: Text('Семена памяти', style: _titleStyle)),
                      Text(
                        formatBig(s.seeds),
                        style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Palette.calm),
                      ),
                    ],
                  ),
                  Text('Спето песен: ${s.songs}', style: _captionStyle),
                  const SizedBox(height: 10),
                  if (canSing) ...[
                    FilledButton(
                      onPressed: controller.sing,
                      style: FilledButton.styleFrom(
                        backgroundColor: Palette.calm,
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                      ),
                      child: Text(
                        'Спеть Долгую песнь · +${formatBig(eco.seedsForSong(s))} семян',
                        style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15),
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text(
                      'Сад отцветёт и оставит семена, его песня уйдёт в спящего кита. '
                      'Эхо, растения и птицы начнутся заново. Чем дольше играешь в последнем регионе, тем больше семян.',
                      style: _captionStyle,
                    ),
                  ] else
                    Text(
                      'Песнь откроется, когда небо в регионе «${c.regions.last.name}» станет цветным. '
                      'Сейчас регион ${s.region + 1} из ${c.regions.length}.',
                      style: _captionStyle,
                    ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            const Text('Дерево памяти', style: _titleStyle),
            const SizedBox(height: 6),
            for (final node in c.memory) ...[
              _MemoryTile(controller: controller, nodeId: node.id),
              const SizedBox(height: 8),
            ],
            const SizedBox(height: 6),
            const Text('Стая', style: _titleStyle),
            const SizedBox(height: 6),
            for (final w in c.whales) ...[
              PaperCard(
                color: s.whales.contains(w.id) ? Palette.paper : Palette.paperDeep,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.whales.contains(w.id) ? '${w.name} · ${w.description}' : '${w.name} спит',
                      style: _titleStyle,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      s.whales.contains(w.id) ? (whaleMemory[w.id] ?? '') : _wakeHint(s, w.id),
                      style: _captionStyle,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
            ],
          ],
        );
      },
    );
  }
}

class _MemoryTile extends StatelessWidget {
  const _MemoryTile({required this.controller, required this.nodeId});

  final GameController controller;
  final String nodeId;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final node = s.content.memoryById(nodeId);
    final level = s.memoryLevel(nodeId);
    final cost = eco.memoryCost(s, nodeId);
    final affordable = cost != null && s.seeds >= cost;
    return PaperCard(
      padding: const EdgeInsets.fromLTRB(14, 10, 10, 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${node.name} · ур. $level', style: _titleStyle),
                const SizedBox(height: 2),
                Text(node.description, style: _captionStyle),
              ],
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 96,
            child: FilledButton(
              onPressed: affordable ? () => controller.buyMemory(nodeId) : null,
              style: FilledButton.styleFrom(
                backgroundColor: Palette.calm,
                disabledBackgroundColor: Palette.paperDeep,
                disabledForegroundColor: Palette.inkSoft,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(
                cost == null ? 'Макс.' : '${formatBig(cost)} сем.',
                style: const TextStyle(fontWeight: FontWeight.w800),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Ещё

class MoreTab extends StatelessWidget {
  const MoreTab({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        return ListView(
          padding: const EdgeInsets.fromLTRB(12, 10, 12, 16),
          children: [
            PaperCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Статистика', style: _titleStyle),
                  const SizedBox(height: 6),
                  _stat('Эха за всё время', formatBig(s.totalEarned)),
                  _stat('Тапов', '${s.taps}'),
                  _stat('Время в игре', formatDuration(s.playSeconds)),
                  _stat('Спето песен', '${s.songs}'),
                ],
              ),
            ),
            const SizedBox(height: 10),
            PaperCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('Сохранение', style: _titleStyle),
                  const SizedBox(height: 4),
                  const Text(
                    'Игра сохраняется сама. Код прогресса — запасная копия: его можно вставить на другом устройстве.',
                    style: _captionStyle,
                  ),
                  const SizedBox(height: 8),
                  OutlinedButton(
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: controller.exportProgress()));
                      if (context.mounted) {
                        ScaffoldMessenger.of(context)
                            .showSnackBar(const SnackBar(content: Text('Код прогресса скопирован')));
                      }
                    },
                    child: const Text('Скопировать код прогресса'),
                  ),
                  OutlinedButton(onPressed: () => _importDialog(context), child: const Text('Загрузить из кода')),
                ],
              ),
            ),
            const SizedBox(height: 10),
            PaperCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('Для тестирования', style: _titleStyle),
                  const SizedBox(height: 4),
                  const Text('Промотка считает время так, будто ты был в игре и не нажимал.', style: _captionStyle),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      OutlinedButton(onPressed: () => controller.debugSkip(600), child: const Text('+10 минут')),
                      OutlinedButton(onPressed: () => controller.debugSkip(3600), child: const Text('+1 час')),
                      OutlinedButton(onPressed: () => _confirmReset(context), child: const Text('Начать заново')),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            PaperCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('О прототипе', style: _titleStyle),
                  const SizedBox(height: 4),
                  const Text(
                    'Песнь небесного кита, версия 0.1. Вся графика нарисована кодом. Реклама пока заглушка.',
                    style: _captionStyle,
                  ),
                  TextButton(
                    onPressed: () => showLicensePage(context: context, applicationName: 'Песнь небесного кита'),
                    child: const Text('Лицензии шрифтов и библиотек'),
                  ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _stat(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 2),
    child: Row(
      children: [
        Expanded(child: Text(label, style: _captionStyle)),
        Text(
          value,
          style: const TextStyle(fontWeight: FontWeight.w800, color: Palette.ink),
        ),
      ],
    ),
  );

  Future<void> _importDialog(BuildContext context) async {
    final field = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: Palette.paper,
        title: const Text('Загрузить из кода'),
        content: TextField(
          controller: field,
          maxLines: 3,
          decoration: const InputDecoration(hintText: 'Вставь код прогресса'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Загрузить')),
        ],
      ),
    );
    if (ok != true) return;
    final imported = await controller.importProgress(field.text);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(imported ? 'Прогресс загружен' : 'Код не подошёл — проверь, что он скопирован целиком')),
    );
  }

  Future<void> _confirmReset(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: Palette.paper,
        title: const Text('Начать заново?'),
        content: const Text('Весь прогресс, семена и проснувшиеся киты будут удалены.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Начать заново')),
        ],
      ),
    );
    if (ok == true) await controller.resetProgress();
  }
}


/// Когда проснётся кит стаи: при переходе нашего кита в нужный возраст.
String _wakeHint(GameState s, String whaleId) {
  for (final a in s.content.ages) {
    if (a.whale == whaleId) return 'Проснётся, когда наш кит станет: ${a.name.toLowerCase()}.';
  }
  return 'Спит.';
}

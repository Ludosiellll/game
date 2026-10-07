import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import '../core/story.dart';
import '../scene/whale_scene.dart';
import 'hud.dart';
import 'juicy.dart';

/// Окно в сочном стиле: панель, лента-заголовок, кнопки внизу.
Future<T?> showJuicyDialog<T>(
  BuildContext context, {
  required String title,
  required Widget content,
  required List<Widget> actions,
  ButtonColors ribbon = J.goldButton,
  bool dismissible = false,
}) {
  return showDialog<T>(
    context: context,
    barrierDismissible: dismissible,
    barrierColor: const Color(0x88102040),
    builder: (ctx) => Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: const EdgeInsets.symmetric(horizontal: 20),
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 30),
            child: JuicyPanel(
              padding: const EdgeInsets.fromLTRB(16, 44, 16, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  content,
                  const SizedBox(height: 14),
                  for (final a in actions) ...[a, const SizedBox(height: 8)],
                ],
              ),
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Center(child: Ribbon(title, colors: ribbon, size: 26)),
          ),
        ],
      ),
    ),
  );
}

const TextStyle _body = TextStyle(fontWeight: FontWeight.w800, fontSize: 16, color: J.navy, height: 1.3);
const TextStyle _note = TextStyle(
  fontWeight: FontWeight.w700,
  fontSize: 14,
  color: J.muted,
  height: 1.3,
  fontStyle: FontStyle.italic,
);

Widget _button(String text, ButtonColors colors, VoidCallback onPressed) => ChunkyButton(
  expand: true,
  colors: colors,
  onPressed: onPressed,
  child: OutlinedText(text, size: 19, strokeWidth: 3),
);

Future<void> showOfflineDialog(BuildContext context, GameController controller) async {
  final r = controller.pendingOffline;
  if (r == null) return;
  final years = r.seconds * controller.state.content.fight.yearsPerSecond;
  await showJuicyDialog<void>(
    context,
    title: 'С ВОЗВРАЩЕНИЕМ',
    content: Column(
      children: [
        Text('Кит летел ${formatDuration(r.seconds)} и напел', style: _body, textAlign: TextAlign.center),
        const SizedBox(height: 6),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [const CoinNote(size: 36), const SizedBox(width: 6), OutlinedText(formatBig(r.earned), size: 36)],
        ),
        if (years >= 1) ...[
          const SizedBox(height: 6),
          Text('и стал старше на ${yearsLabel(years)}', style: _body, textAlign: TextAlign.center),
        ],
        if (r.capped) ...[
          const SizedBox(height: 6),
          Text(
            'Офлайн копится до ${eco.offlineCapHours(controller.state).round()} ч.',
            style: _note,
            textAlign: TextAlign.center,
          ),
        ],
        const SizedBox(height: 8),
        Text(diaryNotes[controller.state.taps % diaryNotes.length], style: _note, textAlign: TextAlign.center),
      ],
    ),
    actions: [
      _button('×2 ЗА РЕКЛАМУ', J.greenButton, () {
        Navigator.pop(context);
        controller.claimOffline(doubled: true);
      }),
      _button('ЗАБРАТЬ', J.whiteButton, () {
        Navigator.pop(context);
        controller.claimOffline(doubled: false);
      }),
    ],
  );
}

Future<void> showAgeUpDialog(
  BuildContext context,
  SpriteAtlas atlas,
  GameController controller,
  int age,
  String? woke,
) {
  final def = controller.state.content.ages[age];
  return showJuicyDialog<void>(
    context,
    title: 'КИТ ВЫРОС!',
    ribbon: J.purpleButton,
    content: Column(
      children: [
        Container(
          height: 190,
          decoration: const BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(colors: [Color(0xCCFFE6A0), Color(0x00FFE6A0)]),
          ),
          child: WhalePortrait(atlas: atlas, state: controller.state, age: age, size: 190),
        ),
        OutlinedText(def.name, size: 30),
        const SizedBox(height: 8),
        Text(ageUp[def.id] ?? '', style: _body, textAlign: TextAlign.center),
        if (woke != null) ...[
          const SizedBox(height: 8),
          Text(
            'Проснулся ${controller.state.content.whaleById(woke).name}: ${controller.state.content.whaleById(woke).description}.',
            style: _body.copyWith(color: J.green),
            textAlign: TextAlign.center,
          ),
        ],
        const SizedBox(height: 4),
        Text('Мир вокруг стал меньше — или это кит стал больше?', style: _note, textAlign: TextAlign.center),
      ],
    ),
    actions: [_button('УРА!', J.goldButton, () => Navigator.pop(context))],
  );
}

Future<void> showBossWinDialog(BuildContext context, SpriteAtlas atlas, FightEndEvent e) {
  final r = e.reward!;
  final boss = e.fight.boss;
  final img = atlas.bosses[boss.id];
  final story = r.firstWin ? bossDefeat[boss.id] : null;
  return showJuicyDialog<void>(
    context,
    title: 'ПОБЕДА',
    ribbon: J.greenButton,
    content: Column(
      children: [
        if (img != null) Opacity(opacity: 0.85, child: SpriteImage(img, size: 120)),
        OutlinedText('${boss.name} отступил', size: 22, maxLines: 2),
        const SizedBox(height: 10),
        Wrap(
          alignment: WrapAlignment.center,
          spacing: 14,
          runSpacing: 6,
          children: [
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [const ScaleIcon(size: 26), const SizedBox(width: 4), OutlinedText('+${r.scales}', size: 24)],
            ),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const CoinNote(size: 26),
                const SizedBox(width: 4),
                OutlinedText('+${formatBig(r.echo)}', size: 24),
              ],
            ),
            if (r.years > 0) OutlinedText('+${yearsLabel(r.years)}', size: 24),
          ],
        ),
        if (story != null) ...[const SizedBox(height: 10), Text(story, style: _body, textAlign: TextAlign.center)],
      ],
    ),
    actions: [_button('ДАЛЬШЕ', J.greenButton, () => Navigator.pop(context))],
  );
}

Future<void> showMigrationDialog(BuildContext context, GameController controller, int region) {
  final def = controller.state.content.regions[region];
  return showJuicyDialog<void>(
    context,
    title: 'В ПУТЬ',
    ribbon: J.greenButton,
    content: Column(
      children: [
        OutlinedText(def.name, size: 28),
        const SizedBox(height: 8),
        Text(
          regionArrival[def.id] ?? 'Кит летит дальше. Новые растения ждут.',
          style: _body,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 6),
        Text(
          'Доход ×${controller.state.content.regionIncomeMultiplier} и новые растения в саду.',
          style: _note,
          textAlign: TextAlign.center,
        ),
      ],
    ),
    actions: [_button('ПОЛЕТЕЛИ', J.greenButton, () => Navigator.pop(context))],
  );
}

Future<void> showSongDialog(BuildContext context, GameController controller, SongEvent e) {
  final s = controller.state;
  return showJuicyDialog<void>(
    context,
    title: 'ДОЛГАЯ ПЕСНЬ',
    ribbon: J.purpleButton,
    content: Column(
      children: [
        Text('Кит уснул и спел свою песню небу. Сад отцвёл и оставил', style: _body, textAlign: TextAlign.center),
        const SizedBox(height: 6),
        OutlinedText('${formatBig(e.seeds)} семян памяти', size: 26, maxLines: 2),
        const SizedBox(height: 8),
        Text(chronicle[(s.songs - 1) % chronicle.length], style: _note, textAlign: TextAlign.center),
        const SizedBox(height: 6),
        Text('Киту теперь ${yearsLabel(s.years)}.', style: _body, textAlign: TextAlign.center),
      ],
    ),
    actions: [_button('ПРОСНУТЬСЯ', J.purpleButton, () => Navigator.pop(context))],
  );
}

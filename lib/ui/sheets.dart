import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import '../core/game_state.dart';
import '../core/story.dart';
import '../scene/whale_scene.dart';
import 'hud.dart';
import 'intro_screen.dart';
import 'juicy.dart';

/// Окно-панель снизу с лентой-заголовком и красной кнопкой закрытия.
Future<void> showJuicySheet(
  BuildContext context, {
  required String title,
  required WidgetBuilder builder,
  ButtonColors ribbon = J.goldButton,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x66102040),
    builder: (ctx) => FractionallySizedBox(
      heightFactor: 0.8,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned.fill(
            top: 30,
            child: JuicyPanel(radius: 30, padding: const EdgeInsets.fromLTRB(12, 44, 12, 0), child: builder(ctx)),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Center(child: Ribbon(title, colors: ribbon, size: 28)),
          ),
          Positioned(
            top: 18,
            right: 10,
            child: ChunkyCircle(
              size: 38,
              colors: J.redButton,
              depth: 5,
              onPressed: () => Navigator.pop(ctx),
              child: const OutlinedText('×', size: 26, strokeWidth: 3),
            ),
          ),
        ],
      ),
    ),
  );
}

Widget _priceLabel(String text, {Widget? icon, Color color = J.white}) => Row(
  mainAxisSize: MainAxisSize.min,
  children: [
    icon ?? const CoinNote(size: 20),
    const SizedBox(width: 4),
    OutlinedText(text, size: 18, color: color, strokeWidth: 3, shadow: false),
  ],
);

TextStyle _title([Color c = J.navy]) => TextStyle(fontWeight: FontWeight.w900, fontSize: 19, color: c, height: 1.1);
TextStyle _caption([Color c = J.muted]) => TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: c, height: 1.2);

// ---------------------------------------------------------------------------
// Сад: растения и птицы

class ShopSheet extends StatelessWidget {
  const ShopSheet({super.key, required this.controller, required this.atlas});

  final GameController controller;
  final SpriteAtlas atlas;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        return Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: Text('Сад: +${formatRate(eco.productionPerSecond(s, controller.nowMs))}', style: _title()),
                ),
                for (final (mode, label) in const [(BuyMode.one, '×1'), (BuyMode.ten, '×10'), (BuyMode.max, 'МАКС')])
                  Padding(
                    padding: const EdgeInsets.only(left: 6),
                    child: ChunkyButton(
                      colors: controller.buyMode == mode ? J.goldButton : J.whiteButton,
                      depth: 5,
                      radius: 14,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                      gloss: false,
                      onPressed: () => controller.setBuyMode(mode),
                      child: controller.buyMode == mode
                          ? OutlinedText(label, size: 16, strokeWidth: 3, shadow: false)
                          : Text(label, style: _title().copyWith(fontSize: 16)),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.only(bottom: 24),
                children: [
                  _BirdCard(controller: controller, atlas: atlas),
                  for (final g in s.content.generators) ...[
                    const SizedBox(height: 10),
                    _GeneratorCard(controller: controller, atlas: atlas, id: g.id),
                  ],
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _GeneratorCard extends StatelessWidget {
  const _GeneratorCard({required this.controller, required this.atlas, required this.id});

  final GameController controller;
  final SpriteAtlas atlas;
  final String id;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final g = s.content.generatorById(id);
    final icon = atlas.icons[id];
    if (!eco.isUnlocked(s, id)) {
      final open = g.region < eco.openRegions(s);
      final reason = open
          ? 'Откроется в «${s.content.regions[g.region].name}»'
          : 'Нужен возраст: ${_ageForRegion(s, g.region)}';
      return JuicyCard(
        dark: true,
        child: Row(
          children: [
            IconTile(
              dark: true,
              child: icon == null
                  ? const SizedBox()
                  : ColorFiltered(
                      colorFilter: const ColorFilter.mode(Color(0xDD1B2142), BlendMode.srcIn),
                      child: Image.asset(icon),
                    ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(g.name, style: _title(const Color(0xFFDCE4FF))),
                  const SizedBox(height: 4),
                  Text(reason, style: _caption(const Color(0xFFFFD66E))),
                ],
              ),
            ),
          ],
        ),
      );
    }
    final owned = s.owned(id);
    final n = controller.nowMs;
    final perUnit = eco.productionPerUnit(s, id, n);
    final total = perUnit.mulNum(owned.toDouble());
    final milestones = s.content.ownedMilestones;
    final next = milestones.firstWhere((m) => m > owned, orElse: () => 0);
    final prev = milestones.lastWhere((m) => m <= owned, orElse: () => 0);
    final price = controller.generatorPrice(id);
    final amount = controller.generatorAmount(id);
    final can = s.echo >= price;
    return JuicyCard(
      child: Row(
        children: [
          Stack(
            clipBehavior: Clip.none,
            children: [
              IconTile(child: icon == null ? const SizedBox() : Image.asset(icon)),
              Positioned(
                left: 4,
                bottom: 4,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                  decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(10)),
                  child: Text(
                    'ур. $owned',
                    style: const TextStyle(color: J.white, fontSize: 11, fontWeight: FontWeight.w900),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(g.name, style: _title()),
                const SizedBox(height: 2),
                Row(
                  children: [
                    const NoteGlyph(size: 15, color: J.green),
                    const SizedBox(width: 3),
                    Flexible(
                      child: Text(
                        owned == 0 ? '+${formatRate(perUnit)} за штуку' : '+${formatRate(total)}',
                        style: const TextStyle(color: J.green, fontWeight: FontWeight.w900, fontSize: 15),
                      ),
                    ),
                  ],
                ),
                if (next > 0) ...[
                  const SizedBox(height: 5),
                  Row(
                    children: [
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: LinearProgressIndicator(
                            value: (owned - prev) / (next - prev),
                            minHeight: 9,
                            backgroundColor: const Color(0xFFD6E2F6),
                            color: const Color(0xFFFFB028),
                          ),
                        ),
                      ),
                      const SizedBox(width: 6),
                      Text('$owned/$next до ×2', style: _caption()),
                    ],
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          ChunkyButton(
            colors: J.greenButton,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            onPressed: can ? () => controller.buyGenerator(id) : null,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                OutlinedText(amount > 1 ? 'КУПИТЬ ×$amount' : 'КУПИТЬ', size: 13, strokeWidth: 2.5, shadow: false),
                const SizedBox(height: 2),
                _priceLabel(formatBig(price), color: can ? J.white : const Color(0xFFFF8C8C)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static String _ageForRegion(GameState s, int region) {
    for (final a in s.content.ages) {
      if (a.regionsOpen > region) return a.name.toLowerCase();
    }
    return s.content.ages.last.name.toLowerCase();
  }
}

class _BirdCard extends StatelessWidget {
  const _BirdCard({required this.controller, required this.atlas});

  final GameController controller;
  final SpriteAtlas atlas;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final price = controller.birdPrice();
    final can = s.echo >= price;
    final icon = atlas.icons['bird'];
    final perBird = eco.tapValue(s, controller.nowMs).mulNum(s.content.bird.notesPerSecond);
    return JuicyCard(
      child: Row(
        children: [
          IconTile(child: icon == null ? const SizedBox() : Image.asset(icon)),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${s.content.bird.name} · ${s.birds}', style: _title()),
                const SizedBox(height: 2),
                Text('Поёт сама: +${formatRate(perBird)} за птицу', style: _caption(J.green)),
              ],
            ),
          ),
          ChunkyButton(
            colors: J.greenButton,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            onPressed: can ? () => controller.buyBird() : null,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                OutlinedText(
                  controller.birdAmount() > 1 ? 'КУПИТЬ ×${controller.birdAmount()}' : 'КУПИТЬ',
                  size: 13,
                  strokeWidth: 2.5,
                  shadow: false,
                ),
                const SizedBox(height: 2),
                _priceLabel(formatBig(price), color: can ? J.white : const Color(0xFFFF8C8C)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Кит: возраст, испытание, черты, стая

class WhaleSheet extends StatelessWidget {
  const WhaleSheet({super.key, required this.controller, required this.atlas, required this.onTrial});

  final GameController controller;
  final SpriteAtlas atlas;
  final VoidCallback onTrial;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final age = eco.currentAge(s);
        final next = eco.nextAge(s);
        final prevYears = age.years;
        return ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            Row(
              children: [
                IconTile(
                  size: 104,
                  child: WhalePortrait(atlas: atlas, state: s, age: s.age, size: 92),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(age.name, style: _title().copyWith(fontSize: 24)),
                      Text('Киту ${yearsLabel(s.years)}', style: _caption()),
                      const SizedBox(height: 8),
                      if (next != null)
                        ChunkyBar(
                          value: (s.years - prevYears) / (next.years - prevYears),
                          height: 24,
                          colors: const [Color(0xFFCD8CFF), Color(0xFF8046EB)],
                          label: s.years >= next.years ? 'Готов к испытанию!' : 'до «${next.name}»',
                        )
                      else
                        Text('Высший возраст. Небо помнит его песню.', style: _caption(J.green)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (next != null) _TrialCard(controller: controller, atlas: atlas, onTrial: onTrial),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(child: Text('Черты кита', style: _title())),
                const ScaleIcon(size: 22),
                const SizedBox(width: 4),
                Text('${s.scales}', style: _title()),
              ],
            ),
            Text('Чешуйки дают боссы. Черты не сбрасываются Долгой песнью.', style: _caption()),
            const SizedBox(height: 8),
            for (final t in s.content.traits) ...[
              _TraitCard(controller: controller, id: t.id),
              const SizedBox(height: 8),
            ],
            const SizedBox(height: 6),
            Text('Стая', style: _title()),
            const SizedBox(height: 6),
            for (final w in s.content.whales) ...[
              JuicyCard(
                dark: !s.whales.contains(w.id),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.whales.contains(w.id) ? '${w.name} · ${w.description}' : '${w.name} спит',
                      style: _title(s.whales.contains(w.id) ? J.navy : const Color(0xFFDCE4FF)).copyWith(fontSize: 16),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      s.whales.contains(w.id) ? (whaleMemory[w.id] ?? '') : _wakeHint(s, w.id),
                      style: _caption(s.whales.contains(w.id) ? J.muted : const Color(0xFFFFD66E)),
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

  static String _wakeHint(GameState s, String whaleId) {
    for (final a in s.content.ages) {
      if (a.whale == whaleId) return 'Проснётся, когда наш кит достигнет возраста «${a.name}».';
    }
    return 'Спит.';
  }
}

class _TrialCard extends StatelessWidget {
  const _TrialCard({required this.controller, required this.atlas, required this.onTrial});

  final GameController controller;
  final SpriteAtlas atlas;
  final VoidCallback onTrial;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final next = eco.nextAge(s)!;
    final unlocked = eco.trialUnlocked(s);
    final cooldown = controller.fightCooldownLeft;
    final preview = unlocked ? eco.startTrialFight(s, controller.nowMs) : null;
    final power = preview?.powerRatio() ?? 0;
    return JuicyCard(
      dark: !unlocked,
      child: Row(
        children: [
          IconTile(
            dark: true,
            child: WhalePortrait(atlas: atlas, state: s, age: s.age, size: 62, dark: true),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Испытание: Тень кита', style: _title(unlocked ? J.navy : const Color(0xFFDCE4FF))),
                const SizedBox(height: 2),
                Text(
                  unlocked
                      ? 'Победа откроет возраст «${next.name}». Сила песни: ${(power * 100).round()}%'
                      : 'Откроется, когда киту будет ${yearsLabel(next.years)}.',
                  style: _caption(unlocked ? (power >= 1 ? J.green : J.red) : const Color(0xFFFFD66E)),
                ),
              ],
            ),
          ),
          if (unlocked)
            ChunkyButton(
              colors: J.purpleButton,
              onPressed: cooldown > 0 ? null : onTrial,
              child: OutlinedText(cooldown > 0 ? '${cooldown.ceil()} с' : 'БОЙ', size: 18, strokeWidth: 3),
            ),
        ],
      ),
    );
  }
}

class _TraitCard extends StatelessWidget {
  const _TraitCard({required this.controller, required this.id});

  final GameController controller;
  final String id;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final t = s.content.traitById(id);
    final level = s.traitLevel(id);
    final cost = eco.traitCost(s, id);
    final can = cost != null && s.scales >= cost;
    return JuicyCard(
      child: Row(
        children: [
          TraitGlyph(id: id, size: 52),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${t.name} · ур. $level', style: _title().copyWith(fontSize: 17)),
                Text(t.description, style: _caption()),
              ],
            ),
          ),
          ChunkyButton(
            colors: J.greenButton,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            onPressed: can ? () => controller.buyTrait(id) : null,
            child: cost == null
                ? const OutlinedText('МАКС', size: 15, strokeWidth: 3, shadow: false)
                : _priceLabel('$cost', icon: const ScaleIcon(size: 18), color: can ? J.white : const Color(0xFFFF8C8C)),
          ),
        ],
      ),
    );
  }
}

/// Значки черт: нота (Голос), сердце, плавник, звезда (Дух).
class TraitGlyph extends StatelessWidget {
  const TraitGlyph({super.key, required this.id, this.size = 48});

  final String id;
  final double size;

  static const _colors = {
    'voice': J.coralButton,
    'heart': J.redButton,
    'fins': ButtonColors(Color(0xFF8CD2FF), Color(0xFF3C8CFF), Color(0xFF285AB4)),
    'spirit': J.goldButton,
  };

  @override
  Widget build(BuildContext context) {
    final c = _colors[id] ?? J.goldButton;
    return Container(
      width: size,
      height: size,
      decoration: const BoxDecoration(color: J.navy, shape: BoxShape.circle),
      padding: const EdgeInsets.all(3),
      child: Container(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [c.top, c.bottom]),
        ),
        alignment: Alignment.center,
        child: id == 'voice'
            ? NoteGlyph(size: size * 0.55, shadow: c.depth)
            : CustomPaint(size: Size.square(size * 0.55), painter: _GlyphPainter(id, c.depth)),
      ),
    );
  }
}

class _GlyphPainter extends CustomPainter {
  _GlyphPainter(this.id, this.shadow);
  final String id;
  final Color shadow;

  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width;
    final path = Path();
    switch (id) {
      case 'heart':
        path
          ..moveTo(w * 0.5, w * 0.9)
          ..cubicTo(w * -0.1, w * 0.45, w * 0.15, w * 0.0, w * 0.5, w * 0.28)
          ..cubicTo(w * 0.85, w * 0.0, w * 1.1, w * 0.45, w * 0.5, w * 0.9)
          ..close();
      case 'fins':
        path
          ..moveTo(w * 0.15, w * 0.85)
          ..quadraticBezierTo(w * 0.2, w * 0.2, w * 0.9, w * 0.1)
          ..quadraticBezierTo(w * 0.6, w * 0.5, w * 0.75, w * 0.85)
          ..close();
      default:
        for (var i = 0; i < 10; i++) {
          final a = -1.5708 + i * 0.6283;
          final r = i.isEven ? w * 0.5 : w * 0.22;
          final p = Offset(w * 0.5 + r * math.cos(a), w * 0.52 + r * math.sin(a));
          i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
        }
        path.close();
    }
    canvas.drawPath(path.shift(Offset(w * 0.03, w * 0.06)), Paint()..color = shadow);
    canvas.drawPath(path, Paint()..color = J.white);
  }

  @override
  bool shouldRepaint(_GlyphPainter old) => old.id != id;
}

// ---------------------------------------------------------------------------
// Путь: регионы и стражи, перелёт, Долгая песнь, Дерево памяти

class PathSheet extends StatelessWidget {
  const PathSheet({super.key, required this.controller, required this.atlas, required this.onFight});

  final GameController controller;
  final SpriteAtlas atlas;
  final VoidCallback onFight;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final c = s.content;
        return ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            for (var r = 0; r < c.regions.length; r++) ...[
              _RegionCard(controller: controller, atlas: atlas, region: r, onFight: onFight),
              const SizedBox(height: 8),
            ],
            if (eco.canMigrate(s))
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: ChunkyButton(
                  expand: true,
                  colors: J.greenButton,
                  onPressed: () => controller.migrate(),
                  child: OutlinedText('Лететь дальше: ${c.regions[s.region + 1].name}', size: 18, strokeWidth: 3),
                ),
              ),
            _SongCard(controller: controller),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(child: Text('Дерево памяти', style: _title())),
                Text('Семена: ${formatBig(s.seeds)}', style: _title()),
              ],
            ),
            const SizedBox(height: 6),
            for (final node in c.memory) ...[
              _MemoryCard(controller: controller, id: node.id),
              const SizedBox(height: 8),
            ],
          ],
        );
      },
    );
  }
}

class _RegionCard extends StatelessWidget {
  const _RegionCard({required this.controller, required this.atlas, required this.region, required this.onFight});

  final GameController controller;
  final SpriteAtlas atlas;
  final int region;
  final VoidCallback onFight;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final def = s.content.regions[region];
    final boss = eco.guardianOf(s, region);
    final open = region < eco.openRegions(s);
    final current = region == s.region;
    final passed = region < s.region;
    final img = atlas.bosses[boss.id];
    String status;
    Color statusColor = J.muted;
    Widget? action;
    if (!open) {
      status = 'Киту пока не долететь: нужен возраст «${_ageFor(s, region)}»';
      statusColor = const Color(0xFFFFD66E);
    } else if (passed) {
      status = 'Пройден. ${boss.name} побеждён.';
      statusColor = J.green;
    } else if (current) {
      if (eco.guardianBeaten(s)) {
        status = '${boss.name} побеждён.';
        statusColor = J.green;
      } else if (eco.guardianReady(s)) {
        final f = eco.startGuardianFight(s, controller.nowMs);
        final power = f?.powerRatio() ?? 0;
        status = '${boss.title}. Сила песни: ${(power * 100).round()}%';
        statusColor = power >= 1 ? J.green : J.red;
        final cd = controller.fightCooldownLeft;
        action = ChunkyButton(
          colors: J.redButton,
          onPressed: cd > 0 ? null : onFight,
          child: OutlinedText(cd > 0 ? '${cd.ceil()} с' : 'БОЙ', size: 18, strokeWidth: 3),
        );
      } else {
        status = 'Страж появится, когда небо станет цветным: ${(eco.regionProgress(s) * 100).floor()}%';
      }
    } else {
      status = 'Впереди. Страж: ${boss.name}.';
    }
    return JuicyCard(
      dark: !open,
      child: Row(
        children: [
          IconTile(
            dark: !open,
            child: img == null
                ? const SizedBox()
                : (open ? SpriteImage(img, size: 64) : Opacity(opacity: 0.35, child: SpriteImage(img, size: 64))),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(def.name, style: _title(open ? J.navy : const Color(0xFFDCE4FF))),
                const SizedBox(height: 2),
                Text(status, style: _caption(statusColor)),
              ],
            ),
          ),
          ?action,
        ],
      ),
    );
  }

  static String _ageFor(GameState s, int region) {
    for (final a in s.content.ages) {
      if (a.regionsOpen > region) return a.name;
    }
    return s.content.ages.last.name;
  }
}

class _SongCard extends StatelessWidget {
  const _SongCard({required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final can = eco.canSing(s);
    final years = eco.songYears(s, controller.nowMs);
    return JuicyCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Долгая песнь', style: _title()),
          const SizedBox(height: 2),
          Text(
            can
                ? 'Кит заснёт на ${years >= 99.5 ? 'век' : yearsLabel(years)}: сад отцветёт и оставит '
                      '${formatBig(eco.seedsForSong(s))} семян, на спине вырастет новое поколение.'
                : 'Откроется в конце пути, когда побеждён страж последнего доступного региона. Спето песен: ${s.songs}.',
            style: _caption(),
          ),
          if (can) ...[
            const SizedBox(height: 8),
            ChunkyButton(
              expand: true,
              colors: J.purpleButton,
              onPressed: () {
                Navigator.of(context).maybePop();
                controller.sing();
              },
              child: OutlinedText('СПЕТЬ · +${formatBig(eco.seedsForSong(s))} семян', size: 18, strokeWidth: 3),
            ),
          ],
        ],
      ),
    );
  }
}

class _MemoryCard extends StatelessWidget {
  const _MemoryCard({required this.controller, required this.id});

  final GameController controller;
  final String id;

  @override
  Widget build(BuildContext context) {
    final s = controller.state;
    final node = s.content.memoryById(id);
    final cost = eco.memoryCost(s, id);
    final can = cost != null && s.seeds >= cost;
    return JuicyCard(
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${node.name} · ${s.memoryLevel(id)}/${node.maxLevel}', style: _title().copyWith(fontSize: 16)),
                Text(node.description, style: _caption()),
              ],
            ),
          ),
          ChunkyButton(
            colors: J.greenButton,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            onPressed: can ? () => controller.buyMemory(id) : null,
            child: cost == null
                ? const OutlinedText('МАКС', size: 15, strokeWidth: 3, shadow: false)
                : OutlinedText(
                    '${formatBig(cost)} сем.',
                    size: 15,
                    strokeWidth: 3,
                    shadow: false,
                    color: can ? J.white : const Color(0xFFFF8C8C),
                  ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Ещё: статистика, сохранение, проверка, лицензии

class MoreSheet extends StatelessWidget {
  const MoreSheet({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        Widget stat(String l, String v) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(
            children: [
              Expanded(child: Text(l, style: _caption())),
              Text(v, style: _title().copyWith(fontSize: 15)),
            ],
          ),
        );
        return ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            JuicyCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Статистика', style: _title()),
                  stat('Эха за всё время', formatBig(s.totalEarned)),
                  stat('Тапов', '${s.taps}'),
                  stat('Время в игре', formatDuration(s.playSeconds)),
                  stat('Спето песен', '${s.songs}'),
                  stat('Возраст кита', yearsLabel(s.years)),
                  stat('Побед над боссами', '${s.bossWins}'),
                ],
              ),
            ),
            const SizedBox(height: 10),
            JuicyCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Сохранение', style: _title()),
                  Text(
                    'Игра сохраняется сама. Код прогресса — запасная копия для другого устройства.',
                    style: _caption(),
                  ),
                  const SizedBox(height: 8),
                  ChunkyButton(
                    colors: J.whiteButton,
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: controller.exportProgress()));
                      if (context.mounted) {
                        ScaffoldMessenger.of(context)
                            .showSnackBar(const SnackBar(content: Text('Код прогресса скопирован')));
                      }
                    },
                    child: Text('Скопировать код прогресса', style: _title().copyWith(fontSize: 15)),
                  ),
                  const SizedBox(height: 8),
                  ChunkyButton(
                    colors: J.whiteButton,
                    onPressed: () => _import(context),
                    child: Text('Загрузить из кода', style: _title().copyWith(fontSize: 15)),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            JuicyCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Для тестирования', style: _title()),
                  Text(
                    'Промотка — будто ты был в игре и не нажимал. «+100 лет» — чтобы увидеть превращения.',
                    style: _caption(),
                  ),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final (label, action) in [
                        ('+10 минут', () => controller.debugSkip(600)),
                        ('+1 час', () => controller.debugSkip(3600)),
                        ('+100 лет', () => controller.debugAddYears(100)),
                        ('Начать заново', () => _reset(context)),
                      ])
                        ChunkyButton(
                          colors: J.whiteButton,
                          depth: 5,
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                          onPressed: action,
                          child: Text(label, style: _title().copyWith(fontSize: 14)),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            JuicyCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Об игре', style: _title()),
                  Text(
                    'Песнь небесного кита, версия 0.3. Вся 3D-графика и ролик построены нашим кодом в Blender. '
                    'Реклама пока заглушка.',
                    style: _caption(),
                  ),
                  TextButton(
                    key: const ValueKey('more-intro'),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(builder: (route) => IntroScreen(onDone: () => Navigator.of(route).pop())),
                    ),
                    child: const Text('Смотреть предысторию'),
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

  Future<void> _import(BuildContext context) async {
    final field = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
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

  Future<void> _reset(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Начать заново?'),
        content: const Text('Весь прогресс, возраст кита и проснувшиеся киты будут удалены.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Начать заново')),
        ],
      ),
    );
    if (ok == true) await controller.resetProgress();
  }
}

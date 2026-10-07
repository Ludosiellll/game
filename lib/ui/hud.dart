import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../core/economy.dart' as eco;
import '../core/format.dart';
import '../core/game_state.dart';
import '../scene/whale_scene.dart';
import 'juicy.dart';

/// «1 год», «2 года», «5 лет».
String yearsLabel(num years) {
  final n = years.floor();
  final m10 = n % 10;
  final m100 = n % 100;
  final word = (m10 == 1 && m100 != 11)
      ? 'год'
      : (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14))
      ? 'года'
      : 'лет';
  return '$n $word';
}

/// Картинка спрайта целиком (например, кит для кнопки).
class SpriteImage extends StatelessWidget {
  const SpriteImage(this.image, {super.key, this.size = 48, this.mirror = false});

  final ui.Image image;
  final double size;
  final bool mirror;

  @override
  Widget build(BuildContext context) {
    final img = RawImage(
      image: image,
      width: size,
      height: size,
      fit: BoxFit.contain,
      filterQuality: FilterQuality.medium,
    );
    return mirror ? Transform.flip(flipX: true, child: img) : img;
  }
}

/// Верх экрана: монета, крупное число Эха, доход в секунду, меню.
class TopHud extends StatelessWidget {
  const TopHud({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final n = controller.nowMs;
        final boosted = eco.isBoosted(s, n);
        return Column(
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [const CoinNote(size: 42), const SizedBox(width: 8), OutlinedText(formatBig(s.echo), size: 46)],
            ),
            const SizedBox(height: 4),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
              decoration: BoxDecoration(color: const Color(0x99142050), borderRadius: BorderRadius.circular(20)),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  OutlinedText(
                    '+${formatRate(eco.incomePerSecond(s, n))}',
                    size: 18,
                    color: const Color(0xFF8CFF78),
                    strokeWidth: 3,
                    shadow: false,
                  ),
                  if (boosted) ...[
                    const SizedBox(width: 8),
                    const OutlinedText('×2', size: 18, color: Color(0xFFFFE25C), strokeWidth: 3, shadow: false),
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

/// Регион, «цвет неба» и возраст кита.
class RegionBar extends StatelessWidget {
  const RegionBar({super.key, required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final region = s.content.regions[s.region];
        final p = eco.regionProgress(s);
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  OutlinedText(region.name, size: 20, align: TextAlign.left),
                  const Spacer(),
                  OutlinedText('${s.region + 1} / ${eco.openRegions(s)}', size: 17),
                ],
              ),
              const SizedBox(height: 4),
              ChunkyBar(value: p, label: 'Цвет неба ${(p * 100).floor()}%', height: 28),
              const SizedBox(height: 6),
              Align(
                alignment: Alignment.centerLeft,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
                  decoration: BoxDecoration(color: const Color(0x99142050), borderRadius: BorderRadius.circular(16)),
                  child: OutlinedText(
                    '${eco.currentAge(s).name} · ${yearsLabel(s.years)}',
                    size: 15,
                    strokeWidth: 2.5,
                    shadow: false,
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Боковые кнопки: слева ускорение за рекламу, справа — главное событие.
class SideButtons extends StatelessWidget {
  const SideButtons({
    super.key,
    required this.controller,
    required this.atlas,
    required this.onFight,
    required this.onWhale,
    required this.onPath,
  });

  final GameController controller;
  final SpriteAtlas atlas;
  final VoidCallback onFight;
  final VoidCallback onWhale;
  final VoidCallback onPath;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        final n = controller.nowMs;
        final boostLeft = math.max(0, s.boostUntilMs - n) ~/ 1000;
        return Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              _Labeled(
                label: boostLeft > 0 ? '${boostLeft ~/ 60}:${(boostLeft % 60).toString().padLeft(2, '0')}' : 'реклама',
                child: ChunkyCircle(
                  size: 58,
                  colors: J.tealButton,
                  onPressed: () => controller.watchBoostAd(),
                  child: const OutlinedText('×2', size: 26),
                ),
              ),
              const Spacer(),
              _event(s),
            ],
          ),
        );
      },
    );
  }

  Widget _event(GameState s) {
    if (eco.guardianReady(s)) {
      final boss = atlas.bosses[eco.guardianOf(s, s.region).id];
      return _Labeled(
        label: 'БОЙ!',
        child: _Pulse(
          child: ChunkyCircle(
            key: const ValueKey('event-fight'),
            size: 66,
            colors: J.redButton,
            glow: const Color(0x99FF6070),
            badge: true,
            onPressed: onFight,
            child: boss == null ? const OutlinedText('!', size: 30) : SpriteImage(boss, size: 56),
          ),
        ),
      );
    }
    if (eco.trialUnlocked(s)) {
      return _Labeled(
        label: 'ИСПЫТАНИЕ',
        child: _Pulse(
          child: ChunkyCircle(
            size: 62,
            colors: J.purpleButton,
            glow: const Color(0x99C080FF),
            onPressed: onWhale,
            child: WhalePortrait(atlas: atlas, state: s, age: s.age, size: 52, dark: true),
          ),
        ),
      );
    }
    if (eco.canMigrate(s)) {
      return _Labeled(
        label: 'В ПУТЬ',
        child: _Pulse(
          child: ChunkyCircle(
            size: 62,
            colors: J.greenButton,
            onPressed: () => controller.migrate(),
            child: const OutlinedText('»', size: 40), // «➜» нет в Nunito
          ),
        ),
      );
    }
    if (eco.canSing(s)) {
      return _Labeled(
        label: 'ПЕСНЬ',
        child: _Pulse(
          child: ChunkyCircle(
            size: 62,
            colors: J.purpleButton,
            glow: const Color(0x99C080FF),
            onPressed: onPath,
            child: const NoteGlyph(size: 34, shadow: Color(0xFF462896)),
          ),
        ),
      );
    }
    return const SizedBox(width: 66);
  }
}

class _Labeled extends StatelessWidget {
  const _Labeled({required this.label, required this.child});

  final String label;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        child,
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
          decoration: BoxDecoration(color: J.navy, borderRadius: BorderRadius.circular(12)),
          child: Text(
            label,
            style: const TextStyle(color: J.white, fontWeight: FontWeight.w900, fontSize: 12),
          ),
        ),
      ],
    );
  }
}

/// Мягкая пульсация — «нажми меня».
class _Pulse extends StatefulWidget {
  const _Pulse({required this.child});

  final Widget child;

  @override
  State<_Pulse> createState() => _PulseState();
}

class _PulseState extends State<_Pulse> with SingleTickerProviderStateMixin {
  late final AnimationController _a = AnimationController(vsync: this, duration: const Duration(milliseconds: 900))
    ..repeat(reverse: true);

  @override
  void dispose() {
    _a.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ScaleTransition(
      scale: Tween(begin: 0.94, end: 1.06).animate(CurvedAnimation(parent: _a, curve: Curves.easeInOut)),
      child: widget.child,
    );
  }
}

/// Нижняя панель: Сад, Кит, большая нота, Путь, Ещё.
class BottomBar extends StatelessWidget {
  const BottomBar({
    super.key,
    required this.controller,
    required this.atlas,
    required this.onShop,
    required this.onWhale,
    required this.onPath,
    required this.onMore,
    required this.singAt,
  });

  final GameController controller;
  final SpriteAtlas atlas;
  final VoidCallback onShop;
  final VoidCallback onWhale;
  final VoidCallback onPath;
  final VoidCallback onMore;

  /// Где на сцене звучит нота от большой кнопки (у морды кита).
  final Offset Function() singAt;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final s = controller.state;
        // Пять кнопок занимают 376 px: на узком экране панель ужимается целиком.
        return LayoutBuilder(
          builder: (context, box) => FittedBox(
            fit: BoxFit.scaleDown,
            child: SizedBox(
              width: math.max(box.maxWidth, 384),
              height: 128,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  _NavButton(
                    label: 'Сад',
                    badge: _canBuyAnything(s),
                    onTap: onShop,
                    child: _icon(atlas.icons['flower'], 50),
                  ),
                  _NavButton(
                    label: 'Кит',
                    badge: eco.trialUnlocked(s) || _canBuyTrait(s),
                    onTap: onWhale,
                    child: WhalePortrait(atlas: atlas, state: s, age: s.age, size: 50),
                  ),
                  _SingButton(controller: controller, singAt: singAt),
                  _NavButton(
                    label: 'Путь',
                    badge: eco.guardianReady(s) || eco.canMigrate(s) || eco.canSing(s),
                    onTap: onPath,
                    child: _icon(atlas.icons['route'], 50),
                  ),
                  _NavButton(label: 'Ещё', onTap: onMore, child: const _MoreGlyph()),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  static Widget _icon(String? path, double size) =>
      path == null ? SizedBox(width: size, height: size) : Image.asset(path, width: size, height: size);

  bool _canBuyAnything(GameState s) {
    for (final g in s.content.generators) {
      if (eco.isUnlocked(s, g.id) && eco.costOf(s, g.id, 1) <= s.echo) return true;
    }
    return eco.birdCost(s, 1) <= s.echo;
  }

  bool _canBuyTrait(GameState s) {
    for (final t in s.content.traits) {
      final c = eco.traitCost(s, t.id);
      if (c != null && c <= s.scales) return true;
    }
    return false;
  }
}

/// Три точки «ещё» в стиле наклеек: белые с тёмной обводкой.
class _MoreGlyph extends StatelessWidget {
  const _MoreGlyph();

  @override
  Widget build(BuildContext context) {
    Widget dot() => Container(
      width: 13,
      height: 13,
      margin: const EdgeInsets.symmetric(horizontal: 2),
      decoration: BoxDecoration(
        color: J.white,
        shape: BoxShape.circle,
        border: Border.all(color: J.navy, width: 3),
      ),
    );
    return Row(mainAxisSize: MainAxisSize.min, children: [dot(), dot(), dot()]);
  }
}

class _NavButton extends StatelessWidget {
  const _NavButton({required this.label, required this.onTap, required this.child, this.badge = false});

  final String label;
  final VoidCallback onTap;
  final Widget child;
  final bool badge;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ChunkyCircle(key: ValueKey('nav-$label'), size: 58, badge: badge, onPressed: onTap, child: child),
        OutlinedText(label, size: 15, strokeWidth: 3),
        const SizedBox(height: 6),
      ],
    );
  }
}

/// Большая кнопка «нота»: тап — нота, удержание — непрерывное пение.
class _SingButton extends StatefulWidget {
  const _SingButton({required this.controller, required this.singAt});

  final GameController controller;
  final Offset Function() singAt;

  @override
  State<_SingButton> createState() => _SingButtonState();
}

class _SingButtonState extends State<_SingButton> {
  Timer? _hold;
  final math.Random _rng = math.Random();

  void _sing() {
    final p = widget.singAt();
    widget.controller.tapAt(p.dx + (_rng.nextDouble() - 0.5) * 60, p.dy + (_rng.nextDouble() - 0.5) * 30);
  }

  void _stop() {
    _hold?.cancel();
    _hold = null;
  }

  @override
  void dispose() {
    _stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) {
        _sing();
        _hold = Timer.periodic(const Duration(milliseconds: 170), (_) => _sing());
      },
      onPointerUp: (_) => _stop(),
      onPointerCancel: (_) => _stop(),
      child: Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: ChunkyCircle(
          size: 104,
          depth: 10,
          colors: J.coralButton,
          glow: const Color(0x88FFD278),
          onPressed: () {},
          child: const NoteGlyph(size: 58, shadow: Color(0xFF961E3C)),
        ),
      ),
    );
  }
}

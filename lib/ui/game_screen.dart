import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../art/sprite_atlas.dart';
import '../scene/whale_scene.dart';
import 'dialogs.dart';
import 'fight_screen.dart';
import 'hud.dart';
import 'juicy.dart';
import 'sheets.dart';

/// Главный экран: сцена с китом на весь экран, сочный интерфейс поверх.
class GameScreen extends StatefulWidget {
  const GameScreen({super.key, required this.controller, required this.atlas});

  final GameController controller;
  final SpriteAtlas atlas;

  /// Где по высоте экрана центр кита.
  static const double whaleCenterY = 0.53;

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> {
  late final AppLifecycleListener _lifecycle;
  StreamSubscription<GameEvent>? _events;
  bool _dialogOpen = false;
  bool _inFight = false;
  FightEndEvent? _fightEnd;

  GameController get c => widget.controller;
  SpriteAtlas get atlas => widget.atlas;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(
      onHide: () => c.onPause(),
      onResume: () {
        c.onResume();
        _showOfflineIfAny();
      },
    );
    _events = c.events.listen(_onEvent);
    WidgetsBinding.instance.addPostFrameCallback((_) => _showOfflineIfAny());
  }

  @override
  void dispose() {
    _events?.cancel();
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _showOfflineIfAny() async {
    if (_dialogOpen || _inFight || c.pendingOffline == null || !mounted) return;
    _dialogOpen = true;
    await showOfflineDialog(context, c);
    _dialogOpen = false;
  }

  Future<void> _onEvent(GameEvent e) async {
    if (!mounted) return;
    switch (e) {
      case MigrationEvent(:final region):
        await showMigrationDialog(context, c, region);
      case SongEvent():
        await showSongDialog(context, c, e);
      case FightEndEvent():
        // Итог показываем, когда экран боя закроется (иначе его закрытие сняло бы окно).
        if (e.won) _fightEnd = e;
    }
  }

  Future<void> _fight({required bool trial}) async {
    if (_inFight) return;
    final started = trial ? c.startTrialFight() : c.startGuardianFight();
    if (!started) return;
    _inFight = true;
    final nav = Navigator.of(context);
    while (nav.canPop()) {
      nav.pop();
    }
    await nav.push(
      PageRouteBuilder<void>(
        opaque: true,
        transitionDuration: const Duration(milliseconds: 350),
        pageBuilder: (_, _, _) => FightScreen(controller: c, atlas: atlas),
        transitionsBuilder: (_, anim, _, child) => FadeTransition(opacity: anim, child: child),
      ),
    );
    _inFight = false;
    final end = _fightEnd;
    _fightEnd = null;
    if (end == null || !mounted) return;
    final reward = end.reward!;
    if (reward.newAge != null) {
      await showAgeUpDialog(context, atlas, c, reward.newAge!, reward.woke);
    } else {
      await showBossWinDialog(context, atlas, end);
    }
  }

  void _shop() => showJuicySheet(
    context,
    title: 'САД',
    builder: (_) => ShopSheet(controller: c, atlas: atlas),
  );

  void _whale() => showJuicySheet(
    context,
    title: 'КИТ',
    ribbon: J.purpleButton,
    builder: (_) => WhaleSheet(controller: c, atlas: atlas, onTrial: () => _fight(trial: true)),
  );

  void _path() => showJuicySheet(
    context,
    title: 'ПУТЬ',
    ribbon: J.greenButton,
    builder: (_) => PathSheet(controller: c, atlas: atlas, onFight: () => _fight(trial: false)),
  );

  void _more() => showJuicySheet(
    context,
    title: 'ЕЩЁ',
    ribbon: J.whiteButton,
    builder: (_) => MoreSheet(controller: c),
  );

  /// Морда кита на экране — оттуда звучат ноты большой кнопки.
  Offset _mouth() {
    final size = MediaQuery.sizeOf(context);
    final age = atlas.age(c.state.age);
    final b = age.bounds.inflate(age.frame.width * 0.02);
    final k = math.min(size.width * 0.98 / b.width, size.height * 0.56 / b.height);
    final center = Offset(size.width / 2, size.height * GameScreen.whaleCenterY);
    return center + (age.mouth - b.center) * k + const Offset(0, -30);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF52B4FF),
      body: Stack(
        children: [
          Positioned.fill(
            child: WhaleScene(controller: c, atlas: atlas, centerY: GameScreen.whaleCenterY),
          ),
          SafeArea(
            child: Column(
              children: [
                const SizedBox(height: 4),
                TopHud(controller: c),
                const SizedBox(height: 6),
                RegionBar(controller: c),
                const Spacer(),
                SideButtons(
                  controller: c,
                  atlas: atlas,
                  onFight: () => _fight(trial: false),
                  onWhale: _whale,
                  onPath: _path,
                ),
                BottomBar(
                  controller: c,
                  atlas: atlas,
                  onShop: _shop,
                  onWhale: _whale,
                  onPath: _path,
                  onMore: _more,
                  singAt: _mouth,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

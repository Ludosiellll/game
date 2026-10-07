import 'dart:async';

import 'package:flutter/material.dart';

import '../app/game_controller.dart';
import '../core/story.dart';
import '../scene/scene_view.dart';
import 'dialogs.dart';
import 'hud.dart';
import 'palette.dart';
import 'tabs.dart';

/// Главный экран: сцена с китом сверху, вкладки снизу.
class GameScreen extends StatefulWidget {
  const GameScreen({super.key, required this.controller});

  final GameController controller;

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 3, vsync: this);
  late final AppLifecycleListener _lifecycle;
  StreamSubscription<GameEvent>? _events;
  bool _dialogOpen = false;

  GameController get c => widget.controller;

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
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _showOfflineIfAny() async {
    if (_dialogOpen || c.pendingOffline == null || !mounted) return;
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
        _tabs.animateTo(1);
        await showSongDialog(context, c, e);
      case FightEndEvent():
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Palette.paper,
      body: Column(
        children: [
          Expanded(
            flex: 57,
            child: Stack(
              fit: StackFit.expand,
              children: [
                SceneView(controller: c),
                SafeArea(bottom: false, child: Hud(controller: c)),
                Align(
                  alignment: Alignment.bottomCenter,
                  child: RegionBar(controller: c, onOpenSong: () => _tabs.animateTo(1)),
                ),
                _TapHint(controller: c),
              ],
            ),
          ),
          Expanded(
            flex: 43,
            child: Container(
              decoration: BoxDecoration(
                color: Palette.paper,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
                boxShadow: [BoxShadow(color: const Color(0xFF1B2030).withValues(alpha: 0.18), blurRadius: 16)],
              ),
              child: SafeArea(
                top: false,
                child: Column(
                  children: [
                    TabBar(
                      controller: _tabs,
                      labelColor: Palette.ink,
                      unselectedLabelColor: Palette.inkSoft,
                      indicatorColor: Palette.accent,
                      labelStyle: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15),
                      tabs: const [
                        Tab(text: 'Сад'),
                        Tab(text: 'Песнь'),
                        Tab(text: 'Ещё'),
                      ],
                    ),
                    Expanded(
                      child: TabBarView(
                        controller: _tabs,
                        children: [
                          GardenTab(controller: c),
                          SongTab(controller: c),
                          MoreTab(controller: c),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Подсказка для самого первого тапа.
class _TapHint extends StatelessWidget {
  const _TapHint({required this.controller});

  final GameController controller;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          final visible = controller.state.taps == 0 && controller.state.songs == 0;
          return AnimatedOpacity(
            opacity: visible ? 1 : 0,
            duration: const Duration(milliseconds: 400),
            child: Align(
              alignment: const Alignment(0, 0.42),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                decoration: BoxDecoration(
                  color: Palette.paper.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: const Text(
                  firstHint,
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Palette.ink),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

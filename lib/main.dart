import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app/game_controller.dart';
import 'app/save_store.dart';
import 'art/sprite_atlas.dart';
import 'ui/game_screen.dart';
import 'ui/intro_screen.dart';
import 'ui/juicy.dart';
import 'ui/prototype_ads.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  _registerFontLicenses();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  final navigatorKey = GlobalKey<NavigatorState>();
  final controller = GameController(store: PrefsSaveStore(), ads: PrototypeAdService(navigatorKey));
  runApp(SkywhaleApp(controller: controller, navigatorKey: navigatorKey));
}

class SkywhaleApp extends StatelessWidget {
  const SkywhaleApp({super.key, required this.controller, required this.navigatorKey});

  final GameController controller;
  final GlobalKey<NavigatorState> navigatorKey;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Песнь небесного кита',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      theme: J.theme(),
      home: AppRoot(controller: controller),
    );
  }
}

/// Запуск: загрузочный экран → ролик-предыстория (только в первый раз) → игра.
class AppRoot extends StatefulWidget {
  const AppRoot({super.key, required this.controller});

  final GameController controller;

  @override
  State<AppRoot> createState() => _AppRootState();
}

class _AppRootState extends State<AppRoot> {
  SpriteAtlas? _atlas;
  bool _intro = false;
  double _progress = 0;
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    // Настоящий прогресс загрузки не измерить — полоса плавно идёт к 90%, остальное — по готовности.
    _ticker = Timer.periodic(const Duration(milliseconds: 50), (_) {
      if (mounted) setState(() => _progress += (0.9 - _progress) * 0.08);
    });
    _load();
  }

  Future<void> _load() async {
    final shown = Stopwatch()..start();
    final results = await Future.wait([widget.controller.init(), SpriteAtlas.load()]);
    // Свиток должен успеть показаться, а не мелькнуть.
    final left = 1400 - shown.elapsedMilliseconds;
    if (left > 0) await Future<void>.delayed(Duration(milliseconds: left));
    _ticker?.cancel();
    if (!mounted) return;
    setState(() => _progress = 1);
    await Future<void>.delayed(const Duration(milliseconds: 250));
    if (!mounted) return;
    setState(() {
      _atlas = results[1] as SpriteAtlas;
      _intro = widget.controller.shouldShowIntro;
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final atlas = _atlas;
    final Widget child;
    if (atlas == null) {
      child = LoadingScreen(key: const ValueKey('loading'), progress: _progress);
    } else if (_intro) {
      child = IntroScreen(
        key: const ValueKey('intro'),
        onDone: () {
          widget.controller.markIntroSeen();
          setState(() => _intro = false);
        },
      );
    } else {
      child = GameScreen(key: const ValueKey('game'), controller: widget.controller, atlas: atlas);
    }
    return AnimatedSwitcher(duration: const Duration(milliseconds: 600), child: child);
  }
}

/// Шрифты под SIL OFL 1.1: текст лицензии должен поставляться вместе с ними.
void _registerFontLicenses() {
  LicenseRegistry.addLicense(() async* {
    yield LicenseEntryWithLineBreaks(['Nunito'], await rootBundle.loadString('assets/fonts/OFL-Nunito.txt'));
    yield LicenseEntryWithLineBreaks(['Marck Script'], await rootBundle.loadString('assets/fonts/OFL-MarckScript.txt'));
  });
}

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app/game_controller.dart';
import 'app/save_store.dart';
import 'art/sprite_atlas.dart';
import 'ui/game_screen.dart';
import 'ui/juicy.dart';
import 'ui/prototype_ads.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  _registerFontLicenses();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  final navigatorKey = GlobalKey<NavigatorState>();
  final controller = GameController(store: PrefsSaveStore(), ads: PrototypeAdService(navigatorKey));
  final results = await Future.wait([controller.init(), SpriteAtlas.load()]);
  runApp(SkywhaleApp(controller: controller, atlas: results[1] as SpriteAtlas, navigatorKey: navigatorKey));
}

class SkywhaleApp extends StatelessWidget {
  const SkywhaleApp({super.key, required this.controller, required this.atlas, required this.navigatorKey});

  final GameController controller;
  final SpriteAtlas atlas;
  final GlobalKey<NavigatorState> navigatorKey;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Песнь небесного кита',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      theme: J.theme(),
      home: GameScreen(controller: controller, atlas: atlas),
    );
  }
}

/// Шрифты под SIL OFL 1.1: текст лицензии должен поставляться вместе с ними.
void _registerFontLicenses() {
  LicenseRegistry.addLicense(() async* {
    final text = await rootBundle.loadString('assets/fonts/OFL-Nunito.txt');
    yield LicenseEntryWithLineBreaks(['Nunito'], text);
  });
}

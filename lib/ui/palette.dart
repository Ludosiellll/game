import 'dart:ui';

import 'color_mix.dart';

/// Цвета сцены одного состояния неба. Роли совпадают с палитрами
/// арт-направления «Акварельная дымка» (см. README, раздел «Стиль»).
class ScenePalette {
  const ScenePalette({
    required this.skyTop,
    required this.skyBottom,
    required this.cloud,
    required this.whaleBack,
    required this.whaleRim,
    required this.green,
    required this.bloom,
    required this.glow,
  });

  final Color skyTop;
  final Color skyBottom;
  final Color cloud;
  final Color whaleBack;

  /// Светлое брюхо, видимое каймой по контуру кита, и плавники.
  final Color whaleRim;
  final Color green;
  final Color bloom;

  /// Свечения, волны от нот, отклик на тап.
  final Color glow;

  ScenePalette lerpTo(ScenePalette o, double t) => ScenePalette(
    skyTop: lerpOklab(skyTop, o.skyTop, t),
    skyBottom: lerpOklab(skyBottom, o.skyBottom, t),
    cloud: lerpOklab(cloud, o.cloud, t),
    whaleBack: lerpOklab(whaleBack, o.whaleBack, t),
    whaleRim: lerpOklab(whaleRim, o.whaleRim, t),
    green: lerpOklab(green, o.green, t),
    bloom: lerpOklab(bloom, o.bloom, t),
    glow: lerpOklab(glow, o.glow, t),
  );
}

/// Регион в двух состояниях: в Мгле и восстановленный.
class RegionLook {
  const RegionLook({required this.mist, required this.restored});

  final ScenePalette mist;
  final ScenePalette restored;

  ScenePalette at(double progress) => mist.lerpTo(restored, progress);
}

abstract final class Palette {
  // Интерфейс: «бумажные» карточки и тёплые акценты.
  static const Color paper = Color(0xFFFFF8EE);
  static const Color paperDeep = Color(0xFFF1E8DA);
  static const Color line = Color(0xFFE3D8C6);
  static const Color ink = Color(0xFF2D3142);
  static const Color inkSoft = Color(0xFF6B6F80);
  static const Color accent = Color(0xFFE07A5F);
  static const Color accentDeep = Color(0xFFC4613F);
  static const Color calm = Color(0xFF3F5F8C);
  static const Color gold = Color(0xFFFFD37A);
  static const Color disabled = Color(0xFFC9C1B4);
  static const Color eye = Color(0xFF1C2230);

  static const List<RegionLook> regions = [
    // Рассветное море — направление 1.
    RegionLook(
      mist: ScenePalette(
        skyTop: Color(0xFF7F868E),
        skyBottom: Color(0xFFC6C7C2),
        cloud: Color(0xFFE2E0DA),
        whaleBack: Color(0xFF4E555D),
        whaleRim: Color(0xFFAEB3B4),
        green: Color(0xFF858C84),
        bloom: Color(0xFFB5A9A3),
        glow: Color(0xFFD9D4C7),
      ),
      restored: ScenePalette(
        skyTop: Color(0xFF7C9BE0),
        skyBottom: Color(0xFFF7C9A9),
        cloud: Color(0xFFFFF3E3),
        whaleBack: Color(0xFF3F5F8C),
        whaleRim: Color(0xFFA8DCD5),
        green: Color(0xFF6FBF8A),
        bloom: Color(0xFFF48FB1),
        glow: Color(0xFFFFD37A),
      ),
    ),
    // Пояс бурь — холоднее и свежее.
    RegionLook(
      mist: ScenePalette(
        skyTop: Color(0xFF6E757D),
        skyBottom: Color(0xFFB4B6B3),
        cloud: Color(0xFFD5D4CF),
        whaleBack: Color(0xFF474D55),
        whaleRim: Color(0xFFA2A8AA),
        green: Color(0xFF7C847D),
        bloom: Color(0xFFA9A4A6),
        glow: Color(0xFFCBC8BF),
      ),
      restored: ScenePalette(
        skyTop: Color(0xFF4F6FA8),
        skyBottom: Color(0xFFBFD8D2),
        cloud: Color(0xFFEEF5F3),
        whaleBack: Color(0xFF2F5578),
        whaleRim: Color(0xFF9AD9D3),
        green: Color(0xFF5FAF8F),
        bloom: Color(0xFF8EC5FF),
        glow: Color(0xFFE6F7FF),
      ),
    ),
    // Поля сияния — ночь, направление 2 «Силуэты и фонари».
    RegionLook(
      mist: ScenePalette(
        skyTop: Color(0xFF1C2027),
        skyBottom: Color(0xFF3B414A),
        cloud: Color(0xFF565D66),
        whaleBack: Color(0xFF121519),
        whaleRim: Color(0xFF6E7681),
        green: Color(0xFF2F363D),
        bloom: Color(0xFF8D8A84),
        glow: Color(0xFFB9B3A6),
      ),
      restored: ScenePalette(
        skyTop: Color(0xFF191C47),
        skyBottom: Color(0xFF3E8C7E),
        cloud: Color(0xFF6A4C8C),
        whaleBack: Color(0xFF0F1030),
        whaleRim: Color(0xFFF2B880),
        green: Color(0xFF2E5E5A),
        bloom: Color(0xFF9DFFD0),
        glow: Color(0xFFFFC56B),
      ),
    ),
  ];

  static RegionLook region(int index) => regions[index.clamp(0, regions.length - 1)];
}

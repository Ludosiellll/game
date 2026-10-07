import 'package:skywhale/core/content.dart';

/// Простые круглые числа для тестов правил. Боевой баланс живёт в
/// `defaultContent` и меняется независимо от этих тестов.
GameContent makeTestContent({double routeGrowthPerSong = 0}) => GameContent(
  generators: const [
    GeneratorDef(
      id: 'flower',
      name: 'Цветок',
      baseCost: 10,
      baseProduction: 0.4,
      costGrowth: 1.10,
      bloom: 1,
      region: 0,
    ),
    GeneratorDef(id: 'bush', name: 'Куст', baseCost: 110, baseProduction: 3, costGrowth: 1.11, bloom: 3, region: 0),
    GeneratorDef(id: 'hive', name: 'Улей', baseCost: 1.3e3, baseProduction: 22, costGrowth: 1.12, bloom: 10, region: 1),
    GeneratorDef(
      id: 'mill',
      name: 'Мельница',
      baseCost: 1.6e4,
      baseProduction: 160,
      costGrowth: 1.13,
      bloom: 30,
      region: 1,
    ),
    GeneratorDef(
      id: 'lantern',
      name: 'Фонарный домик',
      baseCost: 2.2e5,
      baseProduction: 1.2e3,
      costGrowth: 1.14,
      bloom: 100,
      region: 2,
    ),
    GeneratorDef(
      id: 'observatory',
      name: 'Обсерватория',
      baseCost: 3.2e6,
      baseProduction: 9e3,
      costGrowth: 1.15,
      bloom: 300,
      region: 2,
    ),
  ],
  bird: const BirdDef(name: 'Птица', baseCost: 25, costGrowth: 1.15, notesPerSecond: 0.5),
  ownedMilestones: const [25, 50, 100, 200, 300, 400],
  regions: const [
    RegionDef(id: 'dawn', name: 'Рассветное море', bloomTarget: 60),
    RegionDef(id: 'storm', name: 'Пояс бурь', bloomTarget: 400),
    RegionDef(id: 'aurora', name: 'Поля сияния', bloomTarget: 2500),
  ],
  regionIncomeMultiplier: 2,
  routeGrowthPerSong: routeGrowthPerSong,
  baseTapShare: 0.03,
  seedsScale: 3,
  seedsDivisor: 1e9,
  seedsPower: 0.5,
  baseOfflineHours: 8,
  boostMinutes: 10,
  maxBoostMinutes: 60,
  memory: const [
    MemoryNodeDef(id: 'echo', name: 'Эхо памяти', description: '', maxLevel: 100, costBase: 1, costStep: 1),
    MemoryNodeDef(id: 'voice', name: 'Сильный голос', description: '', maxLevel: 10, costBase: 2, costStep: 2),
    MemoryNodeDef(id: 'sprouts', name: 'Ранние ростки', description: '', maxLevel: 5, costBase: 1, costStep: 2),
    MemoryNodeDef(id: 'sleep', name: 'Долгий сон', description: '', maxLevel: 8, costBase: 1, costStep: 1),
    MemoryNodeDef(id: 'path', name: 'Лёгкий путь', description: '', maxLevel: 5, costBase: 3, costStep: 3),
    MemoryNodeDef(id: 'flock', name: 'Стайка', description: '', maxLevel: 10, costBase: 1, costStep: 1),
  ],
  memoryEffects: const MemoryEffects(
    echoIncomePerLevel: 0.5,
    voiceTapSharePerLevel: 0.02,
    sproutsFlowersPerLevel: 10,
    sleepHoursPerLevel: 2,
    pathBloomPerLevel: 0.15,
    flockNotesPerLevel: 0.25,
  ),
  whales: const [
    WhaleDef(id: 'dawn', name: 'Рассветный кит', atSong: 1, description: ''),
    WhaleDef(id: 'storm', name: 'Грозовой кит', atSong: 3, description: ''),
    WhaleDef(id: 'night', name: 'Ночной кит', atSong: 6, description: ''),
  ],
  whaleEffects: const WhaleEffects(dawnOfflineMultiplier: 2, stormTapAndBirdMultiplier: 2, nightIncomeMultiplier: 3),
);

final GameContent testContent = makeTestContent();

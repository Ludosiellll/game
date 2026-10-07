/// Игровой контент и баланс.
///
/// Все числа собраны в [GameContent], чтобы правила (`economy.dart`) можно
/// было тестировать на простом тестовом наборе, а боевой баланс
/// [defaultContent] подбирать симулятором (`dart run tool/simulate.dart`).
library;

class GeneratorDef {
  const GeneratorDef({
    required this.id,
    required this.name,
    required this.baseCost,
    required this.baseProduction,
    required this.costGrowth,
    required this.bloom,
    required this.region,
  });

  final String id;
  final String name;

  /// Цена первой штуки.
  final double baseCost;

  /// Эхо в секунду от одной штуки до множителей.
  final double baseProduction;

  /// Во сколько раз дорожает каждая следующая штука.
  final double costGrowth;

  /// Сколько «цвета неба» даёт одна штука.
  final double bloom;

  /// Индекс региона, в котором растение открывается.
  final int region;
}

class BirdDef {
  const BirdDef({required this.name, required this.baseCost, required this.costGrowth, required this.notesPerSecond});

  final String name;
  final double baseCost;
  final double costGrowth;

  /// Сколько «тапов» в секунду делает одна птица.
  final double notesPerSecond;
}

class RegionDef {
  const RegionDef({required this.id, required this.name, required this.bloomTarget});

  final String id;
  final String name;

  /// Накопленный цвет сада, при котором регион восстановлен (до первой песни).
  final double bloomTarget;
}

class MemoryNodeDef {
  const MemoryNodeDef({
    required this.id,
    required this.name,
    required this.description,
    required this.maxLevel,
    required this.costBase,
    required this.costStep,
  });

  final String id;
  final String name;
  final String description;
  final int maxLevel;

  /// Цена уровня `l` (с нуля) = costBase + costStep × l семян.
  final int costBase;
  final int costStep;

  int costOfLevel(int level) => costBase + costStep * level;
}

class WhaleDef {
  const WhaleDef({required this.id, required this.name, required this.atSong, required this.description});

  final String id;
  final String name;

  /// После какой по счёту Долгой песни кит просыпается.
  final int atSong;
  final String description;
}

/// Эффекты узлов Дерева памяти за один уровень.
class MemoryEffects {
  const MemoryEffects({
    required this.echoIncomePerLevel,
    required this.voiceTapSharePerLevel,
    required this.sproutsFlowersPerLevel,
    required this.sleepHoursPerLevel,
    required this.pathBloomPerLevel,
    required this.flockNotesPerLevel,
  });

  /// «Эхо памяти»: +доля ко всему доходу.
  final double echoIncomePerLevel;

  /// «Сильный голос»: +доля дохода в секунду к каждому тапу.
  final double voiceTapSharePerLevel;

  /// «Ранние ростки»: цветов в начале песни.
  final int sproutsFlowersPerLevel;

  /// «Долгий сон»: +часов к офлайн-лимиту.
  final double sleepHoursPerLevel;

  /// «Лёгкий путь»: +доля цвета от сада.
  final double pathBloomPerLevel;

  /// «Стайка»: +доля к частоте пения птиц.
  final double flockNotesPerLevel;
}

/// Эффекты проснувшихся китов.
class WhaleEffects {
  const WhaleEffects({
    required this.dawnOfflineMultiplier,
    required this.stormTapAndBirdMultiplier,
    required this.nightIncomeMultiplier,
  });

  final double dawnOfflineMultiplier;
  final double stormTapAndBirdMultiplier;
  final double nightIncomeMultiplier;
}

class GameContent {
  const GameContent({
    required this.generators,
    required this.bird,
    required this.ownedMilestones,
    required this.regions,
    required this.regionIncomeMultiplier,
    required this.routeGrowthPerSong,
    required this.baseTapShare,
    required this.seedsScale,
    required this.seedsDivisor,
    required this.seedsPower,
    required this.baseOfflineHours,
    required this.boostMinutes,
    required this.maxBoostMinutes,
    required this.memory,
    required this.memoryEffects,
    required this.whales,
    required this.whaleEffects,
  });

  final List<GeneratorDef> generators;
  final BirdDef bird;

  /// Каждый достигнутый порог удваивает доход этого растения.
  final List<int> ownedMilestones;
  final List<RegionDef> regions;

  /// Множитель дохода за каждый пройденный регион маршрута.
  final double regionIncomeMultiplier;

  /// После каждой песни стае нужно больше цвета: цель × (1 + рост × песни).
  final double routeGrowthPerSong;

  /// Базовая доля дохода в секунду, которую добавляет каждый тап.
  final double baseTapShare;

  /// Семена за песню: floor(scale × (заработано за забег / divisor)^power).
  final double seedsScale;
  final double seedsDivisor;
  final double seedsPower;

  final double baseOfflineHours;
  final int boostMinutes;
  final int maxBoostMinutes;

  final List<MemoryNodeDef> memory;
  final MemoryEffects memoryEffects;
  final List<WhaleDef> whales;
  final WhaleEffects whaleEffects;

  GeneratorDef generatorById(String id) => generators.firstWhere(
    (g) => g.id == id,
    orElse: () => throw ArgumentError.value(id, 'id', 'Нет такого растения'),
  );

  MemoryNodeDef memoryById(String id) =>
      memory.firstWhere((m) => m.id == id, orElse: () => throw ArgumentError.value(id, 'id', 'Нет такого узла'));

  WhaleDef whaleById(String id) =>
      whales.firstWhere((w) => w.id == id, orElse: () => throw ArgumentError.value(id, 'id', 'Нет такого кита'));
}

const List<MemoryNodeDef> _memoryNodes = [
  MemoryNodeDef(
    id: 'echo',
    name: 'Эхо памяти',
    description: '+25% ко всему доходу',
    maxLevel: 100,
    costBase: 1,
    costStep: 2,
  ),
  MemoryNodeDef(
    id: 'voice',
    name: 'Сильный голос',
    description: 'Тап добавляет ещё 2% дохода в секунду',
    maxLevel: 10,
    costBase: 2,
    costStep: 2,
  ),
  MemoryNodeDef(
    id: 'sprouts',
    name: 'Ранние ростки',
    description: 'Песня начинается с 10 цветов',
    maxLevel: 5,
    costBase: 1,
    costStep: 2,
  ),
  MemoryNodeDef(
    id: 'sleep',
    name: 'Долгий сон',
    description: '+2 часа к офлайн-лимиту',
    maxLevel: 8,
    costBase: 1,
    costStep: 1,
  ),
  MemoryNodeDef(
    id: 'path',
    name: 'Лёгкий путь',
    description: 'Сад даёт на 15% больше цвета',
    maxLevel: 5,
    costBase: 3,
    costStep: 3,
  ),
  MemoryNodeDef(
    id: 'flock',
    name: 'Стайка',
    description: 'Птицы поют на 25% чаще',
    maxLevel: 10,
    costBase: 1,
    costStep: 1,
  ),
];

const List<WhaleDef> _whales = [
  WhaleDef(id: 'dawn', name: 'Рассветный кит', atSong: 1, description: 'Офлайн-доход ×2'),
  WhaleDef(id: 'storm', name: 'Грозовой кит', atSong: 3, description: 'Тапы и птицы ×2'),
  WhaleDef(id: 'night', name: 'Ночной кит', atSong: 6, description: 'Весь доход ×3'),
];

/// Боевой баланс. Менять только вместе с прогоном симулятора и записью
/// результата в README (раздел «Баланс»).
const GameContent defaultContent = GameContent(
  generators: [
    GeneratorDef(
      id: 'flower',
      name: 'Цветок',
      baseCost: 10,
      baseProduction: 0.15,
      costGrowth: 1.15,
      bloom: 1,
      region: 0,
    ),
    GeneratorDef(id: 'bush', name: 'Куст', baseCost: 120, baseProduction: 1, costGrowth: 1.15, bloom: 4, region: 0),
    GeneratorDef(id: 'hive', name: 'Улей', baseCost: 1.6e3, baseProduction: 8, costGrowth: 1.15, bloom: 15, region: 1),
    GeneratorDef(
      id: 'mill',
      name: 'Мельница',
      baseCost: 2.4e4,
      baseProduction: 60,
      costGrowth: 1.15,
      bloom: 50,
      region: 1,
    ),
    GeneratorDef(
      id: 'lantern',
      name: 'Фонарный домик',
      baseCost: 4e5,
      baseProduction: 480,
      costGrowth: 1.15,
      bloom: 160,
      region: 2,
    ),
    GeneratorDef(
      id: 'observatory',
      name: 'Обсерватория',
      baseCost: 7e6,
      baseProduction: 4e3,
      costGrowth: 1.15,
      bloom: 500,
      region: 2,
    ),
  ],
  bird: BirdDef(name: 'Певчая птица', baseCost: 30, costGrowth: 1.5, notesPerSecond: 0.25),
  ownedMilestones: [25, 50, 100, 150, 200, 250, 300, 400],
  regions: [
    RegionDef(id: 'dawn', name: 'Рассветное море', bloomTarget: 100),
    RegionDef(id: 'storm', name: 'Пояс бурь', bloomTarget: 1000),
    RegionDef(id: 'aurora', name: 'Поля сияния', bloomTarget: 8000),
  ],
  regionIncomeMultiplier: 1.5,
  routeGrowthPerSong: 0.25,
  baseTapShare: 0.05,
  seedsScale: 3,
  seedsDivisor: 2e7,
  seedsPower: 1 / 3,
  baseOfflineHours: 8,
  boostMinutes: 10,
  maxBoostMinutes: 60,
  memory: _memoryNodes,
  memoryEffects: MemoryEffects(
    echoIncomePerLevel: 0.25,
    voiceTapSharePerLevel: 0.02,
    sproutsFlowersPerLevel: 10,
    sleepHoursPerLevel: 2,
    pathBloomPerLevel: 0.15,
    flockNotesPerLevel: 0.25,
  ),
  whales: _whales,
  whaleEffects: WhaleEffects(dawnOfflineMultiplier: 2, stormTapAndBirdMultiplier: 2, nightIncomeMultiplier: 3),
);

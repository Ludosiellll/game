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
  const RegionDef({required this.id, required this.name, required this.bloomTarget, required this.guardian});

  final String id;
  final String name;

  /// Накопленный цвет сада, при котором регион восстановлен (до первой песни).
  final double bloomTarget;

  /// Босс-страж: его надо победить, чтобы лететь дальше или петь Долгую песнь.
  final String guardian;
}

/// Возраст кита. Кит не сбрасывается: годы копятся всю игру, а новый
/// возраст открывается победой в испытании.
class AgeDef {
  const AgeDef({required this.id, required this.name, required this.years, required this.regionsOpen, this.whale});

  final String id;
  final String name;

  /// Сколько лет нужно, чтобы открылось испытание этого возраста.
  final double years;

  /// Сколько регионов маршрута доступно киту этого возраста.
  final int regionsOpen;

  /// Кит стаи, который просыпается при переходе в этот возраст.
  final String? whale;
}

/// Босс: страж региона или испытание возраста.
class BossDef {
  const BossDef({
    required this.id,
    required this.name,
    required this.title,
    required this.hpFactor,
    required this.seconds,
    required this.scales,
    required this.years,
    required this.payoutSeconds,
    required this.notesPerVolley,
    required this.volleySeconds,
    required this.shield,
    this.scalesPerAge = 0,
  });

  final String id;
  final String name;

  /// Подпись под именем: «Страж Пояса бурь».
  final String title;

  /// Здоровье = опорный доход в секунду × длительность × hpFactor.
  final double hpFactor;
  final double seconds;

  /// Награда: чешуйки (+ scalesPerAge за каждый возраст кита), годы и Эхо
  /// в размере дохода за payoutSeconds.
  final int scales;
  final int scalesPerAge;
  final double years;
  final double payoutSeconds;

  /// Тёмные ноты: сколько за залп и раз в сколько секунд.
  final int notesPerVolley;
  final double volleySeconds;

  /// Ставит ли босс щит тишины.
  final bool shield;
}

/// Черта кита, которая прокачивается за чешуйки.
class TraitDef {
  const TraitDef({
    required this.id,
    required this.name,
    required this.description,
    required this.maxLevel,
    required this.costBase,
    required this.costGrowth,
  });

  final String id;
  final String name;
  final String description;
  final int maxLevel;

  /// Цена уровня `l` (с нуля) = ceil(costBase × costGrowth^l) чешуек.
  final double costBase;
  final double costGrowth;
}

/// Эффекты черт кита за один уровень.
class TraitEffects {
  const TraitEffects({
    required this.voiceTapPerLevel,
    required this.heartIncomePerLevel,
    required this.finsOfflineHoursPerLevel,
    required this.finsFightSecondsPerLevel,
    required this.spiritCritPerLevel,
  });

  /// «Голос»: +доля к силе тапа (и в бою).
  final double voiceTapPerLevel;

  /// «Сердце»: +доля ко всему доходу.
  final double heartIncomePerLevel;

  /// «Плавники»: +часов офлайн-лимита и +секунд боя.
  final double finsOfflineHoursPerLevel;
  final double finsFightSecondsPerLevel;

  /// «Дух»: +шанс крита в бою.
  final double spiritCritPerLevel;
}

/// Правила боя и роста кита.
class FightRules {
  const FightRules({
    required this.tapMultiplier,
    required this.crescendoStep,
    required this.crescendoMax,
    required this.crescendoWindow,
    required this.crescendoResetAfter,
    required this.baseCrit,
    required this.critMultiplier,
    required this.songChargePerTap,
    required this.songChargePerPop,
    required this.songChargePerCrack,
    required this.songSeconds,
    required this.songTapMultiplier,
    required this.songPassiveMultiplier,
    required this.songBurstTaps,
    required this.noteFlightSeconds,
    required this.popDamageTaps,
    required this.mutePerNote,
    required this.muteFloor,
    required this.shieldDamageFactor,
    required this.shieldHits,
    required this.shieldFirstAt,
    required this.shieldEvery,
    required this.retrySeconds,
    required this.adExtraSeconds,
    required this.autoWinMargin,
    required this.trialBoss,
    required this.yearsPerSecond,
    required this.yearsPerSong,
    required this.songFullCycleHours,
  });

  /// В бою нота бьёт сильнее обычного тапа: урон = сила тапа × tapMultiplier.
  final double tapMultiplier;

  /// Крещендо: частые тапы (не реже crescendoWindow секунд) поднимают множитель
  /// на crescendoStep до crescendoMax; пауза дольше crescendoResetAfter — сброс.
  final double crescendoStep;
  final double crescendoMax;
  final double crescendoWindow;
  final double crescendoResetAfter;

  final double baseCrit;
  final double critMultiplier;

  /// Заряд Песни кита (0…1) за тап, лопнутую ноту и удар по трещине щита.
  final double songChargePerTap;
  final double songChargePerPop;
  final double songChargePerCrack;

  /// Песнь кита: мгновенный удар силой songBurstTaps тапов и окно songSeconds,
  /// в котором тапы и сад бьют сильнее.
  final double songSeconds;
  final double songTapMultiplier;
  final double songPassiveMultiplier;
  final double songBurstTaps;

  /// Тёмная нота летит до сада столько секунд; лопнутая бьёт как popDamageTaps
  /// тапов; долетевшая глушит сад на mutePerNote (не ниже muteFloor).
  final double noteFlightSeconds;
  final double popDamageTaps;
  final double mutePerNote;
  final double muteFloor;

  /// Щит тишины: урон × shieldDamageFactor, пока не будет shieldHits ударов
  /// по трещине. Встаёт на shieldFirstAt секунде и дальше каждые shieldEvery.
  final double shieldDamageFactor;
  final int shieldHits;
  final double shieldFirstAt;
  final double shieldEvery;

  /// После поражения повторить можно через retrySeconds (сразу — за рекламу).
  final double retrySeconds;

  /// +секунд боя за рекламу (один раз за бой).
  final double adExtraSeconds;

  /// Автопобеда, если сад сам наносит урона ≥ здоровья × autoWinMargin.
  final double autoWinMargin;

  /// Босс испытаний возраста.
  final String trialBoss;

  /// Годы кита: за секунду игры или офлайна и за Долгую песнь.
  final double yearsPerSecond;
  final double yearsPerSong;

  /// Песня даёт полный век сна, только если с прошлой песни прошло столько
  /// часов (иначе — долю): спетая наспех песня — короткий сон. Первая песня
  /// всегда даёт полный век.
  final double songFullCycleHours;
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
  const WhaleDef({required this.id, required this.name, required this.description});

  final String id;
  final String name;

  /// Кит просыпается, когда наш кит переходит в возраст с этим китом
  /// ([AgeDef.whale]).
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
    required this.motherIncomeMultiplier,
  });

  final double dawnOfflineMultiplier;
  final double stormTapAndBirdMultiplier;
  final double nightIncomeMultiplier;
  final double motherIncomeMultiplier;
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
    required this.ages,
    required this.bosses,
    required this.traits,
    required this.traitEffects,
    required this.fight,
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

  /// Возрасты кита по порядку, первый — с 0 лет.
  final List<AgeDef> ages;
  final List<BossDef> bosses;
  final List<TraitDef> traits;
  final TraitEffects traitEffects;
  final FightRules fight;

  BossDef bossById(String id) =>
      bosses.firstWhere((b) => b.id == id, orElse: () => throw ArgumentError.value(id, 'id', 'Нет такого босса'));

  TraitDef traitById(String id) =>
      traits.firstWhere((t) => t.id == id, orElse: () => throw ArgumentError.value(id, 'id', 'Нет такой черты'));

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
  WhaleDef(id: 'dawn', name: 'Рассветный кит', description: 'Офлайн-доход ×2'),
  WhaleDef(id: 'storm', name: 'Грозовой кит', description: 'Тапы и птицы ×2'),
  WhaleDef(id: 'night', name: 'Ночной кит', description: 'Весь доход ×3'),
  WhaleDef(id: 'mother', name: 'Мать-кит', description: 'Весь доход ×2'),
];

const List<AgeDef> _ages = [
  AgeDef(id: 'calf', name: 'Китёнок', years: 0, regionsOpen: 1),
  AgeDef(id: 'young', name: 'Юный кит', years: 100, regionsOpen: 2, whale: 'dawn'),
  AgeDef(id: 'adult', name: 'Взрослый кит', years: 500, regionsOpen: 3, whale: 'storm'),
  AgeDef(id: 'ancient', name: 'Древний кит', years: 1500, regionsOpen: 3, whale: 'night'),
  AgeDef(id: 'deity', name: 'Небесный кит', years: 2200, regionsOpen: 3, whale: 'mother'),
];

const List<BossDef> _bosses = [
  BossDef(
    id: 'fog',
    name: 'Ворчливый Туман',
    title: 'Страж Рассветного моря',
    hpFactor: 3.75,
    seconds: 40,
    scales: 1,
    years: 5,
    payoutSeconds: 60,
    notesPerVolley: 1,
    volleySeconds: 7,
    shield: false,
  ),
  BossDef(
    id: 'petrel',
    name: 'Буревестник Безмолвия',
    title: 'Страж Пояса бурь',
    hpFactor: 4.5,
    seconds: 40,
    scales: 2,
    years: 8,
    payoutSeconds: 90,
    notesPerVolley: 3,
    volleySeconds: 6,
    shield: true,
  ),
  BossDef(
    id: 'eater',
    name: 'Пожиратель Света',
    title: 'Страж Полей сияния',
    hpFactor: 5.2,
    seconds: 40,
    scales: 3,
    years: 12,
    payoutSeconds: 120,
    notesPerVolley: 3,
    volleySeconds: 5,
    shield: true,
  ),
  BossDef(
    id: 'shadow',
    name: 'Тень кита',
    title: 'Испытание возраста',
    hpFactor: 0.8,
    seconds: 60,
    scales: 10,
    scalesPerAge: 5,
    years: 0,
    payoutSeconds: 300,
    notesPerVolley: 2,
    volleySeconds: 6,
    shield: true,
  ),
];

const List<TraitDef> _traits = [
  TraitDef(id: 'voice', name: 'Голос', description: '+25% к силе тапа', maxLevel: 25, costBase: 3, costGrowth: 1.6),
  TraitDef(
    id: 'heart',
    name: 'Сердце',
    description: '+10% ко всему доходу',
    maxLevel: 25,
    costBase: 5,
    costGrowth: 1.7,
  ),
  TraitDef(
    id: 'fins',
    name: 'Плавники',
    description: '+1 ч офлайна и +2 с боя',
    maxLevel: 16,
    costBase: 3,
    costGrowth: 1.5,
  ),
  TraitDef(id: 'spirit', name: 'Дух', description: '+3% шанса крита', maxLevel: 10, costBase: 4, costGrowth: 1.6),
];

const FightRules _fightRules = FightRules(
  tapMultiplier: 4,
  crescendoStep: 0.1,
  crescendoMax: 3,
  crescendoWindow: 0.5,
  crescendoResetAfter: 1,
  baseCrit: 0.05,
  critMultiplier: 3,
  songChargePerTap: 0.015,
  songChargePerPop: 0.12,
  songChargePerCrack: 0.1,
  songSeconds: 3,
  songTapMultiplier: 4,
  songPassiveMultiplier: 3,
  songBurstTaps: 30,
  noteFlightSeconds: 3.5,
  popDamageTaps: 3,
  mutePerNote: 0.15,
  muteFloor: 0.4,
  shieldDamageFactor: 0.1,
  shieldHits: 3,
  shieldFirstAt: 8,
  shieldEvery: 12,
  retrySeconds: 60,
  adExtraSeconds: 15,
  autoWinMargin: 1.25,
  trialBoss: 'shadow',
  yearsPerSecond: 1 / 1800,
  yearsPerSong: 100,
  songFullCycleHours: 3,
);

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
    RegionDef(id: 'dawn', name: 'Рассветное море', bloomTarget: 100, guardian: 'fog'),
    RegionDef(id: 'storm', name: 'Пояс бурь', bloomTarget: 1000, guardian: 'petrel'),
    RegionDef(id: 'aurora', name: 'Поля сияния', bloomTarget: 8000, guardian: 'eater'),
  ],
  regionIncomeMultiplier: 1.5,
  routeGrowthPerSong: 0.25,
  baseTapShare: 0.05,
  seedsScale: 3,
  seedsDivisor: 4e5,
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
  whaleEffects: WhaleEffects(
    dawnOfflineMultiplier: 2,
    stormTapAndBirdMultiplier: 2,
    nightIncomeMultiplier: 3,
    motherIncomeMultiplier: 2,
  ),
  ages: _ages,
  bosses: _bosses,
  traits: _traits,
  traitEffects: TraitEffects(
    voiceTapPerLevel: 0.25,
    heartIncomePerLevel: 0.10,
    finsOfflineHoursPerLevel: 1,
    finsFightSecondsPerLevel: 2,
    spiritCritPerLevel: 0.03,
  ),
  fight: _fightRules,
);

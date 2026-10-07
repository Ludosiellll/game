/// Показ рекламы за награду. В прототипе рекламы нет: реализации ниже
/// просто подтверждают просмотр. Настоящий SDK (AdMob или другой) подключается
/// отдельной реализацией этого интерфейса.
abstract interface class AdService {
  /// Показать rewarded-рекламу. `true` — игрок досмотрел и получает награду.
  Future<bool> showRewarded();
}

/// Мгновенно «досмотренная» реклама — для тестов и симулятора.
class InstantAdService implements AdService {
  const InstantAdService();

  @override
  Future<bool> showRewarded() async => true;
}

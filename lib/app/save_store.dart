import 'package:shared_preferences/shared_preferences.dart';

/// Где лежит сохранение. Отдельный интерфейс — чтобы в тестах подменять
/// хранилище на память, а позже добавить облачное (Play Games Services).
abstract interface class SaveStore {
  Future<String?> load();
  Future<void> save(String data);
}

/// Хранилище в памяти — для тестов.
class MemorySaveStore implements SaveStore {
  MemorySaveStore([this.data]);

  String? data;

  @override
  Future<String?> load() async => data;

  @override
  Future<void> save(String value) async => data = value;
}

/// Локальное хранилище устройства (SharedPreferences / localStorage в вебе).
///
/// Кроме основной записи держит резервную копию предыдущего сохранения —
/// на случай, если запись оборвётся на середине.
class PrefsSaveStore implements SaveStore {
  PrefsSaveStore({this.key = 'skywhale.save.v1'});

  final String key;
  String get _backupKey => '$key.backup';
  int _saves = 0;

  @override
  Future<String?> load() async {
    final prefs = SharedPreferencesAsync();
    return await prefs.getString(key) ?? await prefs.getString(_backupKey);
  }

  @override
  Future<void> save(String data) async {
    final prefs = SharedPreferencesAsync();
    // Раз в 10 сохранений переносим прошлое состояние в резервную копию.
    if (_saves % 10 == 0) {
      final previous = await prefs.getString(key);
      if (previous != null) await prefs.setString(_backupKey, previous);
    }
    _saves += 1;
    await prefs.setString(key, data);
  }
}

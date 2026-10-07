import 'dart:convert';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';

/// Спрайты из Blender (`tool/art/toy_scene.py` → `tool/art/pack_sprites.py`).
/// Координаты слоёв и мест построек — пиксели исходного кадра своего возраста.
const String spritesDir = 'assets/sprites';

class SpriteLayer {
  const SpriteLayer(this.image, this.rect, {this.pivot});

  final ui.Image image;

  /// Где слой лежит в кадре возраста.
  final Rect rect;

  /// Ось вращения (плавники, хвост).
  final Offset? pivot;
}

class GardenSlot {
  const GardenSlot({required this.item, required this.pos, required this.scale, this.gen, this.need = 0});

  /// Ключ постройки (`house:roof`, `flower:petal1`…).
  final String item;

  /// Точка на палубе, куда встаёт основание постройки.
  final Offset pos;

  /// Размер постройки в мире (относительно её спрайта).
  final double scale;

  /// Растение, от числа которых зависит появление (`null` — декор, виден всегда).
  final String? gen;

  /// С какого числа растений постройка появляется.
  final int need;
}

class AgeArt {
  const AgeArt({
    required this.id,
    required this.frame,
    required this.ppu,
    required this.scale,
    required this.bodyColor,
    required this.body,
    required this.finFar,
    required this.finNear,
    required this.fluke,
    required this.eyes,
    required this.mouth,
    required this.slots,
    this.eyesClosed,
    this.mouthOpen,
    this.halo,
  });

  final String id;
  final Size frame;

  /// Пикселей кадра на единицу мира — для перевода размеров построек.
  final double ppu;

  /// Масштаб кита в мире: чем старше кит, тем мельче мир вокруг.
  final double scale;
  final ui.Color bodyColor;
  final SpriteLayer body;
  final SpriteLayer finFar;
  final SpriteLayer finNear;
  final SpriteLayer fluke;
  final SpriteLayer? eyesClosed;
  final SpriteLayer? mouthOpen;
  final SpriteLayer? halo;

  /// Глаза: центр и радиус (для светящихся глаз Тени).
  final List<(Offset, double)> eyes;
  final Offset mouth;
  final List<GardenSlot> slots;

  /// Прямоугольник, в который помещаются все слои кита.
  Rect get bounds => body.rect.expandToInclude(finFar.rect).expandToInclude(finNear.rect).expandToInclude(fluke.rect);
}

class ItemArt {
  const ItemArt(this.image, this.anchor, this.ppu);

  final ui.Image image;

  /// Точка основания в пикселях спрайта.
  final Offset anchor;
  final double ppu;
}

class SpriteAtlas {
  SpriteAtlas._({
    required this.ages,
    required this.items,
    required this.birds,
    required this.bosses,
    required this.clouds,
    required this.icons,
  });

  final List<AgeArt> ages;
  final Map<String, ItemArt> items;
  final List<ui.Image> birds;
  final Map<String, ui.Image> bosses;
  final List<ui.Image> clouds;

  /// Иконки — пути ассетов для `Image.asset` в интерфейсе.
  final Map<String, String> icons;

  AgeArt age(int index) => ages[index.clamp(0, ages.length - 1)];

  static Future<SpriteAtlas> load({AssetBundle? bundle}) async {
    final b = bundle ?? rootBundle;
    final manifest = jsonDecode(await b.loadString('$spritesDir/manifest.json')) as Map<String, dynamic>;
    final cache = <String, Future<ui.Image>>{};
    Future<ui.Image> image(String file) => cache.putIfAbsent(file, () async {
      final data = await b.load('$spritesDir/$file');
      final codec = await ui.instantiateImageCodec(data.buffer.asUint8List());
      return (await codec.getNextFrame()).image;
    });

    Future<SpriteLayer?> layer(Object? raw) async {
      if (raw is! Map) return null;
      final pivot = raw['pivot'] as List?;
      return SpriteLayer(
        await image(raw['file'] as String),
        Rect.fromLTWH(_d(raw['x']), _d(raw['y']), _d(raw['w']), _d(raw['h'])),
        pivot: pivot == null ? null : Offset(_d(pivot[0]), _d(pivot[1])),
      );
    }

    final ages = <AgeArt>[];
    for (final raw in manifest['ages'] as List) {
      final a = raw as Map<String, dynamic>;
      final frame = a['frame'] as List;
      ages.add(
        AgeArt(
          id: a['id'] as String,
          frame: Size(_d(frame[0]), _d(frame[1])),
          ppu: _d(a['ppu']),
          scale: _d(a['scale']),
          bodyColor: _color(a['bodyColor'] as String),
          body: (await layer(a['body']))!,
          finFar: (await layer(a['finFar']))!,
          finNear: (await layer(a['finNear']))!,
          fluke: (await layer(a['fluke']))!,
          eyesClosed: await layer(a['eyesClosed']),
          mouthOpen: await layer(a['mouthOpen']),
          halo: await layer(a['halo']),
          eyes: [for (final e in a['eyes'] as List) (Offset(_d(e[0]), _d(e[1])), _d(e[2]))],
          mouth: Offset(_d((a['mouth'] as List)[0]), _d((a['mouth'] as List)[1])),
          slots: [
            for (final s in a['slots'] as List)
              GardenSlot(
                item: s['item'] as String,
                pos: Offset(_d(s['x']), _d(s['y'])),
                scale: _d(s['scale']),
                gen: s['gen'] as String?,
                need: (s['need'] as num?)?.toInt() ?? 0,
              ),
          ],
        ),
      );
    }

    final items = <String, ItemArt>{};
    for (final e in (manifest['items'] as Map<String, dynamic>).entries) {
      final m = e.value as Map<String, dynamic>;
      final anchor = m['anchor'] as List;
      items[e.key] = ItemArt(await image(m['file'] as String), Offset(_d(anchor[0]), _d(anchor[1])), _d(m['ppu']));
    }

    Future<List<ui.Image>> list(String key) async => [
      for (final m in manifest[key] as List) await image((m as Map)['file'] as String),
    ];

    return SpriteAtlas._(
      ages: ages,
      items: items,
      birds: await list('birds'),
      bosses: {
        for (final e in (manifest['bosses'] as Map<String, dynamic>).entries)
          e.key: await image((e.value as Map)['file'] as String),
      },
      clouds: await list('clouds'),
      icons: {
        for (final e in (manifest['icons'] as Map<String, dynamic>).entries)
          e.key: '$spritesDir/${(e.value as Map)['file']}',
      },
    );
  }
}

double _d(Object? v) => (v as num).toDouble();

ui.Color _color(String hex) => ui.Color(0xFF000000 | int.parse(hex.replaceFirst('#', ''), radix: 16));

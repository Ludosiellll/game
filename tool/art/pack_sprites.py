"""Упаковка рендеров Blender в спрайты игры.

    python3 tool/art/pack_sprites.py СЫРЫЕ_РЕНДЕРЫ assets/sprites

Сырые рендеры делает `toy_scene.py` (режимы sprites, items, birds, bosses,
clouds, icons). Здесь каждый слой обрезается по альфе и сохраняется в WebP,
заплатки «глаза закрыты» и «рот открыт» маскируются — остаются только пиксели,
которые отличаются от тела (с мягким краем, без шва). Координаты в
`manifest.json` — пиксели исходного кадра своего возраста.
"""
import json
import os
import sys

from PIL import Image, ImageChops, ImageFilter

RAW, OUT = sys.argv[1], sys.argv[2]
AGE_IDS = ["calf", "young", "adult", "ancient", "deity"]


def save(im, name):
    path = os.path.join(OUT, name + ".webp")
    im.save(path, "WEBP", quality=90, method=6)
    return name + ".webp"


def crop(im, margin=2):
    """Обрезать по непрозрачным пикселям; вернуть картинку и её смещение."""
    box = im.getchannel("A").point(lambda a: 255 if a > 2 else 0).getbbox()
    if box is None:
        return None, (0, 0)
    x0, y0 = max(0, box[0] - margin), max(0, box[1] - margin)
    x1, y1 = min(im.width, box[2] + margin), min(im.height, box[3] + margin)
    return im.crop((x0, y0, x1, y1)), (x0, y0)


def layer(path, name, extra=None):
    im = Image.open(path).convert("RGBA")
    cut, (x, y) = crop(im)
    if cut is None:
        return None
    entry = {"file": save(cut, name), "x": x, "y": y, "w": cut.width, "h": cut.height}
    entry.update(extra or {})
    return entry


def patch(body_path, patch_path, name):
    """Заплатка поверх тела: только изменившиеся пиксели, край растушёван."""
    body = Image.open(body_path).convert("RGBA")
    p = Image.open(patch_path).convert("RGBA")
    region = p.getchannel("A").getbbox()
    if region is None:
        return None
    b = body.crop(region)
    q = p.crop(region)
    diff = ImageChops.difference(q, b)
    r, g, bl, a = diff.split()
    m = ImageChops.lighter(ImageChops.lighter(r, g), ImageChops.lighter(bl, a))
    m = m.point(lambda v: 255 if v > 10 else 0)
    m = m.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(2))
    q.putalpha(ImageChops.multiply(q.getchannel("A"), m))
    cut, (x, y) = crop(q, margin=1)
    if cut is None:
        return None
    return {"file": save(cut, name), "x": region[0] + x, "y": region[1] + y, "w": cut.width, "h": cut.height}


def pack_age(n):
    src = os.path.join(RAW, f"age{n}")
    meta = json.load(open(os.path.join(src, "whale.json")))
    f = lambda layer_name: os.path.join(src, layer_name + ".png")
    age = {
        "id": AGE_IDS[n - 1],
        "frame": meta["size"],
        "ppu": meta["ppu"],
        "scale": meta["scale"],
        "itemScale": meta["itemScale"],
        "bodyColor": meta["bodyColor"],
        "body": layer(f("body"), f"whale{n}_body"),
        "finFar": layer(f("fin_far"), f"whale{n}_fin_far", {"pivot": meta["finFar"]}),
        "finNear": layer(f("fin_near"), f"whale{n}_fin_near", {"pivot": meta["finNear"]}),
        "fluke": layer(f("fluke"), f"whale{n}_fluke", {"pivot": meta["fluke"]}),
        "eyesClosed": patch(f("body"), f("eyes_closed"), f"whale{n}_eyes_closed"),
        "mouthOpen": patch(f("body"), f("mouth_open"), f"whale{n}_mouth_open"),
        "eyes": meta["eyes"],
        "mouth": meta["mouth"],
        "slots": [{k: v for k, v in s.items() if k != "depth"} for s in meta["slots"]],
    }
    if meta.get("halo"):
        age["halo"] = layer(f("halo"), f"whale{n}_halo")
    return age


def pack_items():
    src = os.path.join(RAW, "items")
    meta = json.load(open(os.path.join(src, "items.json")))
    items = {}
    for key, m in meta.items():
        im = Image.open(os.path.join(src, m["file"] + ".png")).convert("RGBA")
        cut, (x, y) = crop(im)
        items[key] = {
            "file": save(cut, m["file"]),
            "w": cut.width,
            "h": cut.height,
            "anchor": [round(m["anchor"][0] - x, 2), round(m["anchor"][1] - y, 2)],
            "ppu": m["ppu"],
        }
    return items


def pack_simple(sub, names, prefix=""):
    out = {}
    for name in names:
        path = os.path.join(RAW, sub, name + ".png")
        if not os.path.exists(path):
            continue
        im = Image.open(path).convert("RGBA")
        cut, _ = crop(im)
        out[name] = {"file": save(cut, prefix + name), "w": cut.width, "h": cut.height}
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for old in os.listdir(OUT):
        if old.endswith(".webp"):
            os.remove(os.path.join(OUT, old))
    manifest = {
        "version": 1,
        "ages": [pack_age(n) for n in range(1, 6)],
        "items": pack_items(),
        "birds": list(pack_simple("birds", ["bird0", "bird1", "bird2"]).values()),
        "bosses": {k.replace("boss_", ""): v for k, v in pack_simple("bosses", ["boss_fog", "boss_petrel", "boss_eater"]).items()},
        "clouds": list(pack_simple("clouds", ["cloud0", "cloud1", "cloud2"]).values()),
        "icons": pack_simple("icons", ["flower", "bush", "hive", "mill", "lantern", "observatory", "memory", "route",
                                       "bird", "island"], prefix="icon_"),
    }
    with open(os.path.join(OUT, "manifest.json"), "w") as f:
        json.dump(manifest, f, ensure_ascii=False, separators=(",", ":"))
    total = sum(os.path.getsize(os.path.join(OUT, x)) for x in os.listdir(OUT))
    print(f"packed {len(os.listdir(OUT))} files, {total / 1024:.0f} KB")


main()

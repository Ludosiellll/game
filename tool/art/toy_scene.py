"""Игрушечное 3D: кит с садом на спине, предметы сада, птицы и облака — моделями из кода.

Все модели строятся из примитивов (сочные цвета, мягкий свет, крупные формы),
рендер — Cycles на CPU с прозрачным фоном: небо и интерфейс рисует игра.
Запуск без окна:

    blender -b --python tool/art/toy_scene.py -- РЕЖИМ ПУТЬ [опции]

Режимы:
    scene   кит с садом одним кадром (ПУТЬ — файл .png);
    icons   иконки предметов для магазина и кнопок (ПУТЬ — папка);
    clouds  облака отдельными картинками (ПУТЬ — папка).

Опции:
    --size=ШxВ     размер кадра (scene 900x1125, icons 256x256, clouds 512x320);
    --samples=N    сэмплы Cycles (48);
    --yaw=ГРАДУСЫ  поворот кита: 142 — мордой прямо в камеру (112);
    --outline      тёмный контур, ближе к мультяшным референсам (--line=ПИКСЕЛИ — толщина);
    --flat         плоское затенение кита крупными гранями;
    --only=a,b     для icons: только перечисленные иконки (flower, bird, …).
"""

import math
import os
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector
from mathutils.bvhtree import BVHTree


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    pos = [a for a in argv if not a.startswith("--")]
    opts = {}
    for a in argv:
        if a.startswith("--"):
            key, _, value = a[2:].partition("=")
            opts[key] = value or "1"
    return (pos[0] if pos else "scene"), (pos[1] if len(pos) > 1 else "/tmp/toy_scene.png"), opts


MODE, OUT, OPTS = parse_args()
SAMPLES = int(OPTS.get("samples", 48))
YAW = math.radians(float(OPTS.get("yaw", 112)))
OUTLINE = "outline" in OPTS
FLAT = "flat" in OPTS
_DEFAULT_SIZE = {"scene": (900, 1125), "icons": (256, 256), "clouds": (512, 320)}[MODE]
W, H = tuple(int(s) for s in OPTS["size"].split("x")) if "size" in OPTS else _DEFAULT_SIZE

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

# Камера: ортографическая «изометрия» (наклон 55° от вертикали, поворот −38°).
CAM_PITCH, CAM_TURN = math.radians(55), math.radians(-38)
# Поворот вокруг z, при котором «перед» предмета (+y) смотрит в камеру.
FACE_CAMERA = CAM_TURN + math.pi


# --- материалы ---------------------------------------------------------------------

def hex_rgb(h):
    h = h.lstrip("#")
    srgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    # Blender ждёт линейные значения.
    return tuple(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in srgb)


_materials = {}


def mat(color, rough=0.6, emit=0.0):
    key = ("solid", color, rough, emit)
    if key not in _materials:
        m = bpy.data.materials.new(f"m{color}")
        m.use_nodes = True
        bsdf = m.node_tree.nodes["Principled BSDF"]
        rgb = hex_rgb(color)
        bsdf.inputs["Base Color"].default_value = (*rgb, 1)
        bsdf.inputs["Roughness"].default_value = rough
        bsdf.inputs["Specular IOR Level"].default_value = 0.3
        if emit > 0:
            bsdf.inputs["Emission Color"].default_value = (*rgb, 1)
            bsdf.inputs["Emission Strength"].default_value = emit
        _materials[key] = m
    return _materials[key]


def two_tone(top, bottom, edge, soft=0.02, tilt=0.0, rough=0.5):
    """Два цвета с границей по высоте в координатах объекта: z − tilt·y = edge."""
    key = ("two", top, bottom, edge, soft, tilt, rough)
    if key in _materials:
        return _materials[key]
    m = bpy.data.materials.new(f"m{top}{bottom}")
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = rough
    bsdf.inputs["Specular IOR Level"].default_value = 0.3
    coord = nt.nodes.new("ShaderNodeTexCoord")
    dot = nt.nodes.new("ShaderNodeVectorMath")
    dot.operation = "DOT_PRODUCT"
    dot.inputs[1].default_value = (0.0, -tilt, 1.0)
    ramp = nt.nodes.new("ShaderNodeMapRange")
    ramp.inputs["From Min"].default_value = edge - soft
    ramp.inputs["From Max"].default_value = edge + soft
    mix = nt.nodes.new("ShaderNodeMix")
    mix.data_type = "RGBA"
    mix.inputs[6].default_value = (*hex_rgb(bottom), 1)
    mix.inputs[7].default_value = (*hex_rgb(top), 1)
    nt.links.new(coord.outputs["Object"], dot.inputs[0])
    nt.links.new(dot.outputs["Value"], ramp.inputs["Value"])
    nt.links.new(ramp.outputs["Result"], mix.inputs[0])
    nt.links.new(mix.outputs[2], bsdf.inputs["Base Color"])
    _materials[key] = m
    return m


# Сочная «игрушечная» палитра.
C = {
    "whale": "#3D8BFF", "belly": "#E3F4FF", "fin": "#2F6FE0",
    "eye": "#1A1C2C", "white": "#FFFFFF", "blush": "#FF8FB8", "mouth": "#22264A",
    "grass": "#72D45A", "grass_dark": "#4DB848",
    "soil": "#C98B4E", "soil_dark": "#9E6337",
    "trunk": "#8B5A35", "leaf": "#3EB95A", "leaf2": "#86DE5C", "pine": "#2FA35C",
    "wall": "#FFF6E8", "roof": "#FF5A5F", "roof2": "#FFA63D", "door": "#9C5B34",
    "window": "#FFD86B", "stone": "#E9DCC4",
    "mill": "#F5E6CC", "blade": "#FFFFFF", "sail": "#FF7A6B",
    "hive": "#FFC93C", "hive_dark": "#F0A21F", "bee": "#2A2A2A",
    "lamp": "#FFD66B", "lamp_roof": "#6C63FF", "lamp_wall": "#E6EEFF",
    "dome": "#9FB4FF", "dome_base": "#FFFFFF", "slit": "#2B2F5A", "scope": "#4D5BCE", "gold": "#FFC93C",
    "bush": "#35A852", "berry": "#FF4F8B",
    "petal1": "#FF5FA2", "petal2": "#FFD23F", "petal3": "#B57CFF", "petal4": "#FF8A3D", "center": "#FFF1A8",
    "memory": "#FF9AD5", "memory2": "#C78BFF", "spark": "#FFF6C2",
    "cloud": "#FFFFFF",
    "shirt1": "#FF6B6B", "shirt2": "#4DC9FF", "shirt3": "#FFC93C", "skin": "#FFD3B0",
    "hair1": "#6B3E26", "hair2": "#2E2A3A", "hair3": "#E8A23A",
    "bird": "#FFD23F", "bird_belly": "#FFF4C7", "bird_wing": "#FFAE2B", "beak": "#FF7F3F",
    "post": "#A86B3C", "sign": "#F2C27B",
}


# --- построение ---------------------------------------------------------------------

CREATED = []


def finish(obj, material, smooth=False):
    CREATED.append(obj)
    obj.data.materials.clear()
    obj.data.materials.append(material)
    if obj.type == "MESH":
        for p in obj.data.polygons:
            p.use_smooth = smooth
        if smooth:
            # Гладко, но с острыми рёбрами там, где грани ломаются сильно.
            obj.data.set_sharp_from_angle(angle=math.radians(50))
    return obj


def bevel(obj, width, segments=2):
    # transform_apply действует на всё выделенное — оставляем выделенным только obj.
    for other in bpy.context.selected_objects:
        other.select_set(False)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    mod = obj.modifiers.new("bevel", "BEVEL")
    mod.width = width
    mod.segments = segments
    mod.limit_method = "ANGLE"
    obj.select_set(False)


def sphere(loc, r=1.0, scale=(1, 1, 1), material=None, rot=(0, 0, 0), sub=2, smooth=False, uv=False):
    if uv:
        bpy.ops.mesh.primitive_uv_sphere_add(segments=24, ring_count=14, radius=r, location=loc)
    else:
        bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=sub, radius=r, location=loc)
    o = bpy.context.active_object
    o.scale = scale
    o.rotation_euler = rot
    return finish(o, material, smooth)


def cyl(loc, r, depth, verts=12, material=None, scale=(1, 1, 1), rot=(0, 0, 0), smooth=False, bev=0.0):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=depth, location=loc)
    o = bpy.context.active_object
    o.scale = scale
    o.rotation_euler = rot
    if bev:
        bevel(o, bev)
    return finish(o, material, smooth)


def cone(loc, r1, r2, depth, verts=8, material=None, rot=(0, 0, 0), smooth=False):
    bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=r1, radius2=r2, depth=depth, location=loc)
    o = bpy.context.active_object
    o.rotation_euler = rot
    return finish(o, material, smooth)


def box(loc, size, material=None, rot=(0, 0, 0), bev=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc)
    o = bpy.context.active_object
    o.scale = size
    o.rotation_euler = rot
    if bev:
        bevel(o, bev)
    return finish(o, material)


def torus(loc, major, minor, material, smooth=True):
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=minor, major_segments=20,
                                     minor_segments=10, location=loc)
    return finish(bpy.context.active_object, material, smooth)


def mesh_object(name, verts, faces, material, smooth=False):
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(me)
    bm.free()
    o = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(o)
    return finish(o, material, smooth)


def prism(name, outline, thickness, material, smooth=False):
    """Плоская фигура по контуру (x, y) толщиной thickness по z."""
    n = len(outline)
    verts = [(x, y, -thickness / 2) for x, y in outline] + [(x, y, thickness / 2) for x, y in outline]
    faces = [tuple(range(n)), tuple(range(2 * n - 1, n - 1, -1))]
    faces += [(i, (i + 1) % n, n + (i + 1) % n, n + i) for i in range(n)]
    return mesh_object(name, verts, faces, material, smooth)


def gable_roof(w, d, h, material):
    """Двускатная крыша: конёк вдоль x, скат смотрит вперёд (+y)."""
    x, y = w / 2, d / 2
    verts = [(-x, -y, 0), (x, -y, 0), (x, y, 0), (-x, y, 0), (-x, 0, h), (x, 0, h)]
    faces = [(0, 1, 5, 4), (3, 4, 5, 2), (0, 4, 3), (1, 2, 5), (0, 3, 2, 1)]
    return mesh_object("roof", verts, faces, material)


def group(parts, loc=(0, 0, 0), rot=0.0, scale=1.0):
    """Собирает детали в одну группу: детали заданы в своей системе, +y — фасад."""
    root = bpy.data.objects.new("group", None)
    scene.collection.objects.link(root)
    for p in parts:
        p.parent = root
    root.location = loc
    root.rotation_euler = (0, 0, rot)
    root.scale = (scale, scale, scale)
    CREATED.append(root)
    return root


# --- кит -------------------------------------------------------------------------------

HEAD_L, TAIL_L, BODY_W, BODY_H = 2.3, 3.0, 1.65, 1.45


def make_whale():
    seg, rings = (14, 9) if FLAT else (40, 24)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=seg, ring_count=rings, radius=1)
    body = bpy.context.active_object
    body.name = "whale_body"
    for v in body.data.vertices:
        x, y, z = v.co
        r = math.hypot(x, z)
        if y >= 0:  # голова: тупая и круглая (суперэллипс)
            rr = max(0.0, 1 - y ** 2.6) ** (1 / 2.6)
            yy, lift = y * HEAD_L, 0.0
        else:  # к хвосту тело сужается и слегка загибается вверх
            t = -y
            rr = math.sqrt(max(0.0, 1 - t * t)) * (1 - 0.45 * t)
            yy, lift = y * TAIL_L, 0.55 * t ** 2.2
        k = rr / r if r > 1e-9 else 0.0
        v.co = (x * k * BODY_W, yy, z * k * BODY_H + lift)
    body.data.update()
    finish(body, two_tone(C["whale"], C["belly"], edge=-0.42, soft=0.015, tilt=0.1, rough=0.42), smooth=not FLAT)

    tree = BVHTree.FromPolygons([v.co.copy() for v in body.data.vertices],
                                [tuple(p.vertices) for p in body.data.polygons])

    def surface(origin, direction):
        loc, normal, _, _ = tree.ray_cast(Vector(origin), Vector(direction).normalized())
        return loc, normal.normalized()

    def head_dir(az, el):
        az, el = math.radians(az), math.radians(el)
        return (math.sin(az) * math.cos(el), math.cos(az) * math.cos(el), math.sin(el))

    def on_surface(obj, loc, normal, size, lift=0.0):
        """Ставит объект на поверхность: локальная z — по нормали, y — вверх."""
        q = normal.to_track_quat("Z", "Y")
        obj.matrix_world = Matrix.Translation(loc + normal * lift) @ q.to_matrix().to_4x4() @ Matrix.Diagonal((*size, 1))

    head = (0.0, 0.7, 0.3)
    # Глаза: крупные, блестящие, с двумя бликами.
    for side in (-1, 1):
        loc, n = surface(head, head_dir(side * 30, 4))
        eye = sphere((0, 0, 0), 1, material=mat(C["eye"], 0.15), smooth=True, uv=True)
        on_surface(eye, loc, n, (0.2, 0.27, 0.1), lift=0.0)
        up = n.cross(Vector((0, 0, 1)).cross(n)).normalized()
        right = up.cross(n).normalized()
        hl = sphere((0, 0, 0), 1, material=mat(C["white"], 0.2, emit=0.6), smooth=True, uv=True)
        on_surface(hl, loc + up * 0.09 - right * 0.06 * side, n, (0.07, 0.08, 0.04), lift=0.08)
        hl2 = sphere((0, 0, 0), 1, material=mat(C["white"], 0.2, emit=0.6), smooth=True, uv=True)
        on_surface(hl2, loc - up * 0.1 + right * 0.06 * side, n, (0.035, 0.035, 0.02), lift=0.08)
        # Румянец.
        loc, n = surface(head, head_dir(side * 50, -8))
        blush = sphere((0, 0, 0), 1, material=mat(C["blush"], 0.8), smooth=True, uv=True)
        on_surface(blush, loc, n, (0.17, 0.11, 0.03), lift=0.0)

    # Улыбка: дуга точек на поверхности, обведённая трубкой.
    pts = []
    for i in range(11):
        a = -15 + 30 * i / 10
        el = -6 - 7 * (1 - (a / 15) ** 2)
        loc, n = surface(head, head_dir(a, el))
        pts.append(loc + n * 0.02)
    curve = bpy.data.curves.new("smile", type="CURVE")
    curve.dimensions = "3D"
    curve.bevel_depth = 0.035
    curve.bevel_resolution = 3
    curve.use_fill_caps = True
    spline = curve.splines.new("POLY")
    spline.points.add(len(pts) - 1)
    for p, co in zip(spline.points, pts):
        p.co = (*co, 1)
    smile = bpy.data.objects.new("smile", curve)
    bpy.context.collection.objects.link(smile)
    smile.data.materials.append(mat(C["mouth"], 0.3))
    CREATED.append(smile)

    # Боковые плавники: плоские «лопатки» вниз-наружу-назад.
    for side in (-1, 1):
        root, _ = surface((0, 0.9, -0.1), (side, 0.05, -0.6))
        span = Vector((side * 0.85, -0.45, -0.5)).normalized()
        across = Vector((0, 0, 1)).cross(span).normalized()
        flat_n = span.cross(across).normalized()
        rot = Matrix((span, across, flat_n)).transposed()
        fin = sphere((0, 0, 0), 1, material=mat(C["fin"], 0.45), smooth=not FLAT, uv=True)
        fin.matrix_world = (Matrix.Translation(root + span * 0.55) @ rot.to_4x4()
                            @ Matrix.Diagonal((0.72, 0.34, 0.09, 1)))

    # Хвостовой плавник: два лепестка с выемкой посередине.
    right = [(0.0, 0.08), (0.3, -0.02), (0.6, -0.15), (0.86, -0.36), (1.0, -0.64),
             (0.8, -0.6), (0.55, -0.52), (0.3, -0.52), (0.12, -0.6), (0.0, -0.5)]
    left = [(-x, y) for x, y in reversed(right[1:-1])]
    fluke = prism("fluke", [(x * 1.25, y * 1.25) for x, y in right + left], 0.12,
                  mat(C["fin"], 0.45), smooth=not FLAT)
    bevel(fluke, 0.04, segments=3)
    fluke.location = (0, -TAIL_L + 0.25, 0.52)
    fluke.rotation_euler = (math.radians(18), 0, 0)


# --- палуба и сад -------------------------------------------------------------------------

DECK_Y, DECK_A, DECK_B = -0.2, 1.18, 1.8  # центр и полуоси овала
TOP = 1.58  # верх травы


def make_deck():
    soil = cyl((0, DECK_Y, TOP - 0.38), 1, 0.62, verts=48, scale=(DECK_A, DECK_B, 1),
               material=two_tone(C["soil"], C["soil_dark"], edge=-0.08, soft=0.004), smooth=True, bev=0.07)
    grass = cyl((0, DECK_Y, TOP - 0.05), 1, 0.1, verts=48, scale=(DECK_A + 0.05, DECK_B + 0.05, 1),
                material=mat(C["grass"], 0.75), smooth=True, bev=0.04)
    return [soil, grass]


def tile(radius=0.62):
    """Круглый кусочек сада — подставка для иконок."""
    soil = cyl((0, 0, -0.16), radius, 0.26, verts=40,
               material=two_tone(C["soil"], C["soil_dark"], edge=-0.02, soft=0.004), smooth=True, bev=0.05)
    grass = cyl((0, 0, -0.03), radius + 0.03, 0.07, verts=40, material=mat(C["grass"], 0.75), smooth=True, bev=0.03)
    return [soil, grass]


def tree(s=1.0, crown=None):
    crown = crown or (C["leaf"], C["leaf2"])
    return [
        cyl((0, 0, 0.2 * s), 0.07 * s, 0.4 * s, verts=6, material=mat(C["trunk"], 0.8)),
        sphere((0, 0, 0.58 * s), 0.34 * s, sub=1, material=mat(crown[0], 0.7)),
        sphere((0.13 * s, 0.1 * s, 0.8 * s), 0.23 * s, sub=1, material=mat(crown[1], 0.7)),
    ]


def pine(s=1.0):
    parts = [cyl((0, 0, 0.12 * s), 0.06 * s, 0.24 * s, verts=6, material=mat(C["trunk"], 0.8))]
    for i, (r, z) in enumerate([(0.32, 0.38), (0.25, 0.62), (0.17, 0.84)]):
        parts.append(cone((0, 0, z * s), r * s, 0.0, 0.34 * s, verts=7, material=mat(C["pine"], 0.7)))
    return parts


def flower(petal, s=1.0):
    parts = [cyl((0, 0, 0.12 * s), 0.022 * s, 0.24 * s, verts=5, material=mat(C["leaf"], 0.7))]
    for k in range(5):
        a = k * 2 * math.pi / 5
        parts.append(sphere((math.cos(a) * 0.085 * s, math.sin(a) * 0.085 * s, 0.25 * s), 0.07 * s,
                            scale=(1, 1, 0.45), sub=2, material=mat(petal, 0.6), smooth=True))
    parts.append(sphere((0, 0, 0.27 * s), 0.05 * s, sub=2, material=mat(C["center"], 0.6), smooth=True))
    return parts


def flower_patch():
    parts = []
    for (x, y, p, s) in [(0, 0.1, C["petal1"], 1.15), (-0.2, -0.1, C["petal2"], 1.0), (0.21, -0.12, C["petal3"], 0.95)]:
        g = group(flower(p, s), loc=(x, y, 0))
        parts.append(g)
    return parts


def bush():
    parts = [
        sphere((0, 0, 0.17), 0.22, sub=2, material=mat(C["bush"], 0.7)),
        sphere((0.17, -0.05, 0.13), 0.16, sub=2, material=mat(C["bush"], 0.7)),
        sphere((-0.16, 0.02, 0.12), 0.15, sub=2, material=mat(C["bush"], 0.7)),
    ]
    for (x, y, z) in [(0.06, 0.2, 0.24), (-0.12, 0.14, 0.17), (0.2, 0.08, 0.2), (-0.02, 0.12, 0.33)]:
        parts.append(sphere((x, y, z), 0.04, sub=2, material=mat(C["berry"], 0.4), smooth=True))
    return parts


def house(roof_color):
    return [
        box((0, 0, 0.24), (0.56, 0.48, 0.48), material=mat(C["wall"], 0.6), bev=0.035),
        group([gable_roof(0.72, 0.66, 0.36, mat(roof_color, 0.55))], loc=(0, 0, 0.47)),
        box((0.17, -0.12, 0.88), (0.1, 0.1, 0.22), material=mat(C["wall"], 0.6)),
        box((-0.1, 0.245, 0.15), (0.14, 0.03, 0.26), material=mat(C["door"], 0.6), bev=0.01),
        box((0.13, 0.245, 0.28), (0.12, 0.03, 0.12), material=mat(C["window"], 0.3, emit=1.2)),
    ]


def windmill():
    parts = [
        cone((0, 0, 0.46), 0.27, 0.17, 0.92, verts=8, material=mat(C["mill"], 0.6)),
        cone((0, 0, 1.06), 0.22, 0.0, 0.32, verts=8, material=mat(C["roof2"], 0.55)),
        box((0, 0.22, 0.13), (0.14, 0.06, 0.24), material=mat(C["door"], 0.6)),
        cyl((0, 0.2, 0.8), 0.06, 0.1, verts=10, material=mat(C["door"], 0.5), rot=(math.pi / 2, 0, 0)),
    ]
    hub = Vector((0, 0.27, 0.8))
    for k in range(4):
        a = k * math.pi / 2 + 0.35
        along = Vector((math.cos(a), 0, math.sin(a)))
        side = Vector((-math.sin(a), 0, math.cos(a)))
        parts.append(box(hub + along * 0.32, (0.62, 0.025, 0.035), material=mat(C["blade"], 0.5), rot=(0, -a, 0)))
        parts.append(box(hub + along * 0.38 + side * 0.07, (0.42, 0.015, 0.12),
                         material=mat(C["sail"] if k % 2 == 0 else C["blade"], 0.6), rot=(0, -a, 0)))
    return parts


def hive():
    parts = [sphere((0, 0, 0.26), 0.22, sub=2, material=mat(C["hive_dark"], 0.6), smooth=True)]
    for i, (r, z) in enumerate([(0.24, 0.06), (0.23, 0.17), (0.2, 0.28), (0.15, 0.38), (0.08, 0.45)]):
        parts.append(torus((0, 0, z), r, 0.065, mat(C["hive"] if i % 2 == 0 else C["hive_dark"], 0.55)))
    parts.append(sphere((0, 0.26, 0.08), 0.06, scale=(1, 0.4, 0.8), sub=2, material=mat(C["bee"], 0.5), smooth=True))
    for (x, y, z) in [(0.32, 0.12, 0.42), (-0.28, 0.2, 0.55)]:
        parts.append(sphere((x, y, z), 0.045, scale=(1.3, 1, 1), sub=2, material=mat(C["hive"], 0.5), smooth=True))
        parts.append(sphere((x, y, z + 0.04), 0.035, scale=(0.6, 1, 0.3), sub=2,
                            material=mat(C["white"], 0.3, emit=0.3), smooth=True))
    return parts


def lantern_house():
    parts = [
        box((0, 0, 0.22), (0.36, 0.36, 0.44), material=mat(C["lamp_wall"], 0.6), bev=0.03),
        box((0, 0.185, 0.14), (0.12, 0.02, 0.22), material=mat(C["door"], 0.6)),
        box((0, 0, 0.58), (0.26, 0.26, 0.24), material=mat(C["lamp"], 0.3, emit=3.0), bev=0.02),
        cone((0, 0, 0.84), 0.28, 0.0, 0.3, verts=4, material=mat(C["lamp_roof"], 0.5), rot=(0, 0, math.pi / 4)),
        sphere((0, 0, 1.01), 0.05, sub=2, material=mat(C["gold"], 0.4), smooth=True),
    ]
    for (x, y) in [(-0.13, -0.13), (0.13, -0.13), (0.13, 0.13), (-0.13, 0.13)]:
        parts.append(box((x, y, 0.58), (0.04, 0.04, 0.26), material=mat(C["lamp_roof"], 0.5)))
    return parts


def observatory():
    parts = [
        cyl((0, 0, 0.17), 0.34, 0.34, verts=16, material=mat(C["dome_base"], 0.6), smooth=True),
        box((0, 0.33, 0.13), (0.14, 0.04, 0.24), material=mat(C["door"], 0.6)),
    ]
    bpy.ops.mesh.primitive_uv_sphere_add(segments=20, ring_count=12, radius=0.33, location=(0, 0, 0.34))
    dome = bpy.context.active_object
    bm = bmesh.new()
    bm.from_mesh(dome.data)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -1e-4], context="VERTS")
    bm.to_mesh(dome.data)
    bm.free()
    parts.append(finish(dome, mat(C["dome"], 0.35), smooth=True))
    parts.append(box((0, 0.1, 0.55), (0.13, 0.42, 0.1), material=mat(C["slit"], 0.5), rot=(math.radians(-40), 0, 0)))
    parts.append(cyl((0, 0.26, 0.68), 0.075, 0.5, verts=14, material=mat(C["gold"], 0.35), smooth=True,
                     rot=(math.radians(-58), 0, 0)))
    parts.append(torus((0, 0.47, 0.81), 0.08, 0.025, mat(C["scope"], 0.35)))
    parts[-1].rotation_euler = (math.radians(-58), 0, 0)
    return parts


def person(shirt, hair):
    return [
        cone((0, 0, 0.11), 0.11, 0.07, 0.22, verts=10, material=mat(shirt, 0.6), smooth=True),
        sphere((0, 0, 0.32), 0.12, sub=3, material=mat(C["skin"], 0.6), smooth=True),
        sphere((0, -0.025, 0.355), 0.127, scale=(1, 1, 0.78), sub=3, material=mat(hair, 0.6), smooth=True),
        sphere((-0.045, 0.11, 0.32), 0.017, sub=2, material=mat(C["eye"], 0.3), smooth=True),
        sphere((0.045, 0.11, 0.32), 0.017, sub=2, material=mat(C["eye"], 0.3), smooth=True),
    ]


def stone(x, y):
    return cyl((x, y, 0.01), 0.09, 0.04, verts=8, material=mat(C["stone"], 0.8), scale=(1.2, 1, 1))


def signpost():
    return [
        cyl((0, 0, 0.32), 0.04, 0.64, verts=8, material=mat(C["post"], 0.7)),
        box((0.08, 0.02, 0.52), (0.36, 0.04, 0.11), material=mat(C["sign"], 0.6), bev=0.01),
        box((-0.08, 0.02, 0.36), (0.32, 0.04, 0.1), material=mat(C["sign"], 0.6), rot=(0, 0, 0.0), bev=0.01),
        cone((0.29, 0.02, 0.52), 0.08, 0.0, 0.06, verts=3, material=mat(C["sign"], 0.6), rot=(0, math.pi / 2, 0)),
    ]


def make_garden():
    face = FACE_CAMERA - YAW  # фасады — к камере

    def put(parts, x, y, turn=0.0, s=1.0):
        return group(parts, loc=(x, DECK_Y + y, TOP), rot=face + turn, scale=s)

    put(windmill(), -0.5, -1.0, s=1.05)
    put(observatory(), 0.55, -1.2)
    put(tree(1.0), 0.0, -1.55)
    put(pine(1.0), -0.88, -0.3)
    put(tree(0.8), 0.92, -0.35)
    put(house(C["roof"]), 0.32, -0.32, turn=0.15)
    put(house(C["roof2"]), -0.52, 0.42, turn=-0.1)
    put(lantern_house(), 0.62, 0.55)
    put(hive(), -0.05, 0.98)
    put(bush(), 0.72, 1.12)
    put(bush(), -0.78, 1.0, s=0.85)
    put(bush(), -0.95, -1.05, s=0.8)
    for (x, y, p) in [(0.28, 1.5, C["petal1"]), (-0.32, 1.5, C["petal2"]), (0.02, 0.32, C["petal3"]),
                      (0.98, 0.15, C["petal4"]), (-0.98, 0.2, C["petal1"]), (0.15, -0.95, C["petal2"]),
                      (-0.2, -1.42, C["petal3"]), (0.45, 1.25, C["petal2"]), (-0.55, 1.35, C["petal3"])]:
        put(flower(p, 1.2), x, y)
    for (x, y) in [(0.05, 0.05), (-0.12, 0.2), (-0.25, 0.32), (0.18, -0.08)]:
        put([stone(0, 0)], x, y)
    put(person(C["shirt1"], C["hair1"]), 0.12, 0.62, turn=0.4)
    put(person(C["shirt2"], C["hair2"]), 0.2, -0.82, turn=-0.5)
    put(person(C["shirt3"], C["hair3"]), -0.62, 0.95, turn=0.2)


# --- облака и птицы -------------------------------------------------------------------------

def cloud(puffs, base=-0.12):
    """Облако из шаров с плоским дном: всё ниже base срезано."""
    parts = []
    for (x, y, z, r) in puffs:
        bpy.ops.mesh.primitive_uv_sphere_add(segments=40, ring_count=20, radius=r, location=(0, 0, 0))
        o = bpy.context.active_object
        for v in o.data.vertices:
            v.co = (v.co.x + x, v.co.y + y, max(v.co.z + z, base))
        parts.append(finish(o, mat(C["cloud"], 0.95), smooth=True))
    return parts


# Шары (x вдоль экрана, y вглубь, z вверх, радиус).
CLOUDS = [
    [(-0.78, 0, 0.0, 0.4), (-0.3, 0.05, 0.2, 0.55), (0.32, 0, 0.26, 0.6), (0.88, 0.05, 0.02, 0.42), (0.0, -0.3, 0.0, 0.48)],
    [(-0.48, 0, 0.02, 0.36), (0.04, 0, 0.18, 0.5), (0.55, 0, 0.0, 0.36)],
    [(-1.0, 0, -0.02, 0.3), (-0.55, 0, 0.12, 0.45), (0.0, 0.05, 0.3, 0.58), (0.6, 0, 0.12, 0.46), (1.05, 0, -0.04, 0.3)],
]


def bird():
    parts = [
        sphere((0, 0, 0), 0.2, scale=(1, 1.05, 0.95), sub=3, material=mat(C["bird"], 0.5), smooth=True),
        sphere((0, 0.06, -0.06), 0.15, scale=(0.95, 0.9, 0.85), sub=3, material=mat(C["bird_belly"], 0.6), smooth=True),
        cone((0, 0.215, -0.01), 0.045, 0.0, 0.1, verts=8, material=mat(C["beak"], 0.5), rot=(-math.pi / 2, 0, 0), smooth=True),
        sphere((0, -0.2, 0.06), 0.1, scale=(0.75, 1, 0.22), sub=2, smooth=True,
               material=mat(C["bird_wing"], 0.5), rot=(0.45, 0, 0)),
        sphere((0, 0.02, 0.2), 0.05, scale=(0.5, 1, 1), sub=2, smooth=True, material=mat(C["bird_wing"], 0.5)),
    ]
    for side in (-1, 1):
        parts.append(sphere((side * 0.085, 0.165, 0.05), 0.038, sub=3, material=mat(C["eye"], 0.15), smooth=True))
        parts.append(sphere((side * 0.075, 0.195, 0.07), 0.013, sub=2, material=mat(C["white"], 0.2, emit=0.6), smooth=True))
        parts.append(sphere((side * 0.3, -0.03, 0.04), 0.15, scale=(1, 0.62, 0.17), sub=3, smooth=True,
                            material=mat(C["bird_wing"], 0.5), rot=(0, side * -0.3, side * 0.2)))
    return parts


# --- свет, камера, рендер ------------------------------------------------------------------

def setup_world():
    sun_data = bpy.data.lights.new("sun", type="SUN")
    sun_data.energy = 3.6
    sun_data.angle = math.radians(10)
    sun = bpy.data.objects.new("sun", sun_data)
    scene.collection.objects.link(sun)
    right = Vector((math.cos(CAM_TURN), math.sin(CAM_TURN), 0))
    back = Vector((math.sin(CAM_TURN), -math.cos(CAM_TURN), 0))  # от сцены к камере
    to_sun = (-0.55 * right + 0.35 * back + Vector((0, 0, 0.85))).normalized()
    sun.rotation_euler = to_sun.to_track_quat("Z", "Y").to_euler()

    world = bpy.data.worlds.new("world")
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs["Color"].default_value = (*hex_rgb("#CDEBFF"), 1)
    bg.inputs["Strength"].default_value = 0.9


def make_camera():
    data = bpy.data.cameras.new("cam")
    data.type = "ORTHO"
    cam = bpy.data.objects.new("cam", data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    return cam


def aim_camera(cam, pitch=CAM_PITCH):
    """Ставит камеру на исходную точку; fit_camera потом сдвигает её к предмету."""
    d = 30
    cam.location = (math.sin(pitch) * math.sin(CAM_TURN) * d,
                    -math.sin(pitch) * math.cos(CAM_TURN) * d, math.cos(pitch) * d)
    cam.rotation_euler = (pitch, 0, CAM_TURN)


def fit_camera(cam, margin=0.05):
    """Сдвигает камеру и подбирает масштаб так, чтобы всё видимое влезло в кадр."""
    bpy.context.view_layer.update()
    dg = bpy.context.evaluated_depsgraph_get()
    inv = cam.matrix_world.inverted()
    xs, ys = [], []
    for o in scene.objects:
        if o.type not in {"MESH", "CURVE"}:
            continue
        ev = o.evaluated_get(dg)
        me = ev.to_mesh()
        for v in me.vertices:
            p = inv @ (ev.matrix_world @ v.co)
            xs.append(p.x)
            ys.append(p.y)
        ev.to_mesh_clear()
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    dx, dy = max(xs) - min(xs), max(ys) - min(ys)
    basis = cam.matrix_world.to_3x3()
    cam.location = cam.location + basis @ Vector((cx, cy, 0))
    fit = max(dx, dy * W / H) if W >= H else max(dy, dx * H / W)
    cam.data.ortho_scale = fit * (1 + 2 * margin)


def setup_render(path):
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = SAMPLES
    scene.cycles.use_denoising = True
    scene.cycles.max_bounces = 4
    scene.render.film_transparent = True
    scene.render.resolution_x = W
    scene.render.resolution_y = H
    scene.render.resolution_percentage = 100
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "None"
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.filepath = path
    if OUTLINE:
        scene.render.use_freestyle = True
        scene.render.line_thickness_mode = "ABSOLUTE"
        scene.render.line_thickness = float(OPTS.get("line", max(1.2, W / 360)))
        settings = bpy.context.view_layer.freestyle_settings
        lineset = settings.linesets[0] if len(settings.linesets) else settings.linesets.new("outline")
        if lineset.linestyle is None:
            lineset.linestyle = bpy.data.linestyles.new("outline")
        lineset.select_by_visibility = True
        lineset.select_by_edge_types = True
        lineset.select_silhouette = True
        lineset.select_border = True
        lineset.select_crease = False
        lineset.select_external_contour = True
        lineset.linestyle.color = hex_rgb("#1E2650")
        lineset.linestyle.thickness = scene.render.line_thickness


def render(path):
    setup_render(path)
    bpy.ops.render.render(write_still=True)
    print("RENDERED", path)


def clear_created():
    for o in reversed(CREATED):
        bpy.data.objects.remove(o, do_unlink=True)
    CREATED.clear()


# --- режимы -----------------------------------------------------------------------------------

ICONS = {
    "flower": flower_patch,
    "bush": bush,
    "hive": hive,
    "mill": windmill,
    "lantern": lantern_house,
    "observatory": observatory,
    "memory": lambda: tree(1.0, crown=(C["memory"], C["memory2"])),
    "route": signpost,
}

setup_world()
camera = make_camera()
aim_camera(camera)

if MODE == "scene":
    make_whale()
    make_deck()
    make_garden()
    whale_root = bpy.data.objects.new("whale_root", None)
    scene.collection.objects.link(whale_root)
    for o in CREATED:
        if o.parent is None:
            o.parent = whale_root
    whale_root.rotation_euler = (0, 0, YAW)
    fit_camera(camera)
    render(OUT)
elif MODE == "icons":
    os.makedirs(OUT, exist_ok=True)
    only = OPTS.get("only", "").split(",") if "only" in OPTS else None
    for name, build in list(ICONS.items()) + [("bird", None)]:
        if only and name not in only:
            continue
        aim_camera(camera)
        if build:
            group(build(), rot=FACE_CAMERA)
            tile()
        else:
            group(bird(), rot=FACE_CAMERA + 0.5)
        fit_camera(camera, margin=0.04)
        render(os.path.join(OUT, f"{name}.png"))
        clear_created()
elif MODE == "clouds":
    os.makedirs(OUT, exist_ok=True)
    for i, puffs in enumerate(CLOUDS):
        aim_camera(camera, pitch=math.radians(78))
        group(cloud(puffs), rot=CAM_TURN)
        fit_camera(camera, margin=0.03)
        render(os.path.join(OUT, f"cloud{i}.png"))
        clear_created()
else:
    raise SystemExit(f"неизвестный режим: {MODE}")

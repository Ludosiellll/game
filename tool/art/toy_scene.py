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
    --only=a,b     для icons: только перечисленные иконки (flower, bird, …);
    --stage=1..5   возраст кита: китёнок, юный, взрослый, древний, божество (2);
    --ortho=N      фиксированный масштаб камеры (единиц сцены по большей стороне) —
                   чтобы сравнивать размеры разных возрастов;
    --pitch=ГРАД   наклон камеры от вертикали (55; 70–80 — почти сбоку, для сцены боя);
    --tilt=ГРАД    кит задирает нос вверх на столько градусов.

Режим boss рендерит босса «Буревестник Безмолвия» (ПУТЬ — файл .png).
"""

import math
import os
import sys

import json

import bmesh
import bpy
from bpy_extras.object_utils import world_to_camera_view
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
TILT = math.radians(float(OPTS.get("tilt", 0)))
OUTLINE = "outline" in OPTS
FLAT = "flat" in OPTS
ORTHO = float(OPTS["ortho"]) if "ortho" in OPTS else None
_DEFAULT_SIZE = {"scene": (900, 1125), "icons": (256, 256), "clouds": (512, 320), "boss": (900, 900),
                 "sprites": (1024, 1024), "items": (640, 640), "birds": (256, 256), "bosses": (768, 768)}[MODE]
W, H = tuple(int(s) for s in OPTS["size"].split("x")) if "size" in OPTS else _DEFAULT_SIZE

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
# Разрешение — сразу: от его пропорций зависит проекция точек в кадр (world_to_camera_view).
scene.render.resolution_x = W
scene.render.resolution_y = H
scene.render.resolution_percentage = 100

# Камера: ортографическая «изометрия» (наклон 55° от вертикали, поворот −38°).
CAM_PITCH, CAM_TURN = math.radians(float(OPTS.get("pitch", 55))), math.radians(-38)
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
    "tent": "#FFF1D6", "crate": "#C98B4E", "marble": "#FFF8EE", "roof3": "#4FA3FF",
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


# --- кит: пять возрастов -----------------------------------------------------------------

# Форма тела (голова, хвост, ширина, высота), масштаб в мире, цвета, размер глаз и плавников,
# палуба сада (полуоси и сдвиг) и особые черты возраста.
STAGES = {
    1: dict(name="calf", head=1.85, tail=2.0, w=1.75, h=1.6, scale=0.75,
            body="#5DB4FF", belly="#EAF8FF", fin="#45A2FF", eye=1.3, fins=0.62,
            deck=(0.82, 1.0, -0.3), traits=()),
    2: dict(name="young", head=2.3, tail=3.0, w=1.65, h=1.45, scale=0.85,
            body="#3D8BFF", belly="#E3F4FF", fin="#2F6FE0", eye=1.0, fins=0.72,
            deck=(1.18, 1.8, -0.2), traits=()),
    3: dict(name="adult", head=2.5, tail=3.7, w=1.7, h=1.5, scale=1.0,
            body="#2F6FE3", belly="#DDEFFF", fin="#2558C4", eye=0.92, fins=0.9,
            deck=(1.25, 2.15, -0.35), traits=("crystals", "scar")),
    4: dict(name="ancient", head=2.7, tail=4.3, w=1.85, h=1.6, scale=1.2,
            body="#2C5D93", belly="#CFE3EE", fin="#244C7D", eye=0.84, fins=1.05,
            deck=(1.35, 2.5, -0.4),
            traits=("crystals", "moss", "runes", "beard", "whiskers", "lids", "barnacles")),
    5: dict(name="deity", head=2.8, tail=4.5, w=1.9, h=1.65, scale=1.4,
            body="#EEF2FF", belly="#FFF1D6", fin="#9FF3FF", eye=0.86, fins=1.15,
            deck=(1.4, 2.6, -0.4), traits=("crystals", "runes", "halo", "aurora", "whiskers", "stars")),
}
STAGE = int(OPTS.get("stage", 2))
ST = STAGES[STAGE]
HEAD_L, TAIL_L, BODY_W, BODY_H = ST["head"], ST["tail"], ST["w"], ST["h"]
TRAITS = ST["traits"]


def aurora_mat():
    """Плавники божества: свечение от бирюзового у основания к сиреневому на концах."""
    if "aurora" in _materials:
        return _materials["aurora"]
    m = bpy.data.materials.new("aurora")
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    coord = nt.nodes.new("ShaderNodeTexCoord")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    absx = nt.nodes.new("ShaderNodeMath")
    absx.operation = "ABSOLUTE"
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.elements[0].position = 0.15
    ramp.color_ramp.elements[0].color = (*hex_rgb("#5CFFE4"), 1)
    ramp.color_ramp.elements[1].position = 1.0
    ramp.color_ramp.elements[1].color = (*hex_rgb("#B57CFF"), 1)
    nt.links.new(coord.outputs["Object"], sep.inputs["Vector"])
    nt.links.new(sep.outputs["X"], absx.inputs[0])
    nt.links.new(absx.outputs[0], ramp.inputs["Fac"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(ramp.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 2.0
    bsdf.inputs["Roughness"].default_value = 0.3
    _materials["aurora"] = m
    return m


def gold(emit=0.6):
    return mat(C["gold"], 0.3, emit=emit)


# Части кита для спрайтов: какие объекты к какому слою и где опорные точки.
PARTS = {"fin": [], "fluke": [], "eye": [], "eye_hl": [], "smile": [], "halo": [], "star": []}
EYES = []  # (точка, нормаль, вверх, вправо, размер)
ANCHORS = {}


def part(role, obj):
    PARTS[role].append(obj)
    return obj


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
            yy, lift = y * TAIL_L, 0.55 * t ** 2.2 * TAIL_L / 3.0
        k = rr / r if r > 1e-9 else 0.0
        v.co = (x * k * BODY_W, yy, z * k * BODY_H + lift)
    body.data.update()
    finish(body, two_tone(ST["body"], ST["belly"], edge=-0.42, soft=0.015, tilt=0.1, rough=0.42), smooth=not FLAT)

    tree = BVHTree.FromPolygons([v.co.copy() for v in body.data.vertices],
                                [tuple(p.vertices) for p in body.data.polygons])

    def surface(origin, direction):
        loc, normal, _, _ = tree.ray_cast(Vector(origin), Vector(direction).normalized())
        return loc, normal.normalized()

    def head_dir(az, el):
        az, el = math.radians(az), math.radians(el)
        return (math.sin(az) * math.cos(el), math.cos(az) * math.cos(el), math.sin(el))

    def frame(normal):
        """Касательный базис в точке поверхности: (вверх, вправо)."""
        up = normal.cross(Vector((0, 0, 1)).cross(normal))
        up = up.normalized() if up.length > 1e-6 else Vector((0, 1, 0))
        return up, up.cross(normal).normalized()

    def on_surface(obj, loc, normal, size, lift=0.0, spin=0.0):
        """Ставит объект на поверхность: локальная z — по нормали, y — вверх."""
        q = normal.to_track_quat("Z", "Y")
        obj.matrix_world = (Matrix.Translation(loc + normal * lift) @ q.to_matrix().to_4x4()
                            @ Matrix.Rotation(spin, 4, "Z") @ Matrix.Diagonal((*size, 1)))

    def tube(name, points, radius, material):
        curve = bpy.data.curves.new(name, type="CURVE")
        curve.dimensions = "3D"
        curve.bevel_depth = radius
        curve.bevel_resolution = 3
        curve.use_fill_caps = True
        spline = curve.splines.new("POLY")
        spline.points.add(len(points) - 1)
        for p, co in zip(spline.points, points):
            p.co = (*co, 1)
        obj = bpy.data.objects.new(name, curve)
        bpy.context.collection.objects.link(obj)
        obj.data.materials.append(material)
        CREATED.append(obj)
        return obj

    def crystal(loc, normal, r, length, material, tilt=0.0):
        parts = [cyl((0, 0, length * 0.35), r, length * 0.7, verts=6, material=material),
                 cone((0, 0, length * 0.85), r, 0.0, length * 0.3, verts=6, material=material)]
        g = group(parts)
        q = (normal + Vector((0, 0, tilt))).normalized().to_track_quat("Z", "Y")
        g.matrix_world = Matrix.Translation(loc - normal * 0.02) @ q.to_matrix().to_4x4()
        return g

    head = (0.0, 0.7, 0.3)
    ek = ST["eye"]
    eye_color = "#2B2363" if STAGE == 5 else C["eye"]
    for side in (-1, 1):
        loc, n = surface(head, head_dir(side * 30, 4))
        up, right = frame(n)
        eye = part("eye", sphere((0, 0, 0), 1, material=mat(eye_color, 0.15), smooth=True, uv=True))
        EYES.append((loc.copy(), n.copy(), up.copy(), right.copy(), ek))
        on_surface(eye, loc, n, (0.2 * ek, 0.27 * ek, 0.1 * ek))
        hl_up = -0.02 if "lids" in TRAITS else 0.09
        hl = part("eye_hl", sphere((0, 0, 0), 1, material=mat(C["white"], 0.2, emit=0.6), smooth=True, uv=True))
        on_surface(hl, loc + (up * hl_up - right * 0.06 * side) * ek, n, (0.07 * ek, 0.08 * ek, 0.04), lift=0.08 * ek)
        hl2 = part("eye_hl", sphere((0, 0, 0), 1, material=mat(C["white"], 0.2, emit=0.6), smooth=True, uv=True))
        on_surface(hl2, loc + (-up * 0.1 + right * 0.06 * side) * ek, n, (0.035 * ek, 0.035 * ek, 0.02), lift=0.08 * ek)
        if "lids" in TRAITS:  # тяжёлые мудрые веки
            lid = sphere((0, 0, 0), 1, material=mat(ST["body"], 0.45), smooth=True, uv=True)
            on_surface(lid, loc + up * 0.13 * ek, n, (0.235 * ek, 0.17 * ek, 0.118 * ek))
        loc, n = surface(head, head_dir(side * 50, -8))
        blush = sphere((0, 0, 0), 1, material=mat(C["blush"], 0.8), smooth=True, uv=True)
        on_surface(blush, loc, n, (0.17 * ek, 0.11 * ek, 0.03))

    # Улыбка: дуга точек на поверхности, обведённая трубкой.
    pts = []
    for i in range(11):
        a = -15 + 30 * i / 10
        el = -6 - 7 * (1 - (a / 15) ** 2)
        loc, n = surface(head, head_dir(a, el))
        pts.append(loc + n * 0.02)
    part("smile", tube("smile", pts, 0.035, mat(C["mouth"], 0.3)))
    loc, n = surface(head, head_dir(0, -12))
    ANCHORS["mouth"] = (loc.copy(), n.copy())

    fin_mat = aurora_mat() if "aurora" in TRAITS else mat(ST["fin"], 0.45)
    fs = ST["fins"] / 0.72
    # Боковые плавники: плоские «лопатки» вниз-наружу-назад.
    for side in (-1, 1):
        root, _ = surface((0, 0.9, -0.1), (side, 0.05, -0.6))
        span = Vector((side * 0.85, -0.45, -0.5)).normalized()
        across = Vector((0, 0, 1)).cross(span).normalized()
        flat_n = span.cross(across).normalized()
        rot = Matrix((span, across, flat_n)).transposed()
        fin = part("fin", sphere((0, 0, 0), 1, material=fin_mat, smooth=not FLAT, uv=True))
        ANCHORS[f"fin{side}"] = root.copy()
        fin.matrix_world = (Matrix.Translation(root + span * 0.55 * fs) @ rot.to_4x4()
                            @ Matrix.Diagonal((0.72 * fs, 0.34 * fs ** 0.5, 0.09, 1)))

    # Хвостовой плавник: два лепестка с выемкой посередине.
    right = [(0.0, 0.08), (0.3, -0.02), (0.6, -0.15), (0.86, -0.36), (1.0, -0.64),
             (0.8, -0.6), (0.55, -0.52), (0.3, -0.52), (0.12, -0.6), (0.0, -0.5)]
    left = [(-x, y) for x, y in reversed(right[1:-1])]
    k = 1.25 * fs ** 0.6
    fluke = part("fluke", prism("fluke", [(x * k, y * k) for x, y in right + left], 0.12, fin_mat, smooth=not FLAT))
    ANCHORS["fluke"] = Vector((0, -TAIL_L + 0.25, 0.52 * TAIL_L / 3.0))
    bevel(fluke, 0.04, segments=3)
    fluke.location = (0, -TAIL_L + 0.25, 0.52 * TAIL_L / 3.0)
    fluke.rotation_euler = (math.radians(18), 0, 0)

    crystal_mat = mat("#FFC93C", 0.2, emit=1.6) if STAGE == 5 else mat("#8AF4FF", 0.2, emit=1.4)
    if "crystals" in TRAITS:  # кристаллы памяти на лбу; у божества они становятся короной
        for (az, el, r, ln) in [(-26, 30, 0.1, 0.5), (-9, 36, 0.13, 0.68), (9, 34, 0.12, 0.6),
                                (26, 28, 0.1, 0.46), (0, 26, 0.08, 0.36)]:
            loc, n = surface((0, 1.0, 0.3), head_dir(az, el))
            crystal(loc, n, r, ln, crystal_mat, tilt=0.4)
    if "scar" in TRAITS:  # шрам-молния на боку, который виден камере
        zig = []
        for i, (y, z) in enumerate([(0.9, 0.35), (0.55, 0.1), (0.3, 0.28), (-0.1, 0.02), (-0.4, 0.2)]):
            loc, n = surface((0, y, z), (-1, 0, 0))
            zig.append(loc + n * 0.015)
        tube("scar", zig, 0.035, mat("#CFE8FF", 0.5))
    if "beard" in TRAITS:  # кристальная «борода» под подбородком
        for (az, el, r, ln) in [(-22, -42, 0.07, 0.34), (-8, -48, 0.09, 0.46), (8, -47, 0.08, 0.42), (22, -40, 0.07, 0.3)]:
            loc, n = surface((0, 1.2, 0.0), head_dir(az, el))
            crystal(loc, n, r, ln, crystal_mat, tilt=-0.2)
    if "barnacles" in TRAITS:  # ракушки на щеках
        for (az, el, r) in [(48, 18, 0.08), (58, 26, 0.06), (66, 14, 0.07), (-50, 20, 0.08), (-60, 12, 0.06),
                            (-44, 30, 0.05), (40, 32, 0.05)]:
            loc, n = surface(head, head_dir(az, el))
            b = sphere((0, 0, 0), 1, material=mat("#ECE6D8", 0.8), sub=2, smooth=True)
            on_surface(b, loc, n, (r, r, r * 0.6))
    if "moss" in TRAITS:  # мох по бокам и на хвосте
        spots = [((0, 1.2, 0.4), (1, 0, 0.7)), ((0, 0.1, 0.4), (-1, 0, 0.6)), ((0, -0.9, 0.4), (1, 0, 0.5)),
                 ((0, -1.9, 0.5), (-1, 0, 0.5)), ((0, -2.8, 0.6), (0, 0, 1)), ((0, 0.6, 0.3), (-1, 0, 0.9)),
                 ((0, -1.4, 0.4), (1, 0, 0.9))]
        for i, (origin, direction) in enumerate(spots):
            loc, n = surface(origin, direction)
            m = sphere((0, 0, 0), 1, material=mat("#6FBF5F" if i % 2 else "#8BD46A", 0.9), sub=2)
            on_surface(m, loc, n, (0.38, 0.26, 0.07), spin=0.5 * i)
    if "runes" in TRAITS:  # руны вдоль боков
        glow = 4.0 if STAGE == 5 else 0.9
        big = 1.5 if STAGE == 5 else 1.0
        for side in (-1, 1):
            line = []
            for i in range(8):
                y = 1.5 - i * 0.5
                loc, n = surface((0, y, 0.05), (side, 0, 0))
                line.append(loc + n * 0.012)
                if i % 2 == 0:
                    d = box((0, 0, 0), (1, 1, 1), material=gold(glow))
                    on_surface(d, loc, n, (0.11 * big, 0.11 * big, 0.035), spin=math.pi / 4)
                else:
                    d = sphere((0, 0, 0), 1, material=gold(glow), sub=2, smooth=True)
                    on_surface(d, loc, n, (0.06 * big, 0.06 * big, 0.025))
            tube("rune_line", line, 0.012 * big, gold(glow))
    if "whiskers" in TRAITS:  # длинные усы, как у древних рыб
        color = mat("#FFF3C4", 0.4, emit=1.5) if STAGE == 5 else mat("#FFF0C2", 0.5, emit=0.3)
        for side in (-1, 1):
            loc, n = surface(head, head_dir(side * 20, -4))
            pts = [loc]
            for i, (dx, dy, dz) in enumerate([(0.25, -0.05, -0.15), (0.5, -0.35, -0.38), (0.62, -0.8, -0.42),
                                               (0.58, -1.25, -0.6), (0.66, -1.65, -0.62)]):
                pts.append(loc + Vector((side * dx, dy, dz)))
            tube("whisker", pts, 0.045, color)
    if "gem" in TRAITS:  # сияющий камень на лбу
        loc, n = surface((0, 1.0, 0.3), head_dir(0, 22))
        g = group([cone((0, 0, 0.1), 0.14, 0.0, 0.2, verts=4, material=mat("#FFF6D0", 0.2, emit=3.5)),
                   cone((0, 0, -0.1), 0.0, 0.14, 0.2, verts=4, material=mat("#FFF6D0", 0.2, emit=3.5))])
        q = n.to_track_quat("Z", "Y")
        g.matrix_world = Matrix.Translation(loc + n * 0.12) @ q.to_matrix().to_4x4() @ Matrix.Diagonal((1, 1.3, 0.6, 1))
    if "halo" in TRAITS:  # нимб над головой
        halo = part("halo", torus((0, DECK_Y + DECK_B + 0.35, TOP + 1.15), 0.75, 0.07, gold(4.0)))
        halo.rotation_euler = (math.radians(18), 0, 0)
    if "stars" in TRAITS:  # искры вокруг
        for i in range(16):
            a = i * 2.4
            r = 2.2 + (i % 3) * 0.5
            part("star", sphere((math.cos(a) * r * 0.7, math.sin(a) * r * 1.3 - 0.6, 0.6 + (i % 5) * 0.45),
                        0.06 + (i % 3) * 0.025, sub=2, material=mat("#FFF6C2", 0.3, emit=6.0), smooth=True))


# --- палуба и сад -------------------------------------------------------------------------

DECK_A, DECK_B, DECK_Y = ST["deck"]  # полуоси овала и его сдвиг вдоль тела
TOP = BODY_H + 0.13  # верх травы


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


def tent():
    return [
        cone((0, 0, 0.25), 0.36, 0.0, 0.5, verts=6, material=mat(C["tent"], 0.7)),
        cone((0, 0.06, 0.12), 0.16, 0.0, 0.24, verts=3, material=mat(C["door"], 0.7)),
        cyl((0, 0, 0.58), 0.012, 0.18, verts=5, material=mat(C["post"], 0.7)),
        box((0.06, 0, 0.64), (0.12, 0.01, 0.07), material=mat(C["roof"], 0.6)),
    ]


def campfire():
    parts = [cyl((0, 0, 0.04), 0.03, 0.26, verts=6, material=mat(C["trunk"], 0.8), rot=(math.pi / 2, 0, k * math.pi / 3))
             for k in range(3)]
    parts.append(cone((0, 0, 0.15), 0.09, 0.0, 0.24, verts=8, material=mat("#FF8A2B", 0.5, emit=3.0), smooth=True))
    parts.append(cone((0, 0, 0.12), 0.05, 0.0, 0.15, verts=8, material=mat("#FFE45C", 0.5, emit=4.0), smooth=True))
    for k in range(6):
        a = k * math.pi / 3
        parts.append(sphere((math.cos(a) * 0.17, math.sin(a) * 0.17, 0.02), 0.045, sub=1, material=mat(C["stone"], 0.8)))
    return parts


def crate():
    return [box((0, 0, 0.12), (0.24, 0.24, 0.24), material=mat(C["crate"], 0.7), bev=0.02),
            box((0.02, 0.0, 0.29), (0.18, 0.18, 0.1), material=mat(C["crate"], 0.7), rot=(0, 0, 0.3), bev=0.015)]


def house2(roof_color):
    """Двухэтажный дом."""
    return [
        box((0, 0, 0.38), (0.5, 0.44, 0.76), material=mat(C["wall"], 0.6), bev=0.03),
        group([gable_roof(0.66, 0.6, 0.34, mat(roof_color, 0.55))], loc=(0, 0, 0.75)),
        box((-0.1, 0.225, 0.14), (0.13, 0.03, 0.24), material=mat(C["door"], 0.6), bev=0.01),
        box((0.12, 0.225, 0.3), (0.11, 0.03, 0.11), material=mat(C["window"], 0.3, emit=1.2)),
        box((-0.1, 0.225, 0.56), (0.11, 0.03, 0.11), material=mat(C["window"], 0.3, emit=1.2)),
        box((0.12, 0.225, 0.56), (0.11, 0.03, 0.11), material=mat(C["window"], 0.3, emit=1.2)),
    ]


def lighthouse():
    parts = [cyl((0, 0, 0.15 + i * 0.3), 0.22 - i * 0.02, 0.3, verts=14, smooth=True,
                 material=mat(C["roof"] if i % 2 == 0 else C["wall"], 0.55)) for i in range(4)]
    parts += [
        cyl((0, 0, 1.31), 0.21, 0.05, verts=14, material=mat(C["slit"], 0.5)),
        cyl((0, 0, 1.43), 0.12, 0.2, verts=12, material=mat(C["lamp"], 0.3, emit=4.0)),
        cone((0, 0, 1.63), 0.17, 0.0, 0.22, verts=12, material=mat(C["roof"], 0.5)),
        box((0, 0.21, 0.14), (0.12, 0.04, 0.24), material=mat(C["door"], 0.6)),
    ]
    return parts


def tower(roof_color, h=1.0):
    return [
        cyl((0, 0, h / 2), 0.22, h, verts=14, material=mat(C["stone"], 0.7), smooth=True),
        cone((0, 0, h + 0.2), 0.29, 0.0, 0.42, verts=14, material=mat(roof_color, 0.5), smooth=True),
        box((0, 0.215, h * 0.62), (0.08, 0.03, 0.14), material=mat(C["window"], 0.3, emit=1.2)),
        box((0, 0.215, h * 0.3), (0.08, 0.03, 0.14), material=mat(C["window"], 0.3, emit=1.2)),
    ]


def bell_tower():
    """Колокольня с последним Колоколом Небесного города."""
    parts = [
        box((0, 0, 0.5), (0.38, 0.38, 1.0), material=mat(C["stone"], 0.7), bev=0.02),
        box((0, 0.195, 0.16), (0.13, 0.02, 0.26), material=mat(C["door"], 0.6)),
        box((0, 0, 1.38), (0.46, 0.46, 0.06), material=mat(C["stone"], 0.7)),
        cone((0, 0, 1.64), 0.34, 0.0, 0.46, verts=4, material=mat(C["lamp_roof"], 0.5), rot=(0, 0, math.pi / 4)),
        cone((0, 0, 1.13), 0.13, 0.05, 0.22, verts=16, material=gold(0.5), smooth=True),
        sphere((0, 0, 1.25), 0.055, sub=2, material=gold(0.5), smooth=True),
    ]
    for (x, y) in [(-0.16, -0.16), (0.16, -0.16), (0.16, 0.16), (-0.16, 0.16)]:
        parts.append(box((x, y, 1.18), (0.07, 0.07, 0.36), material=mat(C["stone"], 0.7)))
    return parts


def temple():
    parts = [box((0, 0, 0.05 + i * 0.1), (w, w, 0.1), material=mat(C["marble"], 0.5), bev=0.02)
             for i, w in enumerate([1.3, 1.12, 0.95])]
    for k in range(8):
        a = k * math.pi / 4
        parts.append(cyl((math.cos(a) * 0.36, math.sin(a) * 0.36, 0.6), 0.05, 0.6, verts=10,
                         material=mat(C["marble"], 0.5), smooth=True))
    parts.append(cyl((0, 0, 0.93), 0.46, 0.08, verts=28, material=mat(C["marble"], 0.5), smooth=True))
    bpy.ops.mesh.primitive_uv_sphere_add(segments=24, ring_count=12, radius=0.4, location=(0, 0, 0.97))
    dome = bpy.context.active_object
    bm = bmesh.new()
    bm.from_mesh(dome.data)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -1e-4], context="VERTS")
    bm.to_mesh(dome.data)
    bm.free()
    parts.append(finish(dome, gold(0.35), smooth=True))
    parts.append(cone((0, 0, 1.5), 0.06, 0.0, 0.3, verts=8, material=gold(0.8)))
    parts.append(sphere((0, 0, 0.55), 0.13, sub=2, material=mat("#8AF4FF", 0.2, emit=4.0)))
    return parts


def statue():
    """Золотая статуя кита: жители почитают своего кита."""
    return [
        box((0, 0, 0.12), (0.3, 0.3, 0.24), material=mat(C["marble"], 0.5), bev=0.02),
        sphere((0, 0, 0.42), 0.13, scale=(0.9, 1.5, 0.85), sub=3, material=gold(0.3), smooth=True),
        sphere((0, -0.22, 0.48), 0.07, scale=(1.6, 0.6, 0.25), sub=2, material=gold(0.3), smooth=True),
    ]


def lamp_post():
    return [cyl((0, 0, 0.22), 0.02, 0.44, verts=6, material=mat(C["post"], 0.6)),
            sphere((0, 0, 0.47), 0.055, sub=2, material=mat(C["lamp"], 0.3, emit=4.0), smooth=True)]


def big_tree(s=1.0, crown=None):
    crown = crown or (C["leaf"], C["leaf2"])
    return [
        cyl((0, 0, 0.3 * s), 0.1 * s, 0.6 * s, verts=7, material=mat(C["trunk"], 0.8)),
        sphere((0, 0, 0.8 * s), 0.45 * s, sub=1, material=mat(crown[0], 0.7)),
        sphere((0.3 * s, 0.1 * s, 0.95 * s), 0.3 * s, sub=1, material=mat(crown[1], 0.7)),
        sphere((-0.28 * s, -0.05 * s, 1.0 * s), 0.3 * s, sub=1, material=mat(crown[1], 0.7)),
        sphere((0.0, -0.1 * s, 1.2 * s), 0.3 * s, sub=1, material=mat(crown[0], 0.7)),
    ]


def floating_island(s=1.0):
    return [
        cone((0, 0, -0.32 * s), 0.0, 0.55 * s, 0.6 * s, verts=10,
             material=two_tone(C["soil"], C["soil_dark"], edge=-0.25, soft=0.01)),
        cyl((0, 0, 0.0), 0.58 * s, 0.08 * s, verts=24, material=mat(C["grass"], 0.75), smooth=True),
        group(tree(0.75 * s), loc=(0.1 * s, -0.05 * s, 0.04 * s)),
        group(flower(C["petal1"], 1.0 * s), loc=(-0.25 * s, 0.2 * s, 0.04 * s)),
    ]


# --- сад по возрастам -----------------------------------------------------------------------

def _person(i):
    return person([C["shirt1"], C["shirt2"], C["shirt3"]][i % 3], [C["hair1"], C["hair2"], C["hair3"]][i % 3])


# Постройка по ключу «вид:параметр». Спрайты рендерятся по ключам, поэтому
# один и тот же список задаёт и картинку сада, и места построек в игре.
ITEM_BUILDERS = {
    "flower": lambda a: flower(C[a], 1.2),
    "bush": lambda a: bush(),
    "hive": lambda a: hive(),
    "mill": lambda a: windmill(),
    "lantern": lambda a: lantern_house(),
    "observatory": lambda a: observatory(),
    "house": lambda a: house(C[a]),
    "house2": lambda a: house2(C[a]),
    "tree": lambda a: tree(1.0),
    "memtree": lambda a: tree(1.0, crown=(C["memory"], C["memory2"])),
    "pine": lambda a: pine(1.0),
    "bigtree": lambda a: big_tree(1.0),
    "membigtree": lambda a: big_tree(1.0, (C["memory"], C["memory2"])),
    "tent": lambda a: tent(),
    "campfire": lambda a: campfire(),
    "crate": lambda a: crate(),
    "person": lambda a: _person(int(a)),
    "stone": lambda a: [stone(0, 0)],
    "lighthouse": lambda a: lighthouse(),
    "belltower": lambda a: bell_tower(),
    "tower": lambda a: tower(C[a]),
    "temple": lambda a: temple(),
    "statue": lambda a: statue(),
    "lamppost": lambda a: lamp_post(),
}

# Постройки, которые появляются по мере покупки растений (id в игре = вид).
GENERATOR_KINDS = ("flower", "bush", "hive", "mill", "lantern", "observatory")


def item_parts(key):
    kind, _, arg = key.partition(":")
    return ITEM_BUILDERS[kind](arg)


def _flowers(spots):
    return [("flower:" + p, x, y, 1.0) for (x, y, p) in spots]


# Сад на спине: (ключ постройки, x, y, масштаб) в координатах палубы.
GARDEN = {
    1: [("tent", 0.05, -0.2, 1), ("campfire", 0.38, 0.25, 1), ("crate", -0.48, -0.02, 0.9),
        ("tree", -0.38, -0.58, 0.75), ("bush", 0.48, -0.52, 0.75),
        ("person:0", -0.15, 0.3, 1), ("person:2", 0.24, 0.55, 1)]
       + _flowers([(0.0, 0.7, "petal1"), (-0.38, 0.45, "petal2"), (0.58, -0.05, "petal3"),
                   (-0.6, 0.15, "petal4"), (0.32, -0.72, "petal1")]),
    2: [("mill", -0.5, -1.0, 1.05), ("tree", 0.55, -1.2, 0.9), ("tree", 0.0, -1.55, 1.0),
        ("pine", -0.88, -0.3, 1.0), ("tree", 0.92, -0.35, 0.8),
        ("house:roof", 0.32, -0.32, 1), ("house:roof2", -0.52, 0.42, 1), ("house:roof3", 0.62, 0.55, 0.9),
        ("hive", -0.05, 0.98, 1),
        ("bush", 0.72, 1.12, 1), ("bush", -0.78, 1.0, 0.85), ("bush", -0.95, -1.05, 0.8),
        ("person:0", 0.12, 0.62, 1), ("person:1", 0.2, -0.82, 1), ("person:2", -0.62, 0.95, 1),
        ("stone", 0.05, 0.05, 1), ("stone", -0.12, 0.2, 1), ("stone", -0.25, 0.32, 1), ("stone", 0.18, -0.08, 1)]
       + _flowers([(0.28, 1.5, "petal1"), (-0.32, 1.5, "petal2"), (0.02, 0.32, "petal3"),
                   (0.98, 0.15, "petal4"), (-0.98, 0.2, "petal1"), (0.15, -0.95, "petal2"),
                   (-0.2, -1.42, "petal3"), (0.45, 1.25, "petal2"), (-0.55, 1.35, "petal3")]),
    3: [("lighthouse", -0.55, -1.45, 1), ("mill", 0.5, -1.55, 1), ("observatory", 0.88, -0.55, 0.9),
        ("pine", -0.98, -0.7, 1), ("tree", 0.02, -1.95, 0.9),
        ("house:roof", 0.22, -0.8, 1), ("house:roof2", -0.55, -0.35, 1), ("house:roof3", 0.55, 0.22, 1),
        ("house:roof", -0.42, 0.55, 1), ("lantern", 0.0, 0.0, 0.95), ("hive", 0.12, 1.2, 1),
        ("bush", 0.78, 1.32, 1), ("bush", -0.78, 1.3, 0.9), ("bush", -1.02, 0.2, 0.8),
        ("person:0", 0.25, 0.75, 1), ("person:1", -0.15, -1.0, 1), ("person:2", -0.75, 0.95, 1),
        ("person:1", 0.75, -0.2, 1)]
       + _flowers([(0.3, 1.7, "petal1"), (-0.32, 1.72, "petal2"), (1.02, 0.65, "petal4"),
                   (-1.05, -0.2, "petal1"), (0.35, -1.25, "petal2"), (-0.3, 1.05, "petal3"),
                   (0.55, 1.6, "petal3"), (-0.6, 1.62, "petal1"), (0.95, -1.15, "petal2")]),
    4: [("belltower", 0.0, -1.6, 1), ("tower:lamp_roof", -0.88, -1.3, 1), ("tower:roof3", 0.88, -1.15, 1.1),
        ("mill", 0.15, -2.25, 0.9),
        ("house2:roof", -0.45, -0.78, 1), ("house:roof2", 0.42, -0.62, 1), ("house:roof3", -0.92, -0.2, 1),
        ("house2:roof2", 0.0, -0.12, 1), ("lantern", 0.88, -0.05, 1), ("house:roof2", -0.48, 0.48, 1),
        ("house2:roof3", 0.48, 0.55, 1), ("observatory", -0.12, 1.2, 0.9), ("hive", 0.42, 1.55, 0.9),
        ("bigtree", 0.98, 1.1, 1.15), ("pine", -1.0, 0.95, 1.1),
        ("lamppost", 0.25, 1.0, 1), ("lamppost", -0.42, 1.05, 1), ("lamppost", 0.3, -1.12, 1),
        ("bush", 0.55, 1.95, 0.9), ("bush", -0.62, 1.72, 0.9),
        ("person:0", 0.22, 0.2, 1), ("person:1", -0.2, -1.1, 1), ("person:2", -0.75, 1.3, 1),
        ("person:1", 0.7, 0.95, 1), ("person:0", -0.55, 0.0, 1)]
       + _flowers([(0.25, 2.15, "petal1"), (-0.3, 2.1, "petal2"), (1.12, 0.45, "petal4"),
                   (-1.15, 0.45, "petal1"), (0.6, -1.6, "petal2"), (-0.6, -1.65, "petal3")]),
    5: [("temple", 0.0, -0.1, 1.25), ("membigtree", 0.0, -1.75, 1.5),
        ("tower:gold", -0.92, -1.15, 1), ("tower:gold", 0.92, -1.05, 1),
        ("house:lamp_roof", -0.78, -0.42, 1), ("house2:gold", 0.8, -0.38, 1),
        ("house2:lamp_roof", -0.98, 0.32, 1), ("lantern", 0.98, 0.35, 1),
        ("observatory", -0.62, 0.95, 0.9), ("mill", 0.62, 1.0, 0.85), ("hive", -0.35, 1.55, 0.9),
        ("statue", 0.0, 1.2, 1.2),
        ("memtree", -1.1, -0.85, 0.8), ("memtree", 1.1, -0.75, 0.8),
        ("lamppost", 0.55, 0.42, 1), ("lamppost", -0.55, 0.42, 1),
        ("lamppost", 0.42, -0.78, 1), ("lamppost", -0.42, -0.8, 1),
        ("bush", 0.45, 1.75, 0.9), ("bush", -0.95, 1.2, 0.85),
        ("person:0", 0.25, 0.75, 1), ("person:1", -0.25, 0.72, 1), ("person:2", 0.0, 0.6, 1),
        ("person:1", 0.6, -1.3, 1), ("person:0", -0.55, -1.35, 1)]
       + _flowers([(0.3, 1.95, "petal1"), (-0.3, 1.98, "petal2"), (0.0, 2.1, "petal3"),
                   (1.15, 0.9, "petal4"), (-1.15, 0.9, "petal1"), (0.35, -2.2, "petal2"),
                   (-0.35, -2.2, "petal3"), (1.2, -0.2, "petal1"), (-1.2, -0.2, "petal2")]),
}

# Пороги появления: k-я постройка растения видна, когда куплено ≥ порога.
GENERATOR_THRESHOLDS = {
    "flower": [1, 3, 6, 10, 15, 25, 40, 60, 100, 150, 200],
    "bush": [1, 10, 25, 50, 100],
    "hive": [1, 25, 50],
    "mill": [1, 25, 50],
    "lantern": [1, 25, 50],
    "observatory": [1, 25, 50],
}


def item_scale():
    """Масштаб построек относительно кита: на гиганте помещается целый город."""
    return (ST["scale"] / STAGES[2]["scale"]) ** -0.65


def make_garden():
    face = FACE_CAMERA - YAW  # фасады — к камере
    k = item_scale()
    groups = []
    for (key, x, y, s) in GARDEN[STAGE]:
        g = group(item_parts(key), loc=(x, DECK_Y + y, TOP), rot=face, scale=s * k)
        g["item"] = key
        groups.append(g)
    return groups


# --- облака и птицы -------------------------------------------------------------------------

def cloud(puffs, base=-0.12, color=None):
    """Облако из шаров с плоским дном: всё ниже base срезано."""
    parts = []
    for (x, y, z, r) in puffs:
        bpy.ops.mesh.primitive_uv_sphere_add(segments=40, ring_count=20, radius=r, location=(0, 0, 0))
        o = bpy.context.active_object
        for v in o.data.vertices:
            v.co = (v.co.x + x, v.co.y + y, max(v.co.z + z, base))
        parts.append(finish(o, mat(color or C["cloud"], 0.95), smooth=True))
    return parts


# Шары (x вдоль экрана, y вглубь, z вверх, радиус).
CLOUDS = [
    [(-0.78, 0, 0.0, 0.4), (-0.3, 0.05, 0.2, 0.55), (0.32, 0, 0.26, 0.6), (0.88, 0.05, 0.02, 0.42), (0.0, -0.3, 0.0, 0.48)],
    [(-0.48, 0, 0.02, 0.36), (0.04, 0, 0.18, 0.5), (0.55, 0, 0.0, 0.36)],
    [(-1.0, 0, -0.02, 0.3), (-0.55, 0, 0.12, 0.45), (0.0, 0.05, 0.3, 0.58), (0.6, 0, 0.12, 0.46), (1.05, 0, -0.04, 0.3)],
]


def bird(wing=-0.3):
    """Круглая певчая птица; wing — угол крыльев (кадры взмаха: −0.95, −0.3, 0.35)."""
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
                            material=mat(C["bird_wing"], 0.5), rot=(0, side * wing, side * 0.2)))
    return parts


# --- боссы -------------------------------------------------------------------------------------

def lightning(scale=1.0):
    """Молния: плоская зигзагообразная фигура, стоит вертикально."""
    outline = [(0.0, 0.0), (0.3, 0.0), (0.16, -0.4), (0.36, -0.4), (-0.06, -1.05), (0.08, -0.56), (-0.12, -0.56)]
    bolt = prism("bolt", [(x * scale, y * scale) for x, y in outline], 0.06 * scale, mat("#FFF07A", 0.3, emit=8.0))
    bolt.rotation_euler = (math.pi / 2, 0, 0)
    return bolt


def storm_petrel():
    """Буревестник Безмолвия — страж Пояса бурь: грозовая птица из тёмных туч."""
    body, belly, wing, dark = "#3E4278", "#8A90D8", "#34386A", "#1B1D38"
    parts = [
        sphere((0, 0, 0), 1.0, scale=(1.0, 1.1, 0.95), sub=3, material=mat(body, 0.6), smooth=True),
        sphere((0, 0.38, -0.28), 0.7, scale=(0.95, 0.9, 0.85), sub=3, material=mat(belly, 0.7), smooth=True),
        cone((0, 1.1, 0.0), 0.2, 0.0, 0.5, verts=10, material=mat("#FFB23F", 0.45), smooth=True,
             rot=(-math.pi / 2 - 0.35, 0, 0)),
    ]
    for j, a in enumerate((-0.35, 0.0, 0.35)):
        parts.append(cone((a * 0.5, -0.1, 1.05), 0.12, 0.0, 0.5, verts=6, material=mat(dark, 0.5),
                          rot=(-0.5, a * 1.2, 0)))
    for side in (-1, 1):
        parts.append(sphere((side * 0.36, 0.84, 0.36), 0.22, scale=(1, 0.55, 0.85), sub=3,
                            material=mat("#FFE14A", 0.3, emit=7.0), smooth=True))
        parts.append(sphere((side * 0.32, 0.95, 0.33), 0.085, sub=2, material=mat(dark, 0.2), smooth=True))
        parts.append(box((side * 0.37, 0.9, 0.64), (0.44, 0.14, 0.11), material=mat(dark, 0.5),
                         rot=(0, -side * 0.5, 0), bev=0.03))
        # Крылья подняты и раскинуты: перья — сплюснутые шары, кончики — острые конусы.
        for i in range(5):  # крыло: веер перьев-пластин, нижние длиннее и темнее
            for row, (dz, dx, col) in enumerate([(0.0, 0.0, wing), (-0.32, 0.12, body)]):
                parts.append(sphere((side * (0.9 + i * 0.36 + dx), -0.05 * row, 0.4 + i * 0.27 + dz),
                                    0.6 - i * 0.06, scale=(1, 0.16, 0.5), sub=3, smooth=True,
                                    material=mat(col, 0.6), rot=(0, -side * (0.5 + row * 0.25), 0)))
        for j in range(4):
            parts.append(cone((side * (2.2 + j * 0.05), 0.02, 1.75 - j * 0.3), 0.17, 0.0, 0.66, verts=6,
                              material=mat(dark, 0.5), rot=(0, side * (1.05 + j * 0.12), 0)))
    for j, a in enumerate((-0.45, 0.0, 0.45)):
        parts.append(cone((a * 0.4, -1.15, 0.1 + abs(a) * 0.1), 0.18, 0.0, 0.7, verts=6, material=mat(wing, 0.6),
                          rot=(math.pi / 2 + 0.3, 0, a)))
    # Грозовая туча, из которой он поднимается.
    parts += cloud([(-0.9, -0.2, -0.9, 0.55), (0.0, 0.0, -1.0, 0.7), (0.9, -0.1, -0.9, 0.55),
                    (-1.6, -0.3, -1.05, 0.38), (1.6, -0.2, -1.05, 0.4)], base=-1.35, color="#565A8C")
    b1 = lightning(0.9)
    b1.location = (-1.9, 0.3, 0.2)
    b2 = lightning(0.7)
    b2.location = (1.75, 0.4, -0.1)
    parts += [b1, b2]
    return parts


def fog_boss():
    """Ворчливый Туман — страж Рассветного моря: пухлая серая туча, ворчит и моросит."""
    grey, dark = "#AEB3CC", "#3E4266"
    parts = cloud([(0, 0, 0.0, 1.0), (-0.95, 0.05, -0.25, 0.7), (0.95, 0.0, -0.22, 0.72),
                   (-0.45, -0.2, 0.55, 0.62), (0.5, -0.15, 0.6, 0.6), (0, 0.1, 0.78, 0.52)], base=-0.75, color=grey)
    for side in (-1, 1):
        parts.append(sphere((side * 0.33, 0.9, 0.2), 0.18, scale=(1, 0.5, 1.15), sub=3, smooth=True,
                            material=mat(C["white"], 0.3)))
        parts.append(sphere((side * 0.3, 0.99, 0.15), 0.085, sub=2, smooth=True, material=mat(dark, 0.2)))
        parts.append(sphere((side * 0.27, 1.04, 0.19), 0.025, sub=1, material=mat(C["white"], 0.2, emit=0.5)))
        parts.append(box((side * 0.34, 0.93, 0.45), (0.36, 0.09, 0.08), material=mat(dark, 0.5),
                         rot=(0, -side * 0.42, 0), bev=0.02))
        # Надутые щёки и кулачки-тучки по бокам.
        parts.append(sphere((side * 0.62, 0.8, -0.1), 0.14, scale=(1, 0.5, 0.8), sub=2, smooth=True,
                            material=mat("#C7CBE0", 0.8)))
        parts.append(sphere((side * 1.55, 0.25, 0.25), 0.3, sub=3, smooth=True, material=mat(grey, 0.9)))
    # Надутый рот уголками вниз.
    parts.append(sphere((0, 0.97, -0.17), 0.13, scale=(1, 0.4, 0.45), sub=2, smooth=True, material=mat(dark, 0.4)))
    for side in (-1, 1):
        parts.append(sphere((side * 0.13, 0.95, -0.22), 0.05, scale=(1, 0.4, 0.6), sub=2, smooth=True,
                            material=mat(dark, 0.4)))
    # Морось под тучей.
    for (x, y, z) in [(-0.6, 0.2, -1.05), (0.1, 0.3, -1.2), (0.7, 0.1, -1.0), (-0.2, 0.0, -1.45), (0.45, 0.25, -1.5)]:
        parts.append(cone((x, y, z + 0.06), 0.045, 0.0, 0.12, verts=8, smooth=True, material=mat("#8FD3FF", 0.2, emit=0.3)))
        parts.append(sphere((x, y, z), 0.047, sub=2, smooth=True, material=mat("#8FD3FF", 0.2, emit=0.3)))
    return parts


def eater_boss():
    """Пожиратель Света — страж Полей сияния: угорь из тьмы с огоньком-приманкой."""
    dark, light, glow = "#2A2357", "#4C3F8F", "#F07BFF"
    parts = []
    n = 22
    for i in range(n):
        t = i / (n - 1)
        x = 0.95 * math.sin(t * 2.2 * math.pi) * (1 - 0.25 * t)
        z = 1.0 - 2.5 * t
        y = 0.1 - 0.25 * t
        r = 0.4 * (1 - 0.72 * t) + 0.06
        parts.append(sphere((x, y, z), r, sub=3, smooth=True, material=mat(dark if i % 2 else light, 0.45)))
        if i % 3 == 1 and i < n - 3:
            parts.append(sphere((x, y + r * 0.85, z), r * 0.22, sub=2, smooth=True, material=mat(glow, 0.3, emit=3.0)))
    # Голова с раскрытой пастью.
    hx, hy, hz = 0.0, 0.35, 1.35
    parts.append(sphere((hx, hy, hz), 0.55, scale=(1.0, 1.0, 0.85), sub=3, smooth=True, material=mat(dark, 0.45)))
    parts.append(sphere((hx, hy + 0.3, hz - 0.32), 0.38, scale=(1.0, 0.75, 0.4), sub=3, smooth=True,
                        material=mat(light, 0.45), rot=(0.35, 0, 0)))
    parts.append(sphere((hx, hy + 0.42, hz - 0.12), 0.3, scale=(1.0, 0.5, 0.45), sub=3, smooth=True,
                        material=mat("#5A1838", 0.6)))
    for k in range(5):
        tx = -0.24 + k * 0.12
        parts.append(cone((tx, hy + 0.55, hz + 0.02), 0.04, 0.0, 0.12, verts=6, material=mat(C["white"], 0.3),
                          rot=(math.pi, 0, 0)))
        parts.append(cone((tx + 0.06, hy + 0.58, hz - 0.3), 0.035, 0.0, 0.1, verts=6, material=mat(C["white"], 0.3)))
    for side in (-1, 1):
        parts.append(sphere((side * 0.27, hy + 0.42, hz + 0.25), 0.13, scale=(1, 0.5, 0.8), sub=3, smooth=True,
                            material=mat("#FFD1F7", 0.3, emit=4.0)))
        parts.append(sphere((side * 0.27, hy + 0.48, hz + 0.24), 0.045, scale=(0.5, 1, 1.4), sub=2, smooth=True,
                            material=mat("#1A0F2E", 0.2)))
        parts.append(box((side * 0.28, hy + 0.42, hz + 0.45), (0.3, 0.08, 0.07), material=mat("#140D2A", 0.5),
                         rot=(0, -side * 0.45, 0), bev=0.02))
        parts.append(cone((side * 0.55, hy - 0.05, hz + 0.25), 0.16, 0.0, 0.5, verts=6, material=mat(glow, 0.3, emit=1.2),
                          rot=(0, side * 1.2, 0)))
    # Приманка на усике.
    pts = [(0, hy - 0.1, hz + 0.45), (0.05, hy, hz + 0.85), (0.25, hy + 0.2, hz + 1.1), (0.45, hy + 0.35, hz + 1.05)]
    curve = bpy.data.curves.new("lure", type="CURVE")
    curve.dimensions = "3D"
    curve.bevel_depth = 0.03
    curve.bevel_resolution = 3
    spline = curve.splines.new("POLY")
    spline.points.add(len(pts) - 1)
    for p, co in zip(spline.points, pts):
        p.co = (*co, 1)
    lure = bpy.data.objects.new("lure", curve)
    bpy.context.collection.objects.link(lure)
    lure.data.materials.append(mat(dark, 0.5))
    CREATED.append(lure)
    parts.append(lure)
    parts.append(sphere((0.45, hy + 0.35, hz + 1.05), 0.12, sub=3, smooth=True, material=mat("#FFF3B0", 0.2, emit=6.0)))
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
    cam.data.ortho_scale = ORTHO or fit * (1 + 2 * margin)
    print("ORTHO_FIT", round(fit * (1 + 2 * margin), 3))


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


# --- выгрузка спрайтов для игры ---------------------------------------------------------------

def _meshes(objs):
    return [o for o in objs if o.type in {"MESH", "CURVE"}]


def _project(camera, p):
    """Точка мира → пиксели кадра (x вправо, y вниз) и глубина от камеры."""
    v = world_to_camera_view(scene, camera, p)
    return [round(v.x * W, 2), round((1 - v.y) * H, 2), round(v.z, 4)]


def _render_layer(path, visible, holdout=(), border=None):
    """Рендер слоя: видны только `visible`, `holdout` вырезают то, что за ними."""
    show = set(visible) | set(holdout)
    for o in scene.objects:
        if o.type in {"MESH", "CURVE"}:
            o.hide_render = o not in show
            o.is_holdout = o in holdout
    scene.render.use_border = border is not None
    scene.render.use_crop_to_border = False
    if border:
        x0, y0, x1, y1 = border
        scene.render.border_min_x, scene.render.border_max_x = max(0, x0 / W), min(1, x1 / W)
        scene.render.border_min_y, scene.render.border_max_y = max(0, 1 - y1 / H), min(1, 1 - y0 / H)
    render(path)


def _orient(obj, loc, normal, size, lift=0.0):
    q = normal.to_track_quat("Z", "Y")
    obj.matrix_world = Matrix.Translation(loc + normal * lift) @ q.to_matrix().to_4x4() @ Matrix.Diagonal((*size, 1))


def export_whale_sprites(camera):
    """Кит одного возраста по слоям + места построек на палубе (сырые PNG и JSON).

    Слои: тело с палубой; дальний и ближний плавник и хвост (с вырезом тела —
    для анимации поворотом); заплатки «глаза закрыты» и «рот открыт»; нимб.
    Всё в одном кадре: координаты в JSON — пиксели этого кадра.
    """
    os.makedirs(OUT, exist_ok=True)
    make_whale()
    make_deck()
    whale_objs = list(CREATED)
    garden = make_garden()
    root = bpy.data.objects.new("whale_root", None)
    scene.collection.objects.link(root)
    for o in CREATED:
        if o.parent is None:
            o.parent = root
    root.rotation_euler = (0, 0, YAW)
    root.scale = (ST["scale"],) * 3
    fit_camera(camera, margin=0.07)
    bpy.context.view_layer.update()
    M = root.matrix_world
    ppu = W / camera.data.ortho_scale

    def proj(p_local):
        return _project(camera, M @ Vector(p_local))

    k = item_scale()
    slots, seen = [], {}
    for (key, x, y, size) in GARDEN[STAGE]:
        px, py, depth = proj((x, DECK_Y + y, TOP))
        slot = {"item": key, "x": px, "y": py, "depth": depth, "scale": round(size * k * ST["scale"], 4)}
        kind = key.split(":")[0]
        if kind in GENERATOR_KINDS:
            i = seen.get(kind, 0)
            seen[kind] = i + 1
            th = GENERATOR_THRESHOLDS[kind]
            slot["gen"] = kind
            slot["need"] = th[i] if i < len(th) else th[-1] * (i - len(th) + 2)
        slots.append(slot)
    slots.sort(key=lambda sl: -sl["depth"])  # дальние рисуются первыми

    fins = sorted(PARTS["fin"], key=lambda f: _project(camera, f.matrix_world.translation)[2])
    near_fin, far_fin = fins[0], fins[1]
    near_side = -1 if near_fin is PARTS["fin"][0] else 1  # плавники созданы в порядке сторон −1, 1
    eye_px = []
    for (loc, n, up, right, ek) in EYES:
        cx, cy, _ = proj(loc)
        eye_px.append([cx, cy, round(0.3 * ek * ST["scale"] * ppu, 2)])
    mouth = proj(ANCHORS["mouth"][0])
    data = {
        "age": STAGE, "name": ST["name"], "size": [W, H], "ppu": round(ppu, 4), "scale": ST["scale"],
        "itemScale": round(k, 4), "slots": slots, "eyes": eye_px, "mouth": mouth[:2],
        "finNear": proj(ANCHORS[f"fin{near_side}"])[:2], "finFar": proj(ANCHORS[f"fin{-near_side}"])[:2],
        "fluke": proj(ANCHORS["fluke"])[:2], "bodyColor": ST["body"],
    }

    special = set(PARTS["fin"]) | set(PARTS["fluke"]) | set(PARTS["halo"]) | set(PARTS["star"])
    body = [o for o in _meshes(whale_objs) if o not in special]
    out = lambda name: os.path.join(OUT, f"{name}.png")
    _render_layer(out("body"), body)
    _render_layer(out("fin_far"), [far_fin])
    _render_layer(out("fin_near"), [near_fin], holdout=body)
    _render_layer(out("fluke"), PARTS["fluke"], holdout=body)
    if PARTS["halo"]:
        _render_layer(out("halo"), PARTS["halo"], holdout=body)
        data["halo"] = True

    # Заплатка «глаза закрыты»: глаза и блики спрятаны, вместо них — дуги век.
    closed = []
    for (loc, n, up, right, ek) in EYES:
        pts = []
        for i in range(9):
            u = -1 + 2 * i / 8
            pts.append(loc + right * (u * 0.19 * ek) - up * (0.035 * ek * (1 - u * u)) + n * (0.03 * ek))
        curve = bpy.data.curves.new("lid", type="CURVE")
        curve.dimensions = "3D"
        curve.bevel_depth = 0.032 * ek
        curve.bevel_resolution = 3
        curve.use_fill_caps = True
        spl = curve.splines.new("POLY")
        spl.points.add(len(pts) - 1)
        for p, co in zip(spl.points, pts):
            p.co = (*co, 1)
        obj = bpy.data.objects.new("lid", curve)
        bpy.context.collection.objects.link(obj)
        obj.data.materials.append(mat(C["mouth"], 0.3))
        obj.parent = root
        closed.append(obj)
    bpy.context.view_layer.update()
    xs = [e[0] for e in eye_px]
    ys = [e[1] for e in eye_px]
    r = max(e[2] for e in eye_px) * 1.9
    eyes_box = (min(xs) - r, min(ys) - r, max(xs) + r, max(ys) + r)
    no_eyes = [o for o in body if o not in PARTS["eye"] and o not in PARTS["eye_hl"]]
    _render_layer(out("eyes_closed"), no_eyes + closed, border=eyes_box)
    data["eyesBox"] = [round(v, 2) for v in eyes_box]

    # Заплатка «рот открыт»: кит поёт.
    loc, n = ANCHORS["mouth"]
    m_dark = sphere((0, 0, 0), 1, material=mat("#3A1E3F", 0.4), smooth=True, uv=True)
    _orient(m_dark, loc, n, (0.17, 0.13, 0.06), lift=0.0)
    m_tongue = sphere((0, 0, 0), 1, material=mat("#FF7FA0", 0.5), smooth=True, uv=True)
    up = n.cross(Vector((0, 0, 1)).cross(n)).normalized()
    _orient(m_tongue, loc - up * 0.05, n, (0.1, 0.06, 0.06), lift=0.012)
    for o in (m_dark, m_tongue):
        o.parent = root
    bpy.context.view_layer.update()
    mr = 0.42 * ST["scale"] * ppu
    mouth_box = (mouth[0] - mr, mouth[1] - mr * 0.7, mouth[0] + mr, mouth[1] + mr * 0.8)
    no_smile = [o for o in body if o not in PARTS["smile"]]
    _render_layer(out("mouth_open"), no_smile + [m_dark, m_tongue], border=mouth_box)
    data["mouthBox"] = [round(v, 2) for v in mouth_box]

    with open(os.path.join(OUT, "whale.json"), "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print("EXPORTED", OUT)


def export_items(camera):
    """Каждая постройка сада отдельно, с мягкой тенью от ловца теней (сырые PNG и JSON)."""
    os.makedirs(OUT, exist_ok=True)
    keys = sorted({key for lst in GARDEN.values() for (key, *_rest) in lst})
    only = OPTS.get("only", "").split(",") if "only" in OPTS else None
    ortho = 3.2
    meta = {}
    for key in keys:
        if only and key not in only:
            continue
        aim_camera(camera)
        camera.data.ortho_scale = ortho
        up = camera.matrix_world.to_3x3() @ Vector((0, 1, 0))
        camera.location = camera.location + up * (ortho * 0.18)
        group(item_parts(key), rot=FACE_CAMERA)
        bpy.ops.mesh.primitive_plane_add(size=4, location=(0, 0, 0))
        catcher = bpy.context.active_object
        catcher.is_shadow_catcher = True
        CREATED.append(catcher)
        bpy.context.view_layer.update()
        ax, ay, _ = _project(camera, Vector((0, 0, 0)))
        name = key.replace(":", "_")
        render(os.path.join(OUT, f"item_{name}.png"))
        meta[key] = {"file": f"item_{name}", "anchor": [ax, ay], "ppu": round(W / ortho, 4)}
        clear_created()
    with open(os.path.join(OUT, "items.json"), "w") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    print("EXPORTED", OUT)


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
    "island": floating_island,
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
    whale_root.rotation_euler = (TILT, 0, YAW)
    whale_root.scale = (ST["scale"],) * 3
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
            if name != "island":
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
elif MODE == "boss":
    aim_camera(camera, pitch=math.radians(62))
    group(storm_petrel(), rot=FACE_CAMERA)
    fit_camera(camera, margin=0.04)
    render(OUT)
elif MODE == "sprites":
    export_whale_sprites(camera)
elif MODE == "items":
    export_items(camera)
elif MODE == "birds":
    os.makedirs(OUT, exist_ok=True)
    for i, wing in enumerate((-0.95, -0.3, 0.35)):
        aim_camera(camera)
        group(bird(wing), rot=FACE_CAMERA + 0.5)
        camera.data.ortho_scale = 1.05
        render(os.path.join(OUT, f"bird{i}.png"))
        clear_created()
elif MODE == "bosses":
    os.makedirs(OUT, exist_ok=True)
    for name, build, pitch in (("fog", fog_boss, 62), ("petrel", storm_petrel, 62), ("eater", eater_boss, 66)):
        aim_camera(camera, pitch=math.radians(pitch))
        group(build(), rot=FACE_CAMERA)
        fit_camera(camera, margin=0.04)
        render(os.path.join(OUT, f"boss_{name}.png"))
        clear_created()
else:
    raise SystemExit(f"неизвестный режим: {MODE}")

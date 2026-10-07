"""Кадры интро в стиле китайской живописи тушью (шуймохуа).

Кита рисует Blender (`toy_scene.py ink`: объём shade.png и линии кисти lines.png),
а всё остальное — здесь, на numpy: рисовая бумага, горы в тумане, облака-завитки,
тушь по объёму кита, камни-киты с соснами, Мгла, красная печать, подписи и «цвет,
который возвращается».

    python3 tool/art/ink_frames.py RENDERS FONTS OUT

RENDERS — папка с подпапками mother/, calf/, stone1/, stone2/ (рендеры режима ink),
FONTS — папка со шрифтами MarckScript-Regular.ttf, ComforterBrush-Regular.ttf,
MaShanZheng-Regular.ttf (все SIL OFL), OUT — куда сложить кадры.
"""

import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage

W, H = 1080, 1920
PAPER = np.array([236, 227, 205], float) / 255
INK = np.array([24, 21, 19], float) / 255
GOLD = np.array([214, 160, 64], float) / 255
SEAL = np.array([178, 34, 30], float) / 255


# --- шум ------------------------------------------------------------------------------

def value_noise(h, w, cell, rng):
    """Гладкий шум: случайная сетка с шагом cell, растянутая бикубикой."""
    gh, gw = max(2, int(h / cell) + 3), max(2, int(w / cell) + 3)
    grid = rng.random((gh, gw)).astype(np.float32)
    img = Image.fromarray(grid, mode="F").resize((int(gw * cell), int(gh * cell)), Image.BICUBIC)
    return np.asarray(img)[:h, :w]


def fbm(h, w, cell, rng, octaves=4, gain=0.5):
    total, amp, norm = np.zeros((h, w), np.float32), 1.0, 0.0
    for _ in range(octaves):
        total += amp * value_noise(h, w, cell, rng)
        norm += amp
        amp *= gain
        cell = max(2.0, cell / 2)
    return total / norm


def fbm1d(n, cell, rng, octaves=5):
    return fbm(1, n, cell, rng, octaves)[0]


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def blur(a, s):
    return ndimage.gaussian_filter(a, s) if s > 0 else a


# --- бумага ------------------------------------------------------------------------------

def paper(rng, h=H, w=W):
    """Рисовая бумага: тёплый тон, пятна, зерно, волокна, затемнение к краям."""
    img = np.ones((h, w, 3), np.float32) * PAPER
    img *= (0.97 + 0.06 * fbm(h, w, 380, rng))[..., None]
    img *= (0.985 + 0.03 * rng.random((h, w)).astype(np.float32))[..., None]
    fibers = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(fibers)
    for _ in range(1400):
        x, y = rng.random() * w, rng.random() * h
        ang, length = rng.random() * math.pi, 8 + rng.random() * 40
        pts = []
        for k in range(6):
            t = k / 5
            pts.append((x + math.cos(ang) * length * t + rng.normal() * 1.5, y + math.sin(ang) * length * t + rng.normal() * 1.5))
        d.line(pts, fill=int(40 + rng.random() * 50), width=1)
    f = np.asarray(fibers.filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255
    img *= (1 - 0.12 * f)[..., None]
    yy, xx = np.mgrid[0:h, 0:w]
    r = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2))
    img *= (1 - 0.1 * smoothstep(0.75, 1.45, r))[..., None]
    return img


# --- горы в тумане --------------------------------------------------------------------------

def peaks(w, rng, n, sharp):
    """Профиль хребта: вершины разной формы — от круглых холмов до острых скал."""
    x = np.arange(w, dtype=np.float32)
    prof = np.zeros(w, np.float32)
    for _ in range(n):
        c = rng.random() * w
        width = w * (0.05 + rng.random() * 0.15)
        hgt = 0.4 + rng.random() * 0.6
        k = sharp * (0.75 + 0.9 * rng.random())
        prof = np.maximum(prof, hgt * np.exp(-np.abs((x - c) / width) ** k))
    prof += 0.06 * fbm1d(w, 90, rng) + 0.012 * fbm1d(w, 12, rng)
    return prof


def mountains(rng, base_y, height, density, falloff, n=4, sharp=1.25, strokes=420, h=H, w=W):
    """Гряда тушью, как кистью: рваный контур гребня, мазки-«пимацунь» вниз по склонам,
    пятнистая размывка, которая уходит в туман, и точки мха у вершин."""
    ridge = base_y - height * peaks(w, rng, n, sharp)
    yy = np.arange(h, dtype=np.float32)[:, None]
    dist = yy - ridge[None, :]
    wash = np.exp(-np.clip(dist, 0, None) / falloff) * (0.5 + 0.5 * fbm(h, w, 150, rng, octaves=3))
    wash *= smoothstep(0, 4, dist)

    contour = Image.new("L", (w, h), 0)
    x = 0
    while x < w - 10:
        seg = int(80 + rng.random() * 220)
        xs = np.arange(x, min(w - 1, x + seg), 6)
        if len(xs) > 2:
            pts = [(float(xi), float(ridge[xi]) + rng.normal() * 0.6) for xi in xs]
            wd = 2.5 + rng.random() * 5
            stroke(contour, pts, wd, wd * (0.4 + rng.random() * 0.8), value=0.75 + 0.25 * rng.random())
        x += seg + int(rng.random() * 50)

    tex = Image.new("L", (w, h), 0)
    slope = np.gradient(ridge)
    top = ridge.min()
    for _ in range(strokes):
        # Ближе к вершинам мазков больше.
        for _try in range(6):
            x0 = int(rng.random() * (w - 1))
            if rng.random() < 1.2 - (ridge[x0] - top) / max(1, height):
                break
        sx = float(np.clip(slope[x0], -3, 3))
        dirx = math.copysign(1, sx) if abs(sx) > 0.05 else rng.choice((-1.0, 1.0))
        dx, dy = dirx * 0.55 + rng.normal() * 0.1, 0.6 + abs(sx) * 0.3
        nrm = math.hypot(dx, dy)
        dx, dy = dx / nrm, dy / nrm
        length = 40 + rng.random() * 150
        y0 = ridge[x0] + 4 + rng.random() * 30
        bend = rng.normal() * 0.25
        pts = []
        for t in np.linspace(0, 1, 10):
            ang = bend * t
            pts.append((x0 + (dx * math.cos(ang) - dy * math.sin(ang)) * length * t,
                        y0 + (dx * math.sin(ang) + dy * math.cos(ang)) * length * t))
        stroke(tex, pts, 2 + rng.random() * 4, 0.8 + rng.random(), value=0.4 + 0.45 * rng.random())

    dots = Image.new("L", (w, h), 0)
    dd = ImageDraw.Draw(dots)
    order = np.argsort(ridge)[: max(1, w // 40)]
    for x0 in order[::3]:
        for _ in range(int(4 + rng.random() * 6)):
            px, py = x0 + rng.normal() * 22, ridge[x0] + rng.random() * 14
            r = 1.0 + rng.random() * 1.6
            dd.ellipse((px - r, py - r * 0.8, px + r, py + r * 0.8), fill=210)

    c = np.asarray(contour.filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255
    t = np.asarray(tex.filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255
    d = np.asarray(dots.filter(ImageFilter.GaussianBlur(0.6)), np.float32) / 255
    # Сухая кисть: штрихи местами рвутся о бумагу.
    dry = 0.55 + 0.45 * (rng.random((h, w)).astype(np.float32) > 0.3)
    fade = np.exp(-np.clip(dist, 0, None) / (falloff * 1.6))
    rim = np.exp(-np.clip(dist, 0, None) / (falloff * 0.18)) * smoothstep(0, 3, dist)
    ink = np.maximum(wash * 0.55 + rim * 0.35, t * dry * fade) + c * dry + d * 0.8
    return np.clip(ink * density, 0, 1), ridge


def mist(y0, y1, strength, rng, h=H, w=W):
    """Полоса тумана: стирает тушь, края неровные."""
    yy = np.arange(h, dtype=np.float32)[:, None]
    wobble = 40 * (fbm1d(w, 240, rng)[None, :] - 0.5)
    band = smoothstep(y0 - 120, y0, yy + wobble) * (1 - smoothstep(y1, y1 + 160, yy + wobble))
    return 1 - strength * band


# --- кисть -----------------------------------------------------------------------------------

def stroke(layer, pts, w0, w1, value=1.0, taper=True):
    """Мазок по точкам: кружки вдоль пути, толщина от w0 к w1, сужение к концам."""
    d = ImageDraw.Draw(layer)
    n = len(pts)
    for i in range(n - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        steps = max(1, int(math.hypot(x1 - x0, y1 - y0) / 1.5))
        for k in range(steps):
            t = (i + k / steps) / (n - 1)
            wdt = w0 + (w1 - w0) * t
            if taper:
                wdt *= min(1.0, t / 0.12 + 0.15, (1 - t) / 0.25 + 0.1)
            x, y = x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps
            r = max(0.6, wdt / 2)
            d.ellipse((x - r, y - r, x + r, y + r), fill=int(255 * value))


def cloud_ribbon(rng, x0, y0, length, scale, direction=1):
    """Облако-лента со старинных свитков: белые клубы тумана, две волнистые линии кисти,
    у верхней на «голове» один завиток. Возвращает заливку (закрывает тушь за собой) и линии."""
    fill = Image.new("L", (W, H), 0)
    line = Image.new("L", (W, H), 0)
    fd = ImageDraw.Draw(fill)
    for t in np.linspace(0, 1, 9):
        cx = x0 + direction * t * length
        r = scale * (0.55 + 0.35 * math.sin(t * math.pi)) * (0.8 + 0.4 * rng.random())
        fd.ellipse((cx - r, y0 - r * 0.9, cx + r, y0 + r * 0.45), fill=255)
    for row, (ln, off) in enumerate(((1.0, 0.0), (0.72, 0.42))):
        phase = rng.random() * 6
        pts = []
        for t in np.linspace(0, 1, 50):
            pts.append((x0 + direction * t * length * ln + direction * row * scale * 0.5,
                        y0 + off * scale + math.sin(t * 4.5 + phase) * scale * 0.1))
        if row == 0:
            hx, hy = pts[-1]
            r0 = scale * 0.42
            for t in np.linspace(0, 1, 36)[1:]:
                a = math.pi / 2 - direction * t * 1.8 * math.pi
                rr = r0 * (1 - 0.7 * t)
                pts.append((hx + rr * math.cos(a), hy - r0 + rr * math.sin(a)))
        stroke(line, pts, scale * 0.08, scale * 0.09 if row == 0 else scale * 0.04, value=0.85)
    f = np.asarray(fill.filter(ImageFilter.GaussianBlur(scale * 0.2)), np.float32) / 255
    l = np.asarray(line.filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255
    under = np.clip(f - blur(f, scale * 0.15) * 0.0, 0, 1) * 0.0
    return f * 0.75, np.clip(l + under, 0, 1)


def birds(rng, x0, y0, spread, n):
    layer = Image.new("L", (W, H), 0)
    for _ in range(n):
        x, y = x0 + rng.normal() * spread, y0 + rng.normal() * spread * 0.4
        s = 8 + rng.random() * 8
        stroke(layer, [(x - s, y - s * 0.3), (x - s * 0.4, y - s * 0.45), (x, y)], 3, 1.5)
        stroke(layer, [(x, y), (x + s * 0.5, y - s * 0.5), (x + s * 1.1, y - s * 0.35)], 1.5, 3)
    return np.asarray(layer, np.float32) / 255


# --- тушь по рендеру кита ---------------------------------------------------------------------

def face_strokes(top, h, w, rng, count, reach):
    """Мазки-«пимацунь» вниз от верхнего края силуэта (для камней-китов)."""
    tex = Image.new("L", (w, h), 0)
    cols = np.where(top < h)[0]
    slope = np.gradient(np.where(top < h, top, h).astype(np.float32))
    for _ in range(count):
        x0 = int(rng.choice(cols))
        sx = float(np.clip(slope[x0], -3, 3))
        dirx = math.copysign(1, sx) if abs(sx) > 0.05 else rng.choice((-1.0, 1.0))
        dx, dy = dirx * 0.5 + rng.normal() * 0.15, 0.7
        nrm = math.hypot(dx, dy)
        length = reach * (0.3 + 0.7 * rng.random())
        y0 = top[x0] + 3 + rng.random() * reach * 0.3
        pts = [(x0 + dx / nrm * length * t, y0 + dy / nrm * length * t) for t in np.linspace(0, 1, 8)]
        stroke(tex, pts, 1.8 + rng.random() * 3, 0.8, value=0.35 + 0.45 * rng.random())
    return np.asarray(tex.filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255


def load_render(folder, width, rng, stone=False):
    """shade.png + lines.png → плотность туши, маска (что закрывает фон), линии и цвет.
    Тёмная спина — густая размывка, светлое брюхо — бледная; к краям пятен краска
    собирается, как у настоящей туши. Камень-кит — скала: тёмный верх, мазки по склонам."""
    shade = Image.open(os.path.join(folder, "shade.png")).convert("RGBA")
    lines = Image.open(os.path.join(folder, "lines.png")).convert("RGBA")
    box, lb = shade.getbbox(), lines.getbbox()
    box = (min(box[0], lb[0]), min(box[1], lb[1]), max(box[2], lb[2]), max(box[3], lb[3]))
    shade, lines = shade.crop(box), lines.crop(box)
    k = width / shade.width
    size = (int(shade.width * k), int(shade.height * k))
    shade, lines = shade.resize(size, Image.LANCZOS), lines.resize(size, Image.LANCZOS)
    s = np.asarray(shade, np.float32) / 255
    a = s[..., 3]
    h, w = a.shape
    lum = 0.3 * s[..., 0] + 0.59 * s[..., 1] + 0.11 * s[..., 2]
    tone = np.clip((1 - lum) * 1.25 - 0.08, 0, 1)
    poster = sum(smoothstep(lv - 0.05, lv + 0.05, tone) for lv in (0.15, 0.38, 0.62)) / 3
    wash = (0.55 * poster + 0.45 * tone) * (0.86 + 0.28 * fbm(h, w, 36, rng, octaves=3))
    pooled = np.clip(wash - blur(wash, 4), 0, None) * 2.2
    wash = np.clip(wash * 0.75 + pooled, 0, 1)
    la = np.asarray(lines, np.float32)[..., 3] / 255
    dry = smoothstep(0.25, 0.6, fbm(h, w, 3, rng, octaves=2) + 0.25 * la)
    la = la * (0.55 + 0.45 * dry)
    if stone:
        solid = a > 0.5
        top = np.where(solid.any(axis=0), solid.argmax(axis=0), h)
        dist = np.arange(h, dtype=np.float32)[:, None] - top[None, :]
        rock = np.exp(-np.clip(dist, 0, None) / (h * 0.3)) * (0.45 + 0.55 * fbm(h, w, 60, rng, octaves=3))
        tex = face_strokes(top, h, w, rng, int(w * 0.35), h * 0.55)
        wash = np.clip(np.maximum(rock * 0.75, tex) + tone * 0.15, 0, 1)
        dens = np.clip(wash * a + la * 0.9, 0, 1)
    else:
        dens = np.clip(wash * a * 0.62 + la, 0, 1)
    mask = blur(np.clip(a * 1.15, 0, 1), 1.2)
    return dict(dens=dens, mask=mask, rgb=s[..., :3], alpha=a, lines=la)


def place(canvas, layer, x, y):
    """Срезы холста и слоя, чтобы поставить слой центром в точку x, y."""
    h, w = layer.shape[:2]
    x0, y0 = int(x - w / 2), int(y - h / 2)
    cx0, cy0 = max(0, x0), max(0, y0)
    cx1, cy1 = min(canvas.shape[1], x0 + w), min(canvas.shape[0], y0 + h)
    return (slice(cy0, cy1), slice(cx0, cx1)), (slice(cy0 - y0, cy1 - y0), slice(cx0 - x0, cx1 - x0))


def put(dens, occl, whale, x, y):
    (cy, cx), (ly, lx) = place(dens, whale["dens"], x, y)
    m = whale["mask"][ly, lx]
    dens[cy, cx] = dens[cy, cx] * (1 - m) + whale["dens"][ly, lx]
    occl[cy, cx] = np.maximum(occl[cy, cx], m)
    return (cy, cx), (ly, lx)


def pines_on(whale, rng, count):
    """Сосны тушью на спине камня-кита: наклонный ствол и веера хвои ярусами."""
    a = whale["alpha"]
    h, w = a.shape
    pad = int(h * 0.9)
    big = np.zeros((h + pad, w), np.float32)
    big[pad:] = whale["dens"]
    mask = np.zeros((h + pad, w), np.float32)
    mask[pad:] = whale["mask"]
    alpha = np.zeros((h + pad, w), np.float32)
    alpha[pad:] = a
    cols = np.where(a.max(axis=0) > 0.5)[0]
    layer = Image.new("L", (w, h + pad), 0)
    pads = Image.new("L", (w, h + pad), 0)
    for _ in range(count):
        x = int(cols[0] + (0.15 + 0.55 * rng.random()) * (cols[-1] - cols[0]))
        top = pad + int(np.argmax(a[:, x] > 0.5)) + 4
        th = h * (0.5 + 0.3 * rng.random())
        lean = (rng.random() - 0.3) * th * 0.5
        trunk = [(x + lean * t ** 1.5 + math.sin(t * 5) * th * 0.03, top - th * t) for t in np.linspace(0, 1, 14)]
        stroke(layer, trunk, th * 0.06, th * 0.025, taper=False, value=0.85)
        wash = ImageDraw.Draw(pads)
        for j in range(3 + int(rng.random() * 2)):
            t = 0.5 + 0.5 * j / 3
            bx, by = trunk[min(13, int(t * 13))]
            side = -1 if j % 2 else 1
            pw = th * (0.42 - 0.18 * t) * (0.8 + 0.4 * rng.random())
            ph = pw * 0.28
            px, py = bx + side * pw * 0.35, by - ph * 0.2
            stroke(layer, [(bx, by), (px, py + ph * 0.3)], th * 0.022, th * 0.012, value=0.85)
            wash.ellipse((px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2), fill=110)
            for q in range(26):
                u = rng.random() - 0.5
                sx0, sy0 = px + u * pw * 0.9, py + ph * 0.35
                ang = math.pi / 2 + u * 1.6 + rng.normal() * 0.15
                ln = ph * (0.6 + 0.6 * rng.random())
                stroke(layer, [(sx0, sy0), (sx0 + math.cos(ang) * ln * 0.6, sy0 - math.sin(ang) * ln)], th * 0.012, th * 0.005,
                       value=0.95)
    p = np.asarray(layer.filter(ImageFilter.GaussianBlur(0.9)), np.float32) / 255
    p = np.maximum(p, np.asarray(pads.filter(ImageFilter.GaussianBlur(3)), np.float32) / 255)
    whale["dens"] = np.clip(big + p * 0.95, 0, 1)
    whale["mask"] = np.maximum(mask, blur(p, 2) * 0.9)
    whale["alpha"] = np.maximum(alpha, p)
    whale["rgb"] = np.zeros((h + pad, w, 3), np.float32)
    whale["lines"] = np.zeros((h + pad, w), np.float32)


# --- Мгла ------------------------------------------------------------------------------------

def gloom(rng, cx, cy, rx, ry):
    """Мгла: клубы туши слоями — бледная размывка снаружи, густое ядро, завихрения
    кистью внутри и узкие бледные глаза."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    wx = (fbm(H, W, 200, rng) - 0.5) * 300
    wy = (fbm(H, W, 200, rng) - 0.5) * 220
    r = np.hypot((xx + wx - cx) / rx, (yy + wy - cy) / ry) + 0.3 * (fbm(H, W, 60, rng) - 0.5)
    outer = (1 - smoothstep(0.85, 1.15, r)) * 0.28
    mid = (1 - smoothstep(0.6, 0.9, r)) * 0.3
    core = (1 - smoothstep(0.25, 0.65, r)) * 0.35
    dens = (outer + mid + core) * (0.75 + 0.5 * fbm(H, W, 34, rng, octaves=3))
    pooled = np.clip(dens - blur(dens, 6), 0, None) * 1.8
    swirl = Image.new("L", (W, H), 0)
    for i in range(7):
        sx, sy = cx + rng.normal() * rx * 0.45, cy + rng.normal() * ry * 0.35
        r0 = rx * (0.12 + 0.18 * rng.random())
        turn = 1 if rng.random() < 0.5 else -1
        a0 = rng.random() * math.tau
        pts = [(sx + r0 * (1 - 0.8 * t) * math.cos(a0 + turn * t * 4.2), sy + r0 * 0.6 * (1 - 0.8 * t) * math.sin(a0 + turn * t * 4.2))
               for t in np.linspace(0, 1, 40)]
        stroke(swirl, pts, rx * 0.035, rx * 0.01, value=0.55)
    sw = np.asarray(swirl.filter(ImageFilter.GaussianBlur(1.2)), np.float32) / 255
    dens = np.clip(dens + pooled + sw * (1 - smoothstep(0.5, 0.9, r)), 0, 1)
    eyes = np.zeros((H, W), np.float32)
    for side in (-1, 1):
        ex, ey = cx + side * rx * 0.3, cy - ry * 0.02
        ang = side * math.radians(14)
        u = ((xx - ex) * math.cos(ang) + (yy - ey) * math.sin(ang)) / (rx * 0.1)
        v = (-(xx - ex) * math.sin(ang) + (yy - ey) * math.cos(ang)) / (ry * 0.035)
        eyes = np.maximum(eyes, 1 - smoothstep(0.8, 1.0, np.hypot(u, v)))
    return dens, eyes


def note_glyphs(points, rng):
    """Ноты (кружок, штиль, иногда флажок) в заданных точках: (x, y, размер)."""
    layer = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(layer)
    for x, y, s in points:
        lw = max(2, int(s / 3))
        d.ellipse((x - s, y - s * 0.75, x + s, y + s * 0.75), fill=255)
        d.line((x + s * 0.85, y, x + s * 0.85, y - s * 3.2), fill=255, width=lw)
        if rng.random() < 0.6:
            d.line((x + s * 0.85, y - s * 3.2, x + s * 2.0, y - s * 2.4), fill=255, width=lw)
    return np.asarray(layer.filter(ImageFilter.GaussianBlur(0.7)), np.float32) / 255


def note_stream(rng, x0, y0, x1, y1, n):
    """Золотые ноты песен тянутся вихрем к Мгле, по пути мельчают."""
    pts = []
    for i in range(n):
        t = (i + rng.random() * 0.6) / n
        ang = t * 3.2 * math.pi
        rad = (1 - t) ** 1.3 * 240
        x = x0 + (x1 - x0) * t + math.cos(ang) * rad * 0.7 + rng.normal() * 10
        y = y0 + (y1 - y0) * t + math.sin(ang) * rad * 0.35 + rng.normal() * 10
        pts.append((x, y, 4 + 12 * (1 - t) * (0.6 + 0.6 * rng.random())))
    return note_glyphs(pts, rng)


def note_burst(rng, cx, cy, n, rmax):
    """Ноты разлетаются от китёнка — песня, которая возвращает цвет."""
    pts = []
    for _ in range(n):
        ang = -math.pi * (0.05 + 0.9 * rng.random())
        r = rmax * (0.25 + 0.75 * rng.random() ** 0.7)
        pts.append((cx + math.cos(ang) * r * 1.2, cy + math.sin(ang) * r * 0.8, 5 + 9 * (1 - r / rmax) + 3 * rng.random()))
    return note_glyphs(pts, rng)


# --- подписи и печать -------------------------------------------------------------------------

def text_layer(text, font, size, x, y, anchor="mm", spacing=10):
    layer = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(layer)
    d.multiline_text((x, y), text, font=ImageFont.truetype(font, size), fill=255, anchor=anchor,
                     align="center", spacing=spacing)
    return np.asarray(layer.filter(ImageFilter.GaussianBlur(0.5)), np.float32) / 255


def seal(fonts, x, y, size, rng):
    """Красная печать с иероглифами 鲸歌 («песнь кита»), знаки вырезаны в краске."""
    layer = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle((x, y, x + size, y + size * 1.9), radius=size * 0.08, fill=255)
    font = ImageFont.truetype(os.path.join(fonts, "MaShanZheng-Regular.ttf"), int(size * 0.8))
    d.text((x + size / 2, y + size * 0.5), "鲸", font=font, fill=0, anchor="mm")
    d.text((x + size / 2, y + size * 1.38), "歌", font=font, fill=0, anchor="mm")
    s = np.asarray(layer, np.float32) / 255
    s *= rng.random((H, W)).astype(np.float32) > 0.12
    return blur(s, 0.6)


def ink_over(img, dens):
    d = 1 - np.exp(-2.6 * np.clip(dens, 0, 1.5))
    ink_col = INK[None, None, :] * (0.55 + 0.45 * d[..., None]) + PAPER * 0.25 * (1 - d[..., None])
    return img * (1 - d[..., None]) + ink_col * d[..., None]


def paint(img, layer, color, opacity=1.0):
    a = np.clip(layer, 0, 1)[..., None] * opacity
    return img * (1 - a) + np.asarray(color, np.float32) * a


def with_text(img, base, text):
    halo = blur(text, 6)[..., None] * 0.55
    img = img * (1 - halo) + base * halo
    return paint(img, text, INK)


def save(img, path):
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(path, quality=92)
    print("FRAME", path)


def landscape(rng, layers, mist_band):
    dens = np.zeros((H, W), np.float32)
    for base_y, height, den, fall, n in layers:
        m, _ = mountains(rng, base_y, height, den, fall, n=n)
        dens = np.maximum(dens * mist(base_y - 130, base_y + 30, 0.9, rng), m)
    return dens * mist(*mist_band, 0.85, rng)


# --- кадры ------------------------------------------------------------------------------------

def frame_title(renders, fonts, out):
    """Кадр 1: свиток открывается — горы в тумане, кит-мать и китёнок в облаках."""
    rng = np.random.default_rng(11)
    base = paper(rng)
    dens = landscape(rng, ((1500, 520, 0.38, 260, 5), (1660, 430, 0.62, 200, 4), (1890, 380, 0.95, 150, 3)), (1730, 1990))
    occl = np.zeros((H, W), np.float32)
    f, l = cloud_ribbon(rng, 1120, 1300, 460, 64, -1)
    dens = dens * (1 - f) + l * 0.85
    put(dens, occl, load_render(os.path.join(renders, "mother"), 1000, rng), 560, 850)
    put(dens, occl, load_render(os.path.join(renders, "calf"), 290, rng), 250, 1190)
    f, l = cloud_ribbon(rng, -60, 1080, 560, 70, 1)
    dens = dens * (1 - f) + l * 0.85
    dens = np.maximum(dens, birds(rng, 850, 520, 60, 5) * 0.8)
    title = text_layer("Песнь\nнебесного кита", os.path.join(fonts, "MarckScript-Regular.ttf"), 112, 540, 270, spacing=6)
    sub = text_layer("Когда-то небо пело, и киты плыли по его песне…", os.path.join(fonts, "MarckScript-Regular.ttf"),
                     44, 540, 1790)
    img = ink_over(base, dens)
    img = paint(img, seal(fonts, 900, 160, 70, rng), SEAL, 0.92)
    save(with_text(img, base, np.maximum(title, sub)), os.path.join(out, "ink_1_title.jpg"))


def frame_loading(renders, fonts, out):
    """Фон загрузочного экрана: свиток с названием, кит-мать и китёнок, без подписи
    (полосу загрузки рисует игра). 720×1280, чтобы не утяжелять APK."""
    rng = np.random.default_rng(11)
    base = paper(rng)
    dens = landscape(rng, ((1500, 520, 0.38, 260, 5), (1660, 430, 0.62, 200, 4), (1890, 380, 0.95, 150, 3)), (1730, 1990))
    occl = np.zeros((H, W), np.float32)
    f, l = cloud_ribbon(rng, 1120, 1300, 460, 64, -1)
    dens = dens * (1 - f) + l * 0.85
    # Киты ближе к центру: на вытянутых экранах фон обрезается по бокам.
    put(dens, occl, load_render(os.path.join(renders, "mother"), 760, rng), 560, 880)
    put(dens, occl, load_render(os.path.join(renders, "calf"), 250, rng), 340, 1150)
    f, l = cloud_ribbon(rng, -60, 1080, 560, 70, 1)
    dens = dens * (1 - f) + l * 0.85
    dens = np.maximum(dens, birds(rng, 850, 520, 60, 5) * 0.8)
    title = text_layer("Песнь\nнебесного кита", os.path.join(fonts, "MarckScript-Regular.ttf"), 112, 540, 300, spacing=6)
    img = ink_over(base, dens)
    img = paint(img, seal(fonts, 900, 190, 70, rng), SEAL, 0.92)
    img = with_text(img, base, title)
    path = os.path.join(out, "loading.jpg")
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).resize((720, 1280), Image.LANCZOS).save(path, quality=84)
    print("FRAME", path)


def frame_gloom(renders, fonts, out):
    """Кадр 2: Мгла пьёт песни неба, киты стаи каменеют и становятся островами."""
    rng = np.random.default_rng(23)
    base = paper(rng)
    dens = landscape(rng, ((1640, 320, 0.35, 230, 6),), (1700, 1990))
    g, eyes = gloom(rng, 540, 560, 500, 330)
    dens = np.maximum(dens, g)
    occl = np.zeros((H, W), np.float32)
    for name, width, x, y, seed in (("stone2", 820, 360, 1250, 3), ("stone1", 640, 760, 1470, 5)):
        st = load_render(os.path.join(renders, name), width, rng, stone=True)
        pines_on(st, np.random.default_rng(seed), 2)
        put(dens, occl, st, x, y - st["dens"].shape[0] * 0.25)
    dens *= mist(1560, 1900, 0.75, rng)
    f, l = cloud_ribbon(rng, 1120, 1560, 420, 60, -1)
    dens = dens * (1 - f) + l * 0.7
    dens = np.clip(dens - eyes * 1.5, 0, 1)
    img = ink_over(base, dens)
    img = paint(img, blur(eyes, 8), GOLD, 0.25)
    img = paint(img, note_stream(rng, 980, 1120, 560, 650, 34), GOLD, 0.95)
    sub = text_layer("Но пришла Мгла и выпила песни неба.\nКиты стаи уснули и окаменели,\nстав островами.",
                     os.path.join(fonts, "MarckScript-Regular.ttf"), 46, 540, 1770, spacing=8)
    save(with_text(img, base, sub), os.path.join(out, "ink_2_gloom.jpg"))


def watercolor_whale(whale, rng):
    """Китёнок акварелью: цвета рендера без бликов, светотень — два-три пятна,
    краска разбавлена бумагой, с зерном и чуть темнее у края пятна."""
    a = whale["alpha"]
    wsum = blur(a, 3) + 1e-4
    rgb = np.stack([blur(whale["rgb"][..., c] * a, 3) / wsum for c in range(3)], axis=-1)
    lum = rgb @ np.array([0.3, 0.59, 0.11], np.float32) + 1e-4
    flat = (0.55 + 0.45 * sum(smoothstep(lv - 0.04, lv + 0.04, lum) for lv in (0.45, 0.7)) / 2)
    flat = np.where(lum < 0.3, lum * 0.8, flat)  # глаза остаются тёмными
    rgb = rgb * (flat / lum)[..., None] * 0.95
    h, w = a.shape
    rgb = rgb * 0.8 + PAPER * 0.2
    rgb *= (0.92 + 0.16 * fbm(h, w, 30, rng, octaves=3))[..., None]
    edge = np.clip(a - blur(a, 5), 0, 1)
    rgb *= (1 - 0.35 * edge)[..., None]
    return np.clip(rgb, 0, 1)


def frame_color(renders, fonts, out):
    """Кадр 3: китёнок впервые поёт — цвет разливается по свитку, как акварель."""
    rng = np.random.default_rng(37)
    base = paper(rng)
    dens = landscape(rng, ((1350, 460, 0.4, 250, 5), (1570, 420, 0.65, 190, 4), (1830, 360, 0.95, 150, 3)), (1700, 1990))
    f, l = cloud_ribbon(rng, 1100, 1150, 460, 64, -1)
    dens = dens * (1 - f) + l * 0.8
    occl = np.zeros((H, W), np.float32)
    calf = load_render(os.path.join(renders, "calf"), 470, rng)
    (cy, cx), (ly, lx) = put(dens, occl, calf, 560, 880)
    mouth = (cx.start + calf["alpha"].shape[1] * 0.3, cy.start + calf["alpha"].shape[0] * 0.5)
    notes = note_burst(rng, mouth[0] + 40, mouth[1] - 60, 16, 380)
    ink_img = paint(ink_over(base, dens), notes, GOLD, 0.9)

    # Цветной мир: небо акварелью, горы — «сине-зелёный пейзаж», линии остаются тушью.
    grain = base / PAPER
    yy = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    sky = (1 - yy) * np.array([160, 205, 232]) / 255 + yy * np.array([248, 230, 200]) / 255
    sky = sky * grain * (0.95 + 0.1 * fbm(H, W, 200, rng)[..., None])
    d = 1 - np.exp(-2.6 * np.clip(dens * (1 - occl), 0, 1.5))
    dark = smoothstep(0.55, 0.9, d)
    teal = np.array([62, 132, 128], np.float32) / 255
    col = sky * (1 - d[..., None]) + (teal * (1 - dark[..., None]) + INK * dark[..., None]) * d[..., None]
    wc = watercolor_whale(calf, rng)
    wa = calf["alpha"][ly, lx][..., None]
    col[cy, cx] = col[cy, cx] * (1 - wa) + wc[ly, lx] * wa
    lines = np.zeros((H, W), np.float32)
    lines[cy, cx] = calf["lines"][ly, lx]
    col = paint(col, lines, INK, 0.9)
    col = paint(col, notes, GOLD, 0.9)

    # Граница цвета: мягкая, с «приливными» кольцами мокрого края.
    yyg, xxg = np.mgrid[0:H, 0:W].astype(np.float32)
    r = np.hypot(xxg - mouth[0] - 120, (yyg - mouth[1] - 120) * 0.75) / 700
    r += 0.5 * (fbm(H, W, 320, rng) - 0.5) + 0.16 * (fbm(H, W, 90, rng) - 0.5) + 0.04 * (fbm(H, W, 20, rng) - 0.5)
    edge = 0.92
    bloom = (1 - smoothstep(edge - 0.04, edge + 0.02, r)) * (1 - 0.35 * smoothstep(0.3, edge, r))
    rings = np.exp(-((r - edge) / 0.01) ** 2) * 0.18 + np.exp(-((r - edge + 0.12) / 0.008) ** 2) * 0.07
    col = col * (1 - rings[..., None] * 0.5)
    img = ink_img * (1 - bloom[..., None]) + col * bloom[..., None]

    sub = text_layer("Но одна песня уцелела.\nИ маленький кит запел…", os.path.join(fonts, "MarckScript-Regular.ttf"),
                     48, 540, 1780, spacing=8)
    save(with_text(img, base, sub), os.path.join(out, "ink_3_color.jpg"))


if __name__ == "__main__":
    renders, fonts, out = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    only = sys.argv[4].split(",") if len(sys.argv) > 4 else ["1", "2", "3"]
    if "1" in only:
        frame_title(renders, fonts, out)
    if "2" in only:
        frame_gloom(renders, fonts, out)
    if "3" in only:
        frame_color(renders, fonts, out)
    if "loading" in only:
        frame_loading(renders, fonts, out)

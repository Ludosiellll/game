"""Ролик-предыстория в стиле китайской живописи тушью (~41 с, 24 кадра в секунду).

Пять сцен из слоёв `ink_frames.py`, каждый слой движется со своей скоростью — камера
скользит по свитку, и картинка получает объём («2–3D»):

1. свиток разворачивается, тушью проступает название, ставится печать;
2. камера спускается к киту-матери и китёнку, они плывут над горами в облаках;
3. Мгла растёт и пьёт песни-ноты, киты стаи каменеют и обрастают соснами;
4. мать-кит поёт последнюю песню, роняет светящееся яйцо и сама становится камнем;
5. яйцо трескается, появляется китёнок и запевает — цвет разливается акварелью.

    python3 tool/art/ink_intro.py RENDERS FONTS FRAMES [--workers=4] [--only=START:END]

RENDERS и FONTS — как у ink_frames.py; в FRAMES ложатся кадры 00000.jpg…, видео
собирает ffmpeg (команда — в README).
"""

import math
import os
import sys
from multiprocessing import Pool

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ink_frames as F  # noqa: E402

FPS = 24
W, H = F.W, F.H
TALL = 3000  # высота свитка в сценах 1–2: камера спускается от названия к китам
CAM_END = 980

# Сцены: начало, конец (с); соседние перекрываются на время перехода.
SCENES = [(0.0, 13.8), (13.0, 22.6), (21.8, 30.6), (29.8, 41.0)]
TOTAL = 41.0
SUBS = [
    (6.4, 12.9, "Когда-то небо пело,\nи киты плыли по его песне…"),
    (14.6, 21.6, "Но пришла Мгла и выпила песни неба.\nКиты стаи уснули и окаменели,\nстав островами."),
    (23.0, 29.6, "Мать-кит из последних сил\nспела последнюю песню —\nи оставила яйцо."),
    (31.0, 34.8, "Из него вылупился маленький кит.\nНебо молчало…"),
    (35.2, 39.6, "…но он запел."),
]
GAME_SKY = np.array([207, 240, 255], np.float32) / 255  # верх неба в игре: ролик в него растворяется


def ease(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def ramp(t, a, b):
    return ease((t - a) / (b - a)) if b > a else float(t >= a)


def window(t, a, b, fade=0.7):
    return min(ramp(t, a, a + fade), 1 - ramp(t, b - fade, b))


def crop(canvas, y):
    y = int(round(min(max(0, y), canvas.shape[0] - H)))
    return canvas[y:y + H]


def blit(dens, occl, layer, mask, x, y, alpha=1.0):
    """Слой центром в точку x, y кадра поверх туши (маска закрывает то, что позади)."""
    (cy, cx), (ly, lx) = F.place(dens, layer, x, y)
    if cy.stop <= cy.start or cx.stop <= cx.start:
        return
    m = mask[ly, lx] * alpha
    dens[cy, cx] = dens[cy, cx] * (1 - m) + layer[ly, lx] * alpha
    if occl is not None:
        occl[cy, cx] = np.maximum(occl[cy, cx], m)


def soak(noise, progress):
    """Проявление тушью: пятна бумаги темнеют по шуму, а не равномерно."""
    return F.smoothstep(noise - 0.12, noise, progress * 1.15)


def padded(whale, pad):
    out = {}
    for k in ("dens", "mask", "alpha"):
        a = whale[k]
        big = np.zeros((a.shape[0] + pad, a.shape[1]), np.float32)
        big[pad:] = a
        out[k] = big
    return out


class Timeline:
    """Строит все сцены один раз и отдаёт кадр по времени."""

    def __init__(self, renders, fonts):
        self.renders, self.fonts = renders, fonts
        self.font = os.path.join(fonts, "MarckScript-Regular.ttf")
        rng = np.random.default_rng(5)
        self.subs = [(a, b, F.text_layer(txt, self.font, 50, 540, 1755, spacing=8), F.fbm(H, W, 40, rng))
                     for a, b, txt in SUBS]
        self.trans_noise = F.fbm(H, W, 140, rng, octaves=4)
        self._scroll(np.random.default_rng(11))
        self._gloom(np.random.default_rng(23))
        self._egg(np.random.default_rng(31))
        self._bloom(np.random.default_rng(37))

    # --- сцены 1–2: свиток, название, киты над горами ---------------------------------------

    def _scroll(self, rng):
        F.H = TALL
        try:
            self.s1_paper = F.paper(rng, h=TALL)
            self.s1_mtn = []
            for base_y, height, den, fall, n, k in ((2480, 520, 0.38, 260, 5, 0.55), (2640, 430, 0.62, 200, 4, 0.75),
                                                     (2880, 380, 0.95, 150, 3, 1.0)):
                m, _ = F.mountains(rng, base_y, height, den, fall, n=n, h=TALL)
                m *= F.mist(base_y - 130, base_y + 30, 0.9, rng, h=TALL)
                self.s1_mtn.append((m * F.mist(2720, 2990, 0.85, rng, h=TALL), k))
            self.s1_clouds = [F.cloud_ribbon(rng, 1120, 2280, 460, 64, -1) + (0.85,),
                              F.cloud_ribbon(rng, -60, 2060, 560, 70, 1) + (1.15,)]
            self.s1_birds = F.birds(rng, 850, 1500, 60, 5)
            self.s1_title = F.text_layer("Песнь\nнебесного кита", self.font, 112, 540, 300, spacing=6)
            self.s1_seal = F.seal(self.fonts, 900, 190, 70, rng)
        finally:
            F.H = H
        self.s1_title_noise = F.fbm(TALL, W, 30, rng)
        self.mother = F.load_render(os.path.join(self.renders, "mother"), 1000, rng)
        self.calf = F.load_render(os.path.join(self.renders, "calf"), 290, rng)

    def scene_scroll(self, t):
        cam = CAM_END * ramp(t, 5.0, 9.4)
        dens = np.zeros((H, W), np.float32)
        for m, k in self.s1_mtn:
            v = crop(m, cam * k + (1 - k) * CAM_END)
            dens = np.maximum(dens, v)
        occl = np.zeros((H, W), np.float32)
        y0 = cam  # координаты свитка → кадра
        f, l, k = self.s1_clouds[0]
        dy = cam * k + (1 - k) * CAM_END
        fc, lc = crop(f, dy), crop(l, dy)
        dens = dens * (1 - fc) + lc * 0.85
        drift = -60 * ramp(t, 3, 14)
        bob = math.sin(t * 1.3) * 10
        blit(dens, occl, self.mother["dens"], self.mother["mask"], 560 + drift, 1830 - y0 + bob)
        blit(dens, occl, self.calf["dens"], self.calf["mask"], 250 + drift * 1.3, 2170 - y0 + math.sin(t * 1.3 + 1) * 12)
        f, l, k = self.s1_clouds[1]
        dy = cam * k + (1 - k) * CAM_END
        dens = dens * (1 - crop(f, dy)) + crop(l, dy) * 0.85
        dens = np.maximum(dens, crop(self.s1_birds, y0 - 40 * math.sin(t * 0.4)) * 0.8)
        base = crop(self.s1_paper, y0)
        img = F.ink_over(base, dens)
        title = crop(self.s1_title * soak(self.s1_title_noise, (t - 1.4) / 2.2), y0)
        stamp = ramp(t, 3.7, 4.1)
        if stamp > 0:
            img = F.paint(img, crop(self.s1_seal, y0), F.SEAL, 0.92 * stamp)
        img = F.with_text(img, base, title)
        # Песни неба: ноты поднимаются от кита-матери.
        pts = []
        for i in range(10):
            ph = (t * 0.18 + i / 10) % 1
            x = 330 + drift + 260 * (i % 5) / 4 + 40 * math.sin(ph * 6 + i)
            y = 1700 - y0 - ph * 520
            if 0 < y < H:
                pts.append((x, y, 6 + 6 * (1 - ph)))
        if pts and t > 8:
            img = F.paint(img, F.note_glyphs(pts, np.random.default_rng(5)), F.GOLD, 0.8 * ramp(t, 8, 9.5))
        # Свиток разворачивается сверху вниз; ниже валика — тёмный шёлк.
        reveal = H * ramp(t, 0.15, 2.4)
        if reveal < H:
            rows = np.arange(H, dtype=np.float32)[:, None, None]
            silk = np.array([46, 38, 44], np.float32) / 255
            vis = F.smoothstep(reveal + 2, reveal - 2, rows)
            img = img * vis + silk * (1 - vis)
            shade = np.exp(-np.clip(reveal - rows, 0, None) / 30) * (rows < reveal) * 0.35
            img *= 1 - shade
            ry = int(reveal)
            img[max(0, ry - 14):min(H, ry + 14)] = np.array([96, 62, 40], np.float32) / 255
            img[max(0, ry - 14):min(H, ry - 10)] *= 1.35
        return img

    # --- сцена 3: Мгла и окаменевшие киты ------------------------------------------------------

    def _gloom(self, rng):
        self.s3_paper = F.paper(rng)
        self.s3_mtn = F.landscape(rng, ((1640, 320, 0.35, 230, 6),), (1700, 1990))
        self.s3_gloom, self.s3_eyes = F.gloom(rng, 540, 560, 500, 330)
        yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
        self.s3_r = np.hypot((xx - 540) / 500, (yy - 560) / 330) + 0.35 * (F.fbm(H, W, 90, rng) - 0.5)
        self.s3_stones = []
        for name, width, x, y, seed in (("stone2", 820, 360, 1250, 3), ("stone1", 640, 760, 1470, 5)):
            before = F.load_render(os.path.join(self.renders, name), width, rng)
            stone = F.load_render(os.path.join(self.renders, name), width, rng, stone=True)
            h0 = stone["dens"].shape[0]
            plain = dict(stone)
            F.pines_on(stone, np.random.default_rng(seed), 2)
            pad = stone["dens"].shape[0] - h0
            stone_only = padded(plain, pad)
            pines = np.clip(stone["dens"] - stone_only["dens"], 0, 1)
            b = padded(before, pad)
            noise = F.fbm(*stone["dens"].shape, 30, rng)
            self.s3_stones.append(dict(before=b, stone=stone_only, pines=pines, pmask=stone["mask"], pad=pad, h0=h0,
                                       noise=noise, x=x, y=y - (h0 + pad) * 0.25))
        self.s3_cloud = F.cloud_ribbon(rng, 1120, 1560, 420, 60, -1)

    def scene_gloom(self, t):
        lt = t - SCENES[1][0]
        dens = self.s3_mtn * 1.0
        grow = 1.35 * ramp(lt, 0.4, 3.6)
        g = self.s3_gloom * (1 - F.smoothstep(grow - 0.3, grow, self.s3_r))
        g *= 0.92 + 0.08 * math.sin(lt * 2.1)
        dens = np.maximum(dens, g)
        occl = np.zeros((H, W), np.float32)
        for i, st in enumerate(self.s3_stones):
            p = ramp(lt, 3.6 + i * 0.5, 6.0 + i * 0.5)
            m = soak(st["noise"], p)
            body = st["before"]["dens"] * (1 - m) + st["stone"]["dens"] * m
            mask = np.maximum(st["before"]["mask"], st["stone"]["mask"])
            hh = body.shape[0]
            grow_p = ramp(lt, 5.8 + i * 0.5, 7.6 + i * 0.5)
            rows = np.arange(hh, dtype=np.float32)[:, None]
            line = st["pad"] + 4 - grow_p * (st["pad"] + 4)
            pines = st["pines"] * (rows > line)
            body = np.clip(body + pines * 0.95, 0, 1)
            mask = np.maximum(mask, st["pmask"] * (rows > line) * (pines > 0.02))
            blit(dens, occl, body, mask, st["x"], st["y"] + math.sin(lt * 0.8 + i) * 6)
        dens *= self._s3_mist()
        f, l = self.s3_cloud
        dens = dens * (1 - f) + l * 0.7
        eyes = self.s3_eyes * ramp(lt, 2.9, 3.5) * (1 - 0.85 * ramp(math.sin(lt * 3.1) * 0.5 + 0.5, 0.97, 1.0))
        dens = np.clip(dens - eyes * 1.5, 0, 1)
        img = F.ink_over(self.s3_paper, dens)
        img = F.paint(img, F.blur(eyes, 8), F.GOLD, 0.25)
        # Ноты тянутся вихрем в пасть Мглы и исчезают в ней.
        pts = []
        for i in range(34):
            ph = (lt * 0.22 + i / 34) % 1
            if lt < 2.0 and ph > lt / 2.0:
                continue
            ang = ph * 3.2 * math.pi
            rad = (1 - ph) ** 1.3 * 240
            x = 980 + (560 - 980) * ph + math.cos(ang) * rad * 0.7
            y = 1120 + (650 - 1120) * ph + math.sin(ang) * rad * 0.35
            pts.append((x, y, (4 + 12 * (1 - ph)) * min(1, (1 - ph) * 5)))
        img = F.paint(img, F.note_glyphs(pts, np.random.default_rng(7)), F.GOLD, 0.95 * ramp(lt, 0.8, 1.8))
        return img

    def _s3_mist(self):
        if not hasattr(self, "_mist3"):
            self._mist3 = F.mist(1560, 1900, 0.75, np.random.default_rng(1))
        return self._mist3

    # --- сцена 4: мать-кит и яйцо ------------------------------------------------------------------

    def _egg(self, rng):
        self.s4_paper = F.paper(rng)
        self.s4_mtn = F.landscape(rng, ((1600, 420, 0.4, 240, 5), (1850, 360, 0.8, 160, 4)), (1720, 1990))
        self.s4_cloud = F.cloud_ribbon(rng, -60, 1420, 620, 72, 1)
        self.s4_mother = F.load_render(os.path.join(self.renders, "mother"), 980, rng)
        self.s4_stone = F.load_render(os.path.join(self.renders, "mother"), 980, rng, stone=True)
        self.s4_noise = F.fbm(*self.s4_mother["dens"].shape, 34, rng)

    @staticmethod
    def egg_layers(x, y, size, glow, cracks=0.0, split=0.0):
        """Светящееся яйцо: золотое свечение, контур кистью, трещины, половинки скорлупы."""
        gold = Image.new("L", (W, H), 0)
        ink = Image.new("L", (W, H), 0)
        g = ImageDraw.Draw(gold)
        r = size * (2.2 + 0.25 * glow)
        for k in range(12, 0, -1):
            rr = r * k / 12
            g.ellipse((x - rr, y - rr * 1.1, x + rr, y + rr * 1.1), fill=int(255 * (1 - k / 12) ** 1.6 * 0.6))
        halves = ((-1, -split * size * 3.2, -split * 1.8), (1, split * size * 2.6, split * 1.2))
        for side, dy, dx in halves if split > 0 else ((0, 0, 0),):
            pts = []
            a0, a1 = (math.pi, 2 * math.pi) if side == -1 else ((0, math.pi) if side == 1 else (0, 2 * math.pi))
            for a in np.linspace(a0, a1, 40):
                pts.append((x + dx * size + math.cos(a) * size * 0.78, y + dy + math.sin(a) * size))
            g.polygon(pts, fill=255)
            F.stroke(ink, pts + ([pts[0]] if side == 0 else []), size * 0.07, size * 0.05, value=0.9, taper=side != 0)
        if cracks > 0 and split == 0:
            zig = [(x - size * 0.75, y), (x - size * 0.4, y - size * 0.12), (x - size * 0.1, y + size * 0.1),
                   (x + size * 0.2, y - size * 0.1), (x + size * 0.5, y + size * 0.08), (x + size * 0.78, y - size * 0.05)]
            n = max(2, int(len(zig) * cracks))
            F.stroke(ink, zig[:n], size * 0.05, size * 0.04, value=0.95, taper=False)
        ga = np.asarray(gold, np.float32) / 255
        ia = np.asarray(ink, np.float32) / 255
        return F.blur(ga, 1.5), F.blur(ia, 0.7)

    def scene_egg(self, t):
        lt = t - SCENES[2][0]
        dens = self.s4_mtn * 1.0
        occl = np.zeros((H, W), np.float32)
        p = ramp(lt, 3.6, 6.8)
        m = soak(self.s4_noise, p)
        body = self.s4_mother["dens"] * (1 - m) + self.s4_stone["dens"] * m
        bob = math.sin(lt * 1.1) * 8 * (1 - p)
        mx, my = 560, 640 + bob
        blit(dens, occl, body, self.s4_mother["mask"], mx, my)
        fall = ramp(lt, 3.0, 6.2)
        ex = 470 + 70 * fall + math.sin(lt * 2) * 10 * fall
        ey = 820 + 560 * (fall ** 1.6)
        f, l = self.s4_cloud
        dens = dens * (1 - f) + l * 0.85
        img = F.ink_over(self.s4_paper, dens)
        if lt > 1.2:
            appear = ramp(lt, 1.2, 2.4)
            glow, ink = self.egg_layers(ex, ey, 46, 0.5 + 0.5 * math.sin(lt * 4))
            img = F.paint(img, glow, F.GOLD, 0.9 * appear)
            img = F.paint(img, ink, F.INK, 0.9 * appear)
        return img

    # --- сцена 5: китёнок вылупляется и поёт, цвет возвращается --------------------------------

    def _bloom(self, rng):
        base = F.paper(rng)
        dens = F.landscape(rng, ((1350, 460, 0.4, 250, 5), (1570, 420, 0.65, 190, 4), (1830, 360, 0.95, 150, 3)),
                           (1700, 1990))
        f, l = F.cloud_ribbon(rng, 1100, 1150, 460, 64, -1)
        dens = dens * (1 - f) + l * 0.8
        self.s5_paper = base
        self.s5_dens = dens
        calf = F.load_render(os.path.join(self.renders, "calf"), 470, rng)
        self.s5_calf = calf
        self.s5_calf_pos = (560, 880)
        occl = np.zeros((H, W), np.float32)
        withcalf = dens.copy()
        (cy, cx), (ly, lx) = F.put(withcalf, occl, calf, *self.s5_calf_pos)
        self.s5_mouth = (cx.start + calf["alpha"].shape[1] * 0.3, cy.start + calf["alpha"].shape[0] * 0.5)
        self.s5_ink = F.ink_over(base, dens)
        # Цветной мир (как в пробном кадре 3).
        grain = base / F.PAPER
        yy = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
        sky = (1 - yy) * np.array([160, 205, 232]) / 255 + yy * np.array([248, 230, 200]) / 255
        sky = sky * grain * (0.95 + 0.1 * F.fbm(H, W, 200, rng)[..., None])
        d = 1 - np.exp(-2.6 * np.clip(withcalf * (1 - occl), 0, 1.5))
        dark = F.smoothstep(0.55, 0.9, d)
        teal = np.array([62, 132, 128], np.float32) / 255
        col = sky * (1 - d[..., None]) + (teal * (1 - dark[..., None]) + F.INK * dark[..., None]) * d[..., None]
        wc = F.watercolor_whale(calf, rng)
        wa = calf["alpha"][ly, lx][..., None]
        col[cy, cx] = col[cy, cx] * (1 - wa) + wc[ly, lx] * wa
        lines = np.zeros((H, W), np.float32)
        lines[cy, cx] = calf["lines"][ly, lx]
        self.s5_col = F.paint(col, lines, F.INK, 0.9)
        self.s5_ink_calf = F.ink_over(base, withcalf)
        yyg, xxg = np.mgrid[0:H, 0:W].astype(np.float32)
        r = np.hypot(xxg - self.s5_mouth[0] - 120, (yyg - self.s5_mouth[1] - 120) * 0.75) / 700
        r += 0.5 * (F.fbm(H, W, 320, rng) - 0.5) + 0.16 * (F.fbm(H, W, 90, rng) - 0.5) + 0.04 * (F.fbm(H, W, 20, rng) - 0.5)
        self.s5_r = r

    def scene_bloom(self, t):
        lt = t - SCENES[3][0]
        cx, cy = self.s5_calf_pos
        hatch = ramp(lt, 2.5, 3.4)
        if hatch < 1:
            img = self.s5_ink.copy()
            # Китёнок проступает из яйца.
            if hatch > 0:
                c = self.s5_calf
                scale = 0.6 + 0.4 * hatch
                hh, ww = c["dens"].shape
                size = (max(2, int(ww * scale)), max(2, int(hh * scale)))
                dl = np.asarray(Image.fromarray(c["dens"]).resize(size, Image.BILINEAR))
                ml = np.asarray(Image.fromarray(c["mask"]).resize(size, Image.BILINEAR))
                dens = self.s5_dens.copy()
                blit(dens, None, dl, ml, cx, cy + 40 * (1 - hatch), alpha=hatch)
                img = F.ink_over(self.s5_paper, dens)
        else:
            img = self.s5_ink_calf.copy()
        if lt < 4.2:
            cracks = ramp(lt, 1.4, 2.3)
            split = ramp(lt, 2.4, 3.6)
            fade = 1 - ramp(lt, 2.7, 3.6)
            glow, ink = self.egg_layers(cx, cy + 20, 70, 0.5 + 0.5 * math.sin(lt * 5), cracks, split)
            img = F.paint(img, glow, F.GOLD, 0.9 * fade)
            img = F.paint(img, ink, F.INK, 0.9 * fade)
        # Песня: цвет разливается от китёнка, кольца мокрого края.
        if lt > 5.2:
            edge = 0.05 + 1.0 * ramp(lt, 5.2, 9.0) + 1.6 * ramp(lt, 9.0, 10.6)
            bloom = (1 - F.smoothstep(edge - 0.04, edge + 0.02, self.s5_r)) * (1 - 0.35 * F.smoothstep(0.3, max(0.31, edge), self.s5_r))
            rings = np.exp(-((self.s5_r - edge) / 0.01) ** 2) * 0.18 + np.exp(-((self.s5_r - edge + 0.12) / 0.008) ** 2) * 0.07
            col = self.s5_col * (1 - rings[..., None] * 0.5)
            img = img * (1 - bloom[..., None]) + col * bloom[..., None]
            mx, my = self.s5_mouth
            pts = []
            for i in range(18):
                ph = (lt * 0.25 + i / 18) % 1
                ang = -math.pi * (0.08 + 0.84 * ((i * 0.618) % 1))
                rr = 60 + ph * 520
                pts.append((mx + 40 + math.cos(ang) * rr * 1.2, my - 60 + math.sin(ang) * rr * 0.8, (6 + 9 * (1 - ph)) * min(1, (1 - ph) * 4)))
            img = F.paint(img, F.note_glyphs(pts, np.random.default_rng(3)), F.GOLD, 0.9 * ramp(lt, 5.0, 5.8))
        # В конце ролик растворяется в небе игры.
        img = img * (1 - ramp(lt, 9.8, 11.2)) + GAME_SKY * ramp(lt, 9.8, 11.2)
        return img

    # --- кадр ---------------------------------------------------------------------------------

    def scene(self, i, t):
        return (self.scene_scroll, self.scene_gloom, self.scene_egg, self.scene_bloom)[i](t)

    def frame(self, t):
        active = [i for i, (a, b) in enumerate(SCENES) if a <= t < b] or [len(SCENES) - 1]
        if len(active) == 1:
            img = self.scene(active[0], t)
        else:
            i, j = active[0], active[1]
            a, b = SCENES[j][0], SCENES[i][1]
            p = (t - a) / (b - a)
            # Мягкое растворение пятнами, как тушь, которая расходится по мокрой бумаге.
            m = F.smoothstep(self.trans_noise - 0.3, self.trans_noise + 0.05, ease(p) * 1.35)[..., None]
            img = self.scene(i, t) * (1 - m) + self.scene(j, t) * m
        base = img
        for a, b, layer, noise in self.subs:
            w = window(t, a, b)
            if w > 0:
                img = F.with_text(img, base, layer * soak(noise, (t - a) / 0.9) * w)
        return np.clip(img, 0, 1)


_TL = None


def _init(renders, fonts):
    global _TL
    _TL = Timeline(renders, fonts)


def _render(args):
    idx, out = args
    img = _TL.frame(idx / FPS)
    Image.fromarray((img * 255).astype(np.uint8)).save(os.path.join(out, f"{idx:05d}.jpg"), quality=93)
    return idx


if __name__ == "__main__":
    pos = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=", 1) for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    renders, fonts, out = pos[:3]
    os.makedirs(out, exist_ok=True)
    n = int(TOTAL * FPS)
    start, end = (int(x) for x in opts.get("only", f"0:{n}").split(":"))
    jobs = [(i, out) for i in range(start, min(end, n))]
    with Pool(int(opts.get("workers", 4)), initializer=_init, initargs=(renders, fonts)) as pool:
        for k, idx in enumerate(pool.imap_unordered(_render, jobs, chunksize=4)):
            if k % 48 == 0:
                print("frame", idx, flush=True)
    print("DONE", len(jobs))

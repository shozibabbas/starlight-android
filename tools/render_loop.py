"""Renders a seamless 12 s Milky Way loop for the HyperOS lock screen.

Usage: python3 render_loop.py <frames_dir>
Every motion completes whole cycles over the loop, so the last frame flows into the first.
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw

W, H = 1220, 2712          # Redmi Note 13 Pro+ screen
FPS, SECONDS = 30, 12
N = FPS * SECONDS
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "milkyway-raw.png")


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    src = Image.open(SRC).convert("RGB")
    cover = max(W / src.width, H / src.height)
    rng = random.Random(7)
    # x, y, radius px, phase, cycles per loop
    stars = [(rng.random() * W, rng.random() * H, 1.2 + rng.random() * 2.8,
              rng.random() * math.tau, rng.choice([1, 2, 3, 4])) for _ in range(140)]

    for i in range(N):
        p = i / N                    # 0..1 around the loop
        a = p * math.tau
        zoom = cover * 1.16 * (1 + 0.025 * math.sin(a))
        sw, sh = src.width * zoom, src.height * zoom
        # Drift on a closed ellipse around the centre of the spare margin.
        ox = (sw - W) * (0.5 + 0.32 * math.cos(a))
        oy = (sh - H) * (0.5 + 0.32 * math.sin(a))
        # Affine map from output pixels back into the source image.
        frame = src.transform((W, H), Image.AFFINE,
                              (1 / zoom, 0, ox / zoom, 0, 1 / zoom, oy / zoom),
                              resample=Image.BICUBIC)

        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        for x, y, r, ph, cycles in stars:
            tw = 0.5 + 0.5 * math.sin(ph + a * cycles)
            alpha = int(235 * tw ** 3)
            if alpha < 8:
                continue
            draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, alpha))
            if r > 3.2 and alpha > 120:   # a soft glint on the biggest stars
                g = r * 3
                draw.ellipse((x - g, y - g, x + g, y + g), fill=(200, 210, 255, alpha // 8))

        # One shooting star per loop, from 5.0 s to 5.9 s.
        t = p * SECONDS
        if 5.0 <= t <= 5.9:
            q = (t - 5.0) / 0.9
            fade = math.sin(q * math.pi)
            hx, hy = W * (0.88 - 0.5 * q), H * (0.08 + 0.14 * q)
            tx, ty = hx + W * 0.11, hy - H * 0.03
            for k in range(14):
                f = k / 13
                px, py = hx + (tx - hx) * f, hy + (ty - hy) * f
                rr = 3.2 * (1 - f) + 0.6
                draw.ellipse((px - rr, py - rr, px + rr, py + rr),
                             fill=(255, 255, 255, int(230 * fade * (1 - f) ** 1.5)))

        frame = Image.alpha_composite(frame.convert("RGBA"), layer).convert("RGB")
        frame.save(os.path.join(out_dir, f"f{i:04d}.png"), compress_level=1)
        if i % 60 == 0:
            print(f"frame {i}/{N}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])

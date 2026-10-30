"""Generates always-on-display wallpapers with Gemini and fits them to the phone.

Usage: python3 generate.py [concept ...]     (default: every concept)
The API key is read from the GEMINI_KEY_FILE environment variable (a file path).
"""
import base64
import io
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image

MODEL = "gemini-3-pro-image"
SCREEN = (1220, 2712)  # Redmi Note 13 Pro+ physical resolution
OUT = os.path.dirname(os.path.abspath(__file__))

RULES = (
    "Portrait phone wallpaper made for an AMOLED always-on display. "
    "The background must be absolute pure black (#000000) with no gradient, haze, stars, "
    "vignette or texture anywhere. A single luminous subject glows against the black, "
    "covering no more than about 20 percent of the frame. Keep the top 35 percent of the "
    "image completely empty black, because a clock is drawn there. Keep the subject "
    "horizontally centred within the middle half of the width, since the left and right "
    "edges will be cropped. Exquisite detail, fine glowing light, cinematic, elegant and "
    "minimal. No text, no letters, no numbers, no watermark, no border, no frame."
)

FULL_RULES = (
    "Portrait phone wallpaper for an AMOLED always-on display, filling the entire frame "
    "edge to edge. Deep pure black sky (#000000 between the stars). Keep it dark and "
    "low-key overall so most pixels stay black: the light comes from fine pin-sharp stars "
    "and soft, subtle glow. The upper third should be the calmest and darkest part, with "
    "only sparse small stars, because a large clock and the date sit there. Astrophotography "
    "quality, elegant and minimal. No landscape, no horizon, no people, no planets, no text, "
    "no letters, no numbers, no watermark, no border."
)

FULL_CONCEPTS = {
    "milkyway": "The Milky Way galactic core as a luminous band sweeping diagonally from the "
                "lower left to the middle right, with delicate dust lanes and soft "
                "blue-violet and warm amber tones, surrounded by countless tiny stars.",
    "starfield": "A deep, quiet starfield of thousands of tiny pin-sharp stars in varied "
                 "sizes and soft natural colours, with a faint wisp of violet and teal "
                 "nebula drifting across the lower half.",
    "milkyway-vertical": "The Milky Way rising vertically from the bottom edge, faint and "
                         "ethereal, its glowing core near the bottom and fading to a sparse "
                         "field of tiny stars towards the top.",
}

CONCEPTS = {
    "jellyfish": "A single bioluminescent jellyfish drifting upward, its translucent bell "
                 "glowing soft cyan and violet, long delicate tendrils trailing down like "
                 "threads of light.",
    "eclipse": "A total solar eclipse: a perfectly black moon disc ringed by a thin, "
               "brilliant golden-white corona with faint wispy solar streamers and one "
               "diamond-ring flare of light on its edge.",
    "karakoram": "A mountain range in the style of the Karakoram, with a tall K2-like peak "
                 "at the centre, drawn only as a single thin glowing contour line of warm "
                 "amber light tracing the ridges, with a few fainter parallel contour lines "
                 "below it.",
    "dandelion": "A single dandelion seed head glowing soft white-gold, with a few seeds "
                 "breaking away and drifting upward as tiny points of light.",
}


def prompt(name):
    if name in FULL_CONCEPTS:
        return f"{FULL_CONCEPTS[name]} {FULL_RULES}"
    return f"{CONCEPTS[name]} {RULES}"


def generate(name):
    key = open(os.environ["GEMINI_KEY_FILE"]).read().strip()
    body = {
        "contents": [{"parts": [{"text": prompt(name)}]}],
        "generationConfig": {
            "responseModalities": ["IMAGE"],
            "imageConfig": {"aspectRatio": "9:16", "imageSize": "2K"},
        },
    }
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": key},
    )
    with urllib.request.urlopen(req, timeout=300) as r:
        resp = json.load(r)

    parts = resp["candidates"][0]["content"]["parts"]
    data = next(p["inlineData"]["data"] for p in parts if "inlineData" in p)
    raw = Image.open(io.BytesIO(base64.b64decode(data))).convert("RGB")
    raw.save(os.path.join(OUT, f"{name}-raw.png"))
    fit(raw).save(os.path.join(OUT, f"{name}.png"))
    return f"{name}: {raw.size} -> {SCREEN}"


def fit(img):
    """Scales to the screen height, crops the sides, and crushes near-black to true black."""
    w, h = img.size
    scale = max(SCREEN[0] / w, SCREEN[1] / h)
    img = img.resize((round(w * scale), round(h * scale)), Image.LANCZOS)
    left = (img.width - SCREEN[0]) // 2
    top = (img.height - SCREEN[1]) // 2
    img = img.crop((left, top, left + SCREEN[0], top + SCREEN[1]))
    # Near-black haze is invisible on the AOD but keeps OLED subpixels lit. Pull the
    # black point up with a smooth ramp so soft glows fade out instead of being cut off.
    floor = 6
    return img.point(lambda v: 0 if v <= floor else round((v - floor) * 255 / (255 - floor)))


if __name__ == "__main__":
    if sys.argv[1:2] == ["--refit"]:  # re-crop the saved raw images without calling Gemini
        for n in sys.argv[2:] or list(CONCEPTS):
            fit(Image.open(os.path.join(OUT, f"{n}-raw.png")).convert("RGB")).save(os.path.join(OUT, f"{n}.png"))
        sys.exit()
    names = sys.argv[1:] or list(CONCEPTS)
    with ThreadPoolExecutor(len(names)) as pool:
        for future in [pool.submit(generate, n) for n in names]:
            try:
                print(future.result())
            except Exception as e:
                print(f"FAILED: {e}")

"""
Optimize launcher icon art for APK size without visible quality loss.

Telegram stock icons used XML gradients + tiny foregrounds — that cannot
reproduce photographic/illustrated full-bleed icons. Keep the art approach,
but downscale to ~2x xxxhdpi adaptive needs and encode as high-quality WebP.
Also regenerate density mipmap fallbacks from the same source.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

RES = Path(r"C:\Glass2\TMessagesProj\src\main\res")
NODPI = RES / "drawable-nodpi"
DRAWABLE = RES / "drawable"

# Adaptive icon layer is 108dp; xxxhdpi = 432px. 864 = 2x oversample.
ART_SIZE = 864
ART_WEBP_QUALITY = 92
FG_SIZE = 768
FG_WEBP_QUALITY = 92

MIPMAP_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

DR_SIZES = {
    "drawable-mdpi": 48,
    "drawable-hdpi": 72,
    "drawable-xhdpi": 96,
    "drawable-xxhdpi": 144,
    "drawable-xxxhdpi": 192,
}

ART_TO_LAUNCHERS = {
    "osnova": ["ic_launcher"],
    "dymka": ["dymka_launcher"],
    "gradient": ["gradient_launcher"],
    "zhidkost": ["zhidkost_launcher"],
    "green": ["green_launcher"],
    "perlamutr": ["perlamutr_launcher"],
    "ufc": ["ufc_launcher"],
    "icon_3d": ["icon_3d_launcher"],
    "abstraction": ["abstraction_launcher"],
    "it": ["it_launcher"],
    "race": ["race_launcher"],
    "summer": ["summer_launcher"],
    "winter": ["winter_launcher"],
    "girls": ["girls_launcher"],
    "red": ["red_launcher"],
    "black": ["black_launcher"],
    "realistic": ["realistic_launcher"],
    "paper": ["paper_launcher"],
}


def human(n: int) -> str:
    if n >= 1024 * 1024:
        return f"{n / (1024 * 1024):.2f} MB"
    if n >= 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n} B"


def prepare(im: Image.Image, size: int) -> Image.Image:
    if im.mode not in ("RGB", "RGBA"):
        im = im.convert("RGBA" if "A" in im.getbands() else "RGB")
    w, h = im.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        im = im.crop((left, top, left + side, top + side))
    if im.size != (size, size):
        im = im.resize((size, size), Image.Resampling.LANCZOS)
    return im


def save_webp(im: Image.Image, path: Path, quality: int) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, "WEBP", quality=quality, method=6)
    return path.stat().st_size


def has_useful_alpha(im: Image.Image) -> bool:
    if im.mode != "RGBA":
        return False
    return im.getchannel("A").getextrema()[0] < 255


def optimize_art_files() -> dict[str, Image.Image]:
    arts: dict[str, Image.Image] = {}
    total_before = total_after = 0
    for png in sorted(NODPI.glob("*_icon_art.png")):
        stem = png.name[: -len("_icon_art.png")]
        b = png.stat().st_size
        im = prepare(Image.open(png), ART_SIZE)
        out = NODPI / f"{stem}_icon_art.webp"
        a = save_webp(im, out, ART_WEBP_QUALITY)
        png.unlink()
        arts[stem] = im.copy()
        total_before += b
        total_after += a
        print(f"  art {stem}: {human(b)} -> {human(a)}")
    print(f"  ART TOTAL: {human(total_before)} -> {human(total_after)}")
    return arts


def optimize_foregrounds() -> None:
    total_before = total_after = 0
    for name in ("simple_foreground", "white_foreground"):
        png = DRAWABLE / f"{name}.png"
        if not png.exists():
            continue
        b = png.stat().st_size
        im = prepare(Image.open(png), FG_SIZE)
        out = DRAWABLE / f"{name}.webp"
        a = save_webp(im, out, FG_WEBP_QUALITY)
        png.unlink()
        total_before += b
        total_after += a
        print(f"  fg {name}: {human(b)} -> {human(a)}")
    if total_before:
        print(f"  FG TOTAL: {human(total_before)} -> {human(total_after)}")


def regenerate_mipmaps(arts: dict[str, Image.Image]) -> None:
    total_before = total_after = 0
    for stem, launchers in ART_TO_LAUNCHERS.items():
        im = arts.get(stem)
        if im is None:
            webp = NODPI / f"{stem}_icon_art.webp"
            if not webp.exists():
                print(f"  skip mipmaps for {stem}: no art")
                continue
            im = prepare(Image.open(webp), ART_SIZE)

        for launcher in launchers:
            for folder, size in MIPMAP_SIZES.items():
                d = RES / folder
                for suffix in ("", "_round"):
                    old_png = d / f"{launcher}{suffix}.png"
                    old_webp = d / f"{launcher}{suffix}.webp"
                    if old_png.exists():
                        total_before += old_png.stat().st_size
                        old_png.unlink()
                    if old_webp.exists():
                        total_before += old_webp.stat().st_size
                        old_webp.unlink()
                    small = prepare(im, size)
                    if small.mode == "RGBA" and not has_useful_alpha(small):
                        small = small.convert("RGB")
                    total_after += save_webp(small, d / f"{launcher}{suffix}.webp", 90)

            if launcher == "ic_launcher":
                for folder, size in DR_SIZES.items():
                    d = RES / folder
                    old_png = d / "ic_launcher_dr.png"
                    old_webp = d / "ic_launcher_dr.webp"
                    if old_png.exists():
                        total_before += old_png.stat().st_size
                        old_png.unlink()
                    if old_webp.exists():
                        total_before += old_webp.stat().st_size
                        old_webp.unlink()
                    small = prepare(im, size)
                    if small.mode == "RGBA" and not has_useful_alpha(small):
                        small = small.convert("RGB")
                    total_after += save_webp(small, d / "ic_launcher_dr.webp", 90)

    print(f"  MIPMAP TOTAL: {human(total_before)} -> {human(total_after)}")


def main() -> None:
    print("Optimizing icon art -> WebP…")
    arts = optimize_art_files()
    print("Optimizing Simple/White foregrounds…")
    optimize_foregrounds()
    print("Regenerating mipmap fallbacks…")
    regenerate_mipmaps(arts)
    print("Done.")


if __name__ == "__main__":
    main()

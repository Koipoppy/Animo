#!/usr/bin/env python3
"""Generate the Android launcher icon set from the app-icon artwork.

The source is a square illustration exported with a white frame and rounded
corners.  This script:

  1. fits the artwork's rounded rectangle with a signed-distance fit,
  2. crops past the white frame/glow and extends the edge colours into the cut
     corners, so nothing white is left in the launcher's corners,
  3. renders every density: legacy square/round PNGs and adaptive layers.

The icon is the artwork edge to edge: the launcher supplies the shape, so there
is no second frame inside the icon.  Adaptive layers are 108dp while only their
central 72dp viewport is shown, so the artwork is drawn at 2/3 of the canvas
(with its edge colours extended into the invisible bleed) and the foreground
layer is left empty.

Usage:
    python -I tools/make-app-icons.py
    python -I tools/make-app-icons.py --preview icon-preview.png
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy.ndimage import distance_transform_edt, gaussian_filter
from scipy.optimize import least_squares

# The artwork has a white glow just inside its rounded frame; crop past it so no
# white outline (or its glow) shows at the icon edge.
CROP_INSET = 12.0
# Launchers show the central 72dp of the 108dp adaptive canvas; the outer 18dp is
# parallax bleed.  Drawing the art at 2/3 of the layer makes it edge-to-edge.
VIEWPORT_SCALE = 72.0 / 108.0
# Blur (px of the source artwork) used to soften the synthetic corner fill.
CORNER_SMOOTH = 40.0

# density: (legacy icon px, adaptive layer px)   [48dp icon, 108dp canvas]
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

WHITE_TOLERANCE = 10  # per-channel distance from the white frame still "white"


def _is_white(px: np.ndarray, ref: np.ndarray) -> np.ndarray:
    return np.abs(px - ref).max(axis=-1) <= WHITE_TOLERANCE


def _scan_boundary(profile: np.ndarray, ref: np.ndarray) -> int | None:
    """First index where the profile stops being the white frame."""
    hit = ~_is_white(profile, ref)
    # require two consecutive artwork pixels so JPEG speckle is ignored
    idx = np.flatnonzero(hit[:-1] & hit[1:])
    if len(idx) == 0:
        return None
    return int(idx[0])


def _corner_samples(rgb: np.ndarray, box: int):
    """Boundary samples for each corner, scanned from the white frame inward."""
    height, width, _ = rgb.shape
    corners = {
        "tl": (slice(0, box), slice(0, box)),
        "tr": (slice(0, box), slice(width - box, width)),
        "bl": (slice(height - box, height), slice(0, box)),
        "br": (slice(height - box, height), slice(width - box, width)),
    }
    ref = np.array([254.0, 254.0, 254.0])
    samples = {key: [] for key in corners}
    steps = range(8, box - 8, 3)
    for key, (ys, xs) in corners.items():
        sub = rgb[ys, xs]
        if key[1] == "r":
            sub = sub[:, ::-1]
        if key[0] == "b":
            sub = sub[::-1]
        for i in steps:
            j = _scan_boundary(sub[i], ref)
            if j is not None:
                samples[key].append((
                    xs.start + j if key[1] == "l" else xs.stop - 1 - j,
                    ys.start + i if key[0] == "t" else ys.stop - 1 - i,
                ))
            j = _scan_boundary(sub[:, i], ref)
            if j is not None:
                samples[key].append((
                    xs.start + i if key[1] == "l" else xs.stop - 1 - i,
                    ys.start + j if key[0] == "t" else ys.stop - 1 - j,
                ))
    return samples


def _sdf_points(xs, ys, rect, width, height):
    left, top = rect["left"], rect["top"]
    right, bottom = width - rect["right"], height - rect["bottom"]
    cx, cy = (left + right) / 2, (top + bottom) / 2
    half_w, half_h = (right - left) / 2, (bottom - top) / 2
    tl, tr, br, bl = rect["radii"]
    rad = np.where(ys < cy, np.where(xs < cx, tl, tr), np.where(xs < cx, bl, br))
    qx = np.abs(xs - cx) - (half_w - rad)
    qy = np.abs(ys - cy) - (half_h - rad)
    outside = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0))
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - rad


def fit_geometry(rgb: np.ndarray) -> dict:
    """Fit the artwork's rounded rectangle: insets plus a radius per corner."""
    height, width, _ = rgb.shape
    samples = _corner_samples(rgb, box=min(340, height // 2, width // 2))
    keys = ["tl", "tr", "bl", "br"]
    counts = [len(samples[k]) for k in keys]
    if min(counts) < 12:
        raise SystemExit(f"could not locate the rounded frame (samples: {counts})")
    pts = {k: np.array(samples[k], dtype=float) for k in keys}
    all_pts = np.concatenate([pts[k] for k in keys])

    def to_rect(p):
        return {
            "left": p[0], "top": p[1], "right": p[2], "bottom": p[3],
            "radii": list(p[4:]),
        }

    def residuals(p):
        return _sdf_points(all_pts[:, 0], all_pts[:, 1], to_rect(p), width, height)

    guess = [6.0, 6.0, 6.0, 6.0] + [0.22 * min(width, height)] * 4
    lower = [0.0, 0.0, 0.0, 0.0] + [40.0] * 4
    upper = [80.0] * 4 + [0.45 * min(width, height)] * 4
    result = least_squares(residuals, guess, bounds=(lower, upper), loss="soft_l1")
    # drop the few samples that latched onto white flowers / clouds, then refit
    keep = np.abs(residuals(result.x)) < 6.0
    if keep.sum() >= 24:
        all_pts = all_pts[keep]
        result = least_squares(residuals, result.x, bounds=(lower, upper))
    rect = to_rect(result.x)
    error = np.abs(residuals(result.x))
    print(
        "fitted rounded frame: "
        f"insets L{rect['left']:.1f} T{rect['top']:.1f} "
        f"R{rect['right']:.1f} B{rect['bottom']:.1f}  "
        + " ".join(f"{k}:{r:.1f}" for k, r in zip(keys, rect["radii"]))
        + f"  ({keep.sum()}/{len(keep)} samples, "
        f"residual mean {error.mean():.2f}px, max {error.max():.2f}px)"
    )
    return rect


def rounded_rect_sdf(shape, rect):
    """Signed distance (px) to the rounded rectangle; negative inside."""
    height, width = shape
    yy, xx = np.mgrid[0:height, 0:width].astype(np.float32) + 0.5
    left, top = rect["left"], rect["top"]
    right, bottom = width - rect["right"], height - rect["bottom"]
    cx, cy = (left + right) / 2, (top + bottom) / 2
    half_w, half_h = (right - left) / 2, (bottom - top) / 2
    tl, tr, br, bl = rect["radii"]
    rad = np.where(
        yy < cy,
        np.where(xx < cx, tl, tr),
        np.where(xx < cx, bl, br),
    ).astype(np.float32)
    qx = np.abs(xx - cx) - (half_w - rad)
    qy = np.abs(yy - cy) - (half_h - rad)
    outside = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0))
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - rad


def crop_and_fill(rgb: np.ndarray, rect: dict) -> Image.Image:
    """Crop away the white frame and glow, then extend edge colours outward."""
    width = rgb.shape[1]
    left, top = rect["left"], rect["top"]
    right, bottom = width - rect["right"], rgb.shape[0] - rect["bottom"]
    center_x, center_y = (left + right) / 2, (top + bottom) / 2
    side = min(right - left, bottom - top) - 2 * CROP_INSET
    x0 = int(round(center_x - side / 2))
    y0 = int(round(center_y - side / 2))
    cropped = rgb[y0:y0 + int(side), x0:x0 + int(side)]
    radii = [max(1.0, r - CROP_INSET) for r in rect["radii"]]
    sdf = rounded_rect_sdf(cropped.shape[:2], {
        "left": 0.0,
        "top": 0.0,
        "right": 0.0,
        "bottom": 0.0,
        "radii": radii,
    })
    inside = sdf <= 0
    # Nearest inside pixel for every outside pixel: the fill continues the edge
    # colours exactly where it meets the artwork.
    _, indices = distance_transform_edt(~inside, return_indices=True)
    filled = cropped[indices[0], indices[1]]
    # Rays radiating from the arc look artificial; fade the fill into a blurred
    # copy of itself just outside the artwork, leaving the edge itself sharp.
    smooth = gaussian_filter(filled, sigma=(CORNER_SMOOTH, CORNER_SMOOTH, 0))
    weight = np.clip(sdf / CORNER_SMOOTH, 0, 1)[..., None]
    filled = filled * (1 - weight) + smooth * weight
    return Image.fromarray(np.clip(filled, 0, 255).astype(np.uint8), "RGB")


def render(art: Image.Image, size: int) -> Image.Image:
    """Full-bleed square icon for legacy launchers and previews."""
    return art.resize((size, size), Image.LANCZOS)


def _extend_outside(rgba: np.ndarray, blur: float) -> np.ndarray:
    """Replace transparent pixels with the nearest opaque colour, then soften."""
    solid = rgba[:, :, 3] >= 250
    _, indices = distance_transform_edt(~solid, return_indices=True)
    extended = rgba[indices[0], indices[1], :3].astype(np.float32)
    smooth = gaussian_filter(extended, sigma=(blur, blur, 0))
    distance = distance_transform_edt(~solid)[..., None]
    weight = np.clip(distance / max(blur, 1.0), 0, 1)
    return extended * (1 - weight) + smooth * weight


def adaptive_background(art: Image.Image, size: int) -> Image.Image:
    """Artwork filling the 72dp viewport, with the bleed area extended from it."""
    inner = max(1, int(round(size * VIEWPORT_SCALE)))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.alpha_composite(
        art.resize((inner, inner), Image.LANCZOS).convert("RGBA"),
        ((size - inner) // 2, (size - inner) // 2),
    )
    extended = _extend_outside(np.asarray(layer), size * 0.02)
    return Image.fromarray(np.clip(extended, 0, 255).astype(np.uint8), "RGB")


def circle_mask(size: int, ss: int = 4) -> Image.Image:
    big = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def squircle_mask(size: int, ss: int = 4) -> Image.Image:
    """Android-ish squircle used only for previews."""
    n = size * ss
    sdf = rounded_rect_sdf((n, n), {
        "left": 0.0, "top": 0.0, "right": 0.0, "bottom": 0.0,
        "radii": [0.28 * n] * 4,
    })
    alpha = np.clip(0.5 - sdf, 0, 1)
    return Image.fromarray((alpha * 255).astype(np.uint8), "L").resize(
        (size, size), Image.LANCZOS
    )


def write_icons(art: Image.Image, res: Path) -> None:
    for density, (legacy_px, adaptive_px) in DENSITIES.items():
        folder = res / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)

        # Adaptive layers: the artwork is the background (already at viewport
        # scale); nothing is drawn in front of it.
        adaptive_background(art, adaptive_px).save(folder / "ic_launcher_bg.png")
        Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0)).save(
            folder / "ic_launcher_foreground.png"
        )

        icon = render(art, legacy_px)
        icon.save(folder / "ic_launcher.png")
        round_icon = icon.convert("RGBA")
        round_icon.putalpha(circle_mask(legacy_px))
        round_icon.save(folder / "ic_launcher_round.png")
        print(f"wrote mipmap-{density} ({legacy_px}px icon, {adaptive_px}px adaptive)")


def write_preview(art: Image.Image, target: Path) -> None:
    tile = 320
    # Simulate a launcher: only the central 72dp viewport of the 108dp layer is
    # shown before the launcher mask is applied.
    layer = adaptive_background(art, 432)
    edge = int(round(432 * (1 - VIEWPORT_SCALE) / 2))
    rendered = layer.crop((edge, edge, 432 - edge, 432 - edge)).resize(
        (tile, tile), Image.LANCZOS
    )
    variants = [
        ("square", None),
        ("circle", circle_mask(tile)),
        ("squircle", squircle_mask(tile)),
    ]
    small = render(art, 48)
    sheet = Image.new("RGB", (tile * len(variants), tile + 34 + 120), (232, 228, 224))
    font = ImageFont.load_default(size=18)
    draw = ImageDraw.Draw(sheet)
    for i, (label, mask) in enumerate(variants):
        icon = rendered.convert("RGBA")
        if mask is not None:
            icon.putalpha(mask)
        sheet.paste(icon, (i * tile, 34), icon)
        draw.text((i * tile + 8, 8), label, font=font, fill=(40, 40, 40))
    # 48dp legibility check, shown at 4x
    draw.text((8, tile + 44), "48dp (4x nearest)", font=font, fill=(40, 40, 40))
    sheet.paste(small.resize((192, 192), Image.NEAREST), (8, tile + 74))
    target.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(target)
    print(f"preview -> {target}")


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=root / "tools" / "app-icon" / "source.jpg",
        help="square artwork with a white frame around rounded corners",
    )
    parser.add_argument(
        "--res",
        type=Path,
        default=root / "android" / "app" / "src" / "main" / "res",
        help="Android res directory to write mipmap-* folders into",
    )
    parser.add_argument("--preview", type=Path, help="optional contact sheet PNG")
    args = parser.parse_args()

    rgb = np.asarray(Image.open(args.source).convert("RGB")).astype(np.float32)
    rect = fit_geometry(rgb)
    art = crop_and_fill(rgb, rect)
    write_icons(art, args.res)
    if args.preview:
        write_preview(art, args.preview)


if __name__ == "__main__":
    main()

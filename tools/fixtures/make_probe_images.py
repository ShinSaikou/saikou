#!/usr/bin/env python3
"""Writes the six images ImageProbeDeviceTest reads from `<fixtures>/reader/probe/`.

Ruling R49. `android.graphics` has no encoder for a GIF, an animated WEBP, an AVIF, a HEIF or a
JXL, and its WEBP encoder needs an API level above the module's minimum, so the device test reads
those six routes from the pushed fixture tree. Under `saikou.strict` a missing one fails the test,
and nothing about them needs the owner: they are drawn here from nothing, a few flat shapes at
fixed sizes, so no file can say where a page came from. The same Pillow, libavif, x265 and libjxl
write the same bytes on every run.

    animated.gif    three frames, the animated route on every API level
    static.webp     one lossless frame, a plain RIFF VP8L file, the region route
    animated.webp   three frames in a VP8X file with its animation flag set, the animated route from
                    API 28 and the region route below it
    still.avif      one frame whose `ftyp` brand is `avif`, the region route
    still.heic      one frame whose `ftyp` brand is `heic`, the region route
    still.jxl       one frame as a bare JXL codestream, the region route

Needs Pillow (12.3.0 when this was written), `heif-enc` with an HEVC encoder, and `cjxl`. AVIF goes
through Pillow when its build has the encoder and through `avifenc` otherwise. Every encoder is
checked, and the two command line encodes made, before anything is written, so a missing one leaves
no half-filled directory behind for the gate to mistake for the owner's.

Usage: make_probe_images.py <out dir>
Exit codes: 0 ok; 2 usage; 4 a required encoder is missing.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

WIDTH = 96
HEIGHT = 64
# Three frames that differ, so no encoder can fold them into one and write a still image instead.
FRAME_COLOURS = [(200, 40, 40), (40, 160, 60), (40, 70, 200)]
FRAME_MS = 120
NAMES = ("animated.gif", "static.webp", "animated.webp", "still.avif", "still.heic", "still.jxl")


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def frames(image_module, draw_module) -> list:
    out = []
    for index, colour in enumerate(FRAME_COLOURS):
        frame = image_module.new("RGB", (WIDTH, HEIGHT), (250, 250, 250))
        draw = draw_module.Draw(frame)
        left = 8 + index * 24
        draw.rectangle((left, 12, left + 24, HEIGHT - 12), fill=colour)
        out.append(frame)
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        die(2, "usage: make_probe_images.py <out dir>")
    out = Path(argv[0])

    try:
        from PIL import Image, ImageDraw, features
    except ImportError:
        die(4, "missing: Pillow (pip install --user pillow)")
    if not features.check("webp"):
        die(4, "missing: WEBP support in this Pillow build")
    pillow_avif = features.check("avif")
    avifenc = shutil.which("avifenc")
    if not pillow_avif and avifenc is None:
        die(4, "missing: an AVIF encoder (a Pillow built with libavif, or avifenc on the PATH)")
    heif_enc = shutil.which("heif-enc")
    if heif_enc is None:
        die(4, "missing: heif-enc (libheif's command line encoder)")
    cjxl = shutil.which("cjxl")
    if cjxl is None:
        die(4, "missing: cjxl (libjxl's command line encoder)")

    still, *rest = frames(Image, ImageDraw)
    with tempfile.TemporaryDirectory() as scratch:
        png = Path(scratch) / "still.png"
        still.save(png)
        heic = Path(scratch) / "still.heic"
        jxl = Path(scratch) / "still.jxl"
        try:
            # HEVC by default; libheif picks AV1 only when asked.
            subprocess.run([heif_enc, "-q", "60", "-o", str(heic), str(png)], check=True, capture_output=True)
            subprocess.run([cjxl, str(png), str(jxl), "-q", "90", "-e", "3", "--num_threads=0"],
                           check=True, capture_output=True)
        except subprocess.CalledProcessError as failure:
            die(4, f"an encoder failed: {failure.cmd[0]}: {failure.stderr.decode(errors='replace').strip()}")

        out.mkdir(parents=True, exist_ok=True)
        still.save(out / "animated.gif", save_all=True, append_images=rest, duration=FRAME_MS, loop=0,
                   optimize=False)
        still.save(out / "static.webp", lossless=True, exact=True)
        still.save(out / "animated.webp", save_all=True, append_images=rest, duration=FRAME_MS, loop=0,
                   lossless=True, exact=True)
        if pillow_avif:
            still.save(out / "still.avif", quality=60, speed=6, max_threads=1)
        else:
            subprocess.run([avifenc, "--jobs", "1", "-q", "60", "-s", "6", str(png), str(out / "still.avif")],
                           check=True, capture_output=True)
        shutil.copyfile(heic, out / "still.heic")
        shutil.copyfile(jxl, out / "still.jxl")
    for name in NAMES:
        print(f"{out / name}: {(out / name).stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

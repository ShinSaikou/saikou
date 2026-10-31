#!/usr/bin/env python3
"""Unit tests for tools/fixtures/make_probe_images.py, the six reader/probe fixtures (R49).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Each image is checked against the rule ImageProbe applies to its first bytes on the device
(reader/src/main/kotlin/com/shinsaikou/reader/pipeline/ImageProbe.kt), because a file the probe sorts
into the wrong format would fail ImageProbeDeviceTest for a reason that has nothing to do with the
reader: a GIF signature; RIFF and WEBP with no VP8X animation flag for the still; VP8X with bit 1
of byte 20 set for the animated WEBP; an `ftyp` brand of `avif` for the AVIF and of `heic` for the
HEIF, the still brands the probe sends to the region path; the JXL codestream marker. The two
animated files must hold more than one frame, every file must have a size, and two runs must write
the same bytes. Skipped where Pillow, heif-enc or cjxl is not installed, which is the machine the
generator itself refuses to run on.

Phase 1's preflight in gate.sh draws them only into a directory that does not exist yet; one that
exists is the owner's. Its check for the files such a directory lacks is read out of gate.sh as
written and run in bash, since a directory the four-image generator wrote lacks the HEIF and the JXL.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
GATE = TOOLS / "ci" / "gate.sh"
_spec = importlib.util.spec_from_file_location("make_probe_images", TOOLS / "fixtures" / "make_probe_images.py")
make_probe_images = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = make_probe_images
_spec.loader.exec_module(make_probe_images)

try:
    from PIL import Image, features
    HAVE_PILLOW = True
except ImportError:
    HAVE_PILLOW = False

NAMES = ("animated.gif", "static.webp", "animated.webp", "still.avif", "still.heic", "still.jxl")
HAVE_ENCODERS = HAVE_PILLOW and shutil.which("heif-enc") is not None and shutil.which("cjxl") is not None


def generate(out: Path) -> int:
    with contextlib.redirect_stdout(io.StringIO()):
        return make_probe_images.main([str(out)])


@unittest.skipUnless(HAVE_ENCODERS, "Pillow, heif-enc or cjxl is not installed")
class ProbeImagesTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory()
        cls.out = Path(cls.scratch.name) / "reader" / "probe"
        assert generate(cls.out) == 0

    @classmethod
    def tearDownClass(cls):
        cls.scratch.cleanup()

    def header(self, name: str) -> bytes:
        return (self.out / name).read_bytes()[:32]

    def test_it_writes_exactly_the_six_files_the_device_test_reads(self):
        self.assertEqual(sorted(p.name for p in self.out.iterdir()), sorted(NAMES))

    def test_the_gif_has_a_gif_signature_and_frames(self):
        self.assertIn(self.header("animated.gif")[:6], (b"GIF87a", b"GIF89a"))
        with Image.open(self.out / "animated.gif") as image:
            self.assertGreater(image.n_frames, 1)

    def test_the_still_webp_is_a_webp_without_the_animation_flag(self):
        header = self.header("static.webp")
        self.assertEqual((header[:4], header[8:12]), (b"RIFF", b"WEBP"))
        animated = header[12:16] == b"VP8X" and header[20] & 0x02 != 0
        self.assertFalse(animated)

    def test_the_animated_webp_carries_the_flag_the_probe_reads(self):
        header = self.header("animated.webp")
        self.assertEqual((header[:4], header[8:12], header[12:16]), (b"RIFF", b"WEBP", b"VP8X"))
        self.assertTrue(header[20] & 0x02)
        with Image.open(self.out / "animated.webp") as image:
            self.assertGreater(image.n_frames, 1)

    def test_the_avif_has_the_brand_the_probe_reads(self):
        header = self.header("still.avif")
        self.assertEqual(header[4:8], b"ftyp")
        # `avis` would be an image sequence, which the probe keeps deferred.
        self.assertEqual(header[8:12], b"avif")

    def test_the_heif_has_a_still_brand(self):
        header = self.header("still.heic")
        self.assertEqual(header[4:8], b"ftyp")
        self.assertIn(header[8:12], (b"heic", b"heix", b"mif1"))

    def test_the_jxl_is_a_bare_codestream(self):
        self.assertEqual(self.header("still.jxl")[:2], b"\xff\x0a")

    def test_every_file_has_a_size(self):
        for name in NAMES:
            # Pillow reads no HEIF or JXL, and AVIF only when its build has libavif.
            if name in ("still.heic", "still.jxl") or name == "still.avif" and not features.check("avif"):
                continue
            with Image.open(self.out / name) as image:
                self.assertGreater(image.width, 0, name)
                self.assertGreater(image.height, 0, name)

    def test_two_runs_write_the_same_bytes(self):
        with tempfile.TemporaryDirectory() as again:
            second = Path(again)
            self.assertEqual(generate(second), 0)
            for name in NAMES:
                self.assertEqual((self.out / name).read_bytes(), (second / name).read_bytes(), name)

    @unittest.skipUnless(shutil.which("avifenc"), "avifenc is not installed")
    def test_without_a_pillow_avif_encoder_avifenc_writes_the_same_kind_of_file(self):
        real = features.check

        def no_avif(feature):
            return False if feature == "avif" else real(feature)

        with tempfile.TemporaryDirectory() as other, mock.patch("PIL.features.check", side_effect=no_avif):
            out = Path(other)
            self.assertEqual(generate(out), 0)
            header = (out / "still.avif").read_bytes()[:12]
        self.assertEqual(header[4:8], b"ftyp")
        self.assertEqual(header[8:12], b"avif")


class UsageTest(unittest.TestCase):

    def test_no_directory_is_a_usage_error(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            make_probe_images.main([])
        self.assertEqual(raised.exception.code, 2)

    @unittest.skipUnless(HAVE_PILLOW, "Pillow is not installed")
    def test_no_avif_encoder_at_all_writes_nothing(self):
        real = features.check

        def no_avif(feature):
            return False if feature == "avif" else real(feature)

        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch) / "probe"
            with mock.patch("PIL.features.check", side_effect=no_avif), \
                    mock.patch("shutil.which", return_value=None), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                make_probe_images.main([str(out)])
            self.assertEqual(raised.exception.code, 4)
            self.assertFalse(out.exists())

    @unittest.skipUnless(HAVE_PILLOW, "Pillow is not installed")
    def test_no_heif_or_jxl_encoder_writes_nothing(self):
        real = shutil.which
        for missing in ("heif-enc", "cjxl"):
            def which(name, *args, **kwargs):
                return None if name == missing else real(name, *args, **kwargs)

            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as scratch:
                out = Path(scratch) / "probe"
                with mock.patch("shutil.which", side_effect=which), \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                    make_probe_images.main([str(out)])
                self.assertEqual(raised.exception.code, 4)
                self.assertFalse(out.exists())


def probe_fixtures_missing(directory: Path) -> list[str]:
    """What gate.sh's `probe_fixtures_missing` prints for [directory], as written in gate.sh."""
    match = re.search(r"^probe_fixtures_missing\(\) \{.*?^\}$", GATE.read_text(), re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError("gate.sh defines no probe_fixtures_missing function")
    script = match.group(0) + '\nprobe_fixtures_missing "$1"\n'
    result = subprocess.run(["bash", "-c", script, "bash", str(directory)], capture_output=True, text=True,
                            timeout=30)
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    return result.stdout.split()


class GatePreflightTest(unittest.TestCase):

    def test_the_gate_looks_for_the_six_files_the_generator_writes(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertEqual(probe_fixtures_missing(Path(empty)), list(make_probe_images.NAMES))

    def test_a_directory_the_four_image_generator_wrote_lacks_the_heif_and_the_jxl(self):
        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch)
            for name in ("animated.gif", "static.webp", "animated.webp", "still.avif"):
                (out / name).write_bytes(b"owner's")
            self.assertEqual(probe_fixtures_missing(out), ["still.heic", "still.jxl"])
            for name in ("still.heic", "still.jxl"):
                (out / name).write_bytes(b"owner's")
            self.assertEqual(probe_fixtures_missing(out), [])

    def test_the_preflight_fails_naming_them_and_leaves_an_existing_directory_alone(self):
        text = GATE.read_text()
        preflight = text[text.index('step "0b.'):text.index('step "1.')]
        existing = preflight[preflight.index('if [ -d "$HOST_FIXTURES_DIR/reader/probe" ]'):preflight.index("else")]
        self.assertIn('probe_fixtures_missing "$HOST_FIXTURES_DIR/reader/probe"', existing)
        self.assertIn("fail ", existing)
        self.assertNotIn("make_probe_images.py \"$HOST_FIXTURES_DIR", existing)


if __name__ == "__main__":
    unittest.main()

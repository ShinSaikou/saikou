#!/usr/bin/env python3
"""Unit tests for tools/compat/injekt_types.py: the registry reader, the dexdump signature reader
and the run's verdict.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

dexdump is replaced by a small script that prints canned output per APK, so nothing here needs
the SDK, a fixture or a device. Every package name is synthetic.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import stat
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
INJEKT_TYPES_PATH = TOOLS / "compat" / "injekt_types.py"

_spec = importlib.util.spec_from_file_location("injekt_types", INJEKT_TYPES_PATH)
injekt_types = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(injekt_types)


def signature_line(*pieces: str) -> str:
    """One annotation line exactly as `dexdump -a` prints it."""
    inner = " ".join(f'"{piece}"' for piece in pieces)
    return (
        "  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "
        f'"Luy/kohesive/injekt/api/FullTypeReference<" {inner} ">;" }}'
    )


JSON_LINE = signature_line("Lkotlinx/serialization/json/Json;")
PROTOBUF_LINE = signature_line("Lkotlinx/serialization/protobuf/ProtoBuf;")
OWN_TYPE_LINE = signature_line("Lcom/example/sample/SampleHelper;")
NOT_INJEKT_LINE = (
    '  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "Ljava/util/List<" "Ljava/lang/String;" ">;" }'
)

REGISTRY_SOURCE = textwrap.dedent(
    """\
    package com.example.host

    import kotlinx.serialization.json.Json
    import uy.kohesive.injekt.Injekt

    class Registry(private val json: Json) {
        fun install() {
            Injekt.addSingleton<Json>(json)
            Injekt.addSingletonFactory<com.example.other.Thing> { error("unused") }
            Injekt.addSingleton<LocalThing>(LocalThing())
        }
    }
    """
)


class RegisteredTypesTest(unittest.TestCase):
    def test_resolves_imports_qualified_names_and_the_file_package(self) -> None:
        self.assertEqual(
            injekt_types.registered_types(REGISTRY_SOURCE),
            {"kotlinx.serialization.json.Json", "com.example.other.Thing", "com.example.host.LocalThing"},
        )

    def test_the_real_registry_holds_every_type_upstream_gives_extensions(self) -> None:
        self.assertEqual(
            injekt_types.registered_types(injekt_types.BOOTSTRAP.read_text()),
            {
                "android.content.Context",
                "android.app.Application",
                "eu.kanade.tachiyomi.network.NetworkHelper",
                "kotlinx.serialization.json.Json",
                "kotlinx.serialization.protobuf.ProtoBuf",
                "nl.adaptivity.xmlutil.serialization.XML",
                "eu.kanade.tachiyomi.network.JavaScriptEngine",
                "tachiyomi.core.common.preference.PreferenceStore",
            },
        )


class RequestedTypesTest(unittest.TestCase):
    def test_reads_only_full_type_reference_signatures(self) -> None:
        output = "\n".join(["Class #1", JSON_LINE, NOT_INJEKT_LINE, PROTOBUF_LINE, JSON_LINE])
        self.assertEqual(
            injekt_types.requested_types(output),
            {"kotlinx.serialization.json.Json", "kotlinx.serialization.protobuf.ProtoBuf"},
        )

    def test_generic_type_arguments_are_kept(self) -> None:
        line = signature_line("Ljava/util/Map<", "Ljava/lang/String;", "Ljava/lang/Integer;", ">;")
        self.assertEqual(injekt_types.requested_types(line), {"java.util.Map<java.lang.String, java.lang.Integer>"})


class MainTest(unittest.TestCase):
    """The whole run, against a fake dexdump that prints `<apk stem>.dump` from beside the APK."""

    def run_main(self, dumps: dict[str, str], registry: str = REGISTRY_SOURCE) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apks = root / "compat"
            apks.mkdir()
            for stem, dump in dumps.items():
                (apks / f"{stem}.apk").write_bytes(b"")
                (apks / f"{stem}.dump").write_text(dump)
            dexdump = root / "dexdump"
            dexdump.write_text(f'#!{sys.executable}\nimport sys\nsys.stdout.write(open(sys.argv[2][:-4] + ".dump").read())\n')
            dexdump.chmod(dexdump.stat().st_mode | stat.S_IXUSR)
            bootstrap = root / "Registry.kt"
            bootstrap.write_text(registry)
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = injekt_types.main(["--apks", str(apks), "--bootstrap", str(bootstrap), "--dexdump", str(dexdump)])
            return code, stdout.getvalue()

    def test_every_request_registered_exits_zero(self) -> None:
        code, out = self.run_main({"0000": JSON_LINE, "0001": "", "0002": JSON_LINE})
        self.assertEqual(code, 0)
        self.assertIn("3 APKs; 2 ask Injekt for 1 types; InjektBootstrap registers 3", out)
        self.assertIn("     2  kotlinx.serialization.json.Json  registered", out)

    def test_an_unregistered_request_exits_one_and_names_the_fixture_files(self) -> None:
        code, out = self.run_main({"0000": JSON_LINE, "0001": PROTOBUF_LINE, "0002": PROTOBUF_LINE})
        self.assertEqual(code, 1)
        self.assertIn("kotlinx.serialization.protobuf.ProtoBuf is not registered; asked for by 0001.apk, 0002.apk", out)

    def test_a_type_outside_the_host_packages_is_never_printed_by_name(self) -> None:
        code, out = self.run_main({"0000": OWN_TYPE_LINE})
        self.assertEqual(code, 1)
        self.assertNotIn("com.example.sample", out)
        self.assertIn(f"{injekt_types.EXTENSION_TYPE} is not registered; asked for by 0000.apk", out)

    def test_an_empty_directory_is_invalid_input(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            self.run_main({})
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()

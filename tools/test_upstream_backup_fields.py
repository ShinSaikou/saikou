#!/usr/bin/env python3
"""Unit tests for tools/gate/upstream_backup_fields.py.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The reference tree is not on every machine, so these build a small tree of their own in a temporary
directory: two model files and the enum, written for this test in the shapes the reference sources
use (an annotation on a line of its own above a property, a deprecation whose message holds `//`,
commented-out retired fields, a field numbered 5 declared before field 4, fields numbered by position,
a sealed class and its subclasses in one file, a nullable field with no default, a comment between
enum entries). The committed JSON is read too, for the shape :backup's UpstreamBackupFieldsTest reads.

The cases that matter, and why each one is here:
  - every shape above parses to the number, name, type, nullability and default as written;
  - generation is stable: two runs over one tree write the same bytes, and --check passes straight
    after writing;
  - --check fails with exit 1, naming the field, on each kind of disagreement: a number changed in
    the tree, a field added to it, a field removed from it, two fields swapped, a default changed and
    an enum entry reordered; and on a file edited by hand while the tree stayed put;
  - no tree at all, or a tree without the models directory, is a missing input (exit 2), which is
    what gate.sh skips the check on; a blank SAIKOU_UPSTREAM_DIR is absent, not the current directory;
    saikou.upstream.dir in local.properties names the tree when nothing else does;
  - a constructor parameter that is not a property is refused (exit 2) rather than left out.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("upstream_backup_fields", TOOLS / "gate" / "upstream_backup_fields.py")
fields = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = fields
_spec.loader.exec_module(fields)

RECORD = textwrap.dedent("""\
    package sample.models

    import kotlinx.serialization.Serializable
    import kotlinx.serialization.protobuf.ProtoNumber

    @Suppress("DEPRECATION")
    @Serializable
    class SampleRecord(
        // the key, called something else elsewhere
        @ProtoNumber(1) var key: String,
        @ProtoNumber(2) var label: String = "",
        // @ProtoNumber(3) val retired: Int = 0, not used here
        @ProtoNumber(5) var weight: Float = 0F,
        @ProtoNumber(4) var parts: List<SamplePart> = emptyList(),
        @Deprecated("Use weight // not this", level = DeprecationLevel.WARNING)
        @ProtoNumber(6)
        var legacy: Int = 0,
        @ProtoNumber(7) var note: String?,
        @ProtoNumber(100) var mode: SampleMode = SampleMode.FIRST, /* trailing */
    ) {
        fun describe(): String = "$key ($label)"
    }

    @Serializable
    data class SamplePart(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val sizes: Map<String,   Long> = emptyMap(),
    )
    """)

VALUES = textwrap.dedent("""\
    package sample.models

    import kotlinx.serialization.Serializable

    @Serializable
    sealed class SampleValue

    @Serializable
    data class NumberValue(val value: Long) : SampleValue()

    @Serializable
    data class TextValue(val value: String) : SampleValue()
    """)

ENUM = textwrap.dedent("""\
    package sample.model

    @Suppress("UNUSED")
    enum class UpdateStrategy {
        /** Always. */
        ALWAYS_UPDATE,

        // Once.
        ONLY_FETCH_ONCE,
    }
    """)


class Tree:
    """A reference tree of this test's own, and the JSON file the script writes or checks."""

    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name) / "reference"
        self.models = self.root / fields.MODELS_DIR
        self.models.mkdir(parents=True)
        (self.models / "SampleRecord.kt").write_text(RECORD)
        (self.models / "SampleValue.kt").write_text(VALUES)
        enum = self.root / fields.ENUM_FILE
        enum.parent.mkdir(parents=True)
        enum.write_text(ENUM)
        self.out = Path(directory.name) / "fields.json"

    def edit(self, name: str, old: str, new: str) -> None:
        path = self.models / name if name.endswith(".kt") and "/" not in name else self.root / name
        text = path.read_text()
        if old not in text:
            raise AssertionError(f"{old!r} is not in {name}")
        path.write_text(text.replace(old, new, 1))

    def run(self, *extra: str) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = fields.main(["--upstream", str(self.root), "--out", str(self.out), *extra])
        return code, stdout.getvalue(), stderr.getvalue()


def field(document: dict, message: str, name: str) -> dict:
    return next(f for f in document["messages"][message]["fields"] if f["name"] == name)


class ParseTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tree = Tree(self)
        self.document = fields.generate(self.tree.root)

    def test_every_class_in_every_file_is_a_message(self):
        self.assertEqual(self.document["files"], ["SampleRecord.kt", "SampleValue.kt"])
        self.assertEqual(list(self.document["messages"]),
                         ["SampleRecord", "SamplePart", "SampleValue", "NumberValue", "TextValue"])
        self.assertEqual(self.document["messages"]["SampleRecord"]["serialName"], "sample.models.SampleRecord")

    def test_fields_keep_declaration_order_and_their_own_numbers(self):
        record = self.document["messages"]["SampleRecord"]["fields"]
        self.assertEqual([(f["name"], f["number"]) for f in record],
                         [("key", 1), ("label", 2), ("weight", 5), ("parts", 4), ("legacy", 6), ("note", 7),
                          ("mode", 100)])

    def test_a_commented_out_field_is_not_a_field(self):
        names = [f["name"] for f in self.document["messages"]["SampleRecord"]["fields"]]
        self.assertNotIn("retired", names)

    def test_type_nullability_and_default_are_as_written(self):
        self.assertEqual(field(self.document, "SampleRecord", "key"),
                         {"number": 1, "numberedBy": "annotation", "name": "key", "type": "String",
                          "nullable": False, "default": None})
        self.assertEqual(field(self.document, "SampleRecord", "label")["default"], '""')
        self.assertEqual(field(self.document, "SampleRecord", "weight")["default"], "0F")
        self.assertEqual(field(self.document, "SampleRecord", "parts")["type"], "List<SamplePart>")
        self.assertEqual(field(self.document, "SampleRecord", "mode")["default"], "SampleMode.FIRST")
        note = field(self.document, "SampleRecord", "note")
        self.assertEqual((note["type"], note["nullable"], note["default"]), ("String", True, None))
        self.assertEqual(field(self.document, "SamplePart", "sizes")["type"], "Map<String, Long>")

    def test_an_annotation_on_its_own_line_with_a_slash_pair_in_its_message_still_numbers_the_field(self):
        legacy = field(self.document, "SampleRecord", "legacy")
        self.assertEqual((legacy["number"], legacy["numberedBy"], legacy["default"]), (6, "annotation", "0"))

    def test_sealed_classes_and_their_subclasses_are_recorded_and_unannotated_fields_number_by_position(self):
        messages = self.document["messages"]
        self.assertTrue(messages["SampleValue"]["sealed"])
        self.assertEqual(messages["SampleValue"]["fields"], [])
        self.assertEqual(messages["NumberValue"]["extends"], "SampleValue")
        self.assertEqual(field(self.document, "NumberValue", "value"),
                         {"number": 1, "numberedBy": "position", "name": "value", "type": "Long",
                          "nullable": False, "default": None})

    def test_enum_entries_are_read_in_order_past_comments(self):
        self.assertEqual(self.document["enums"]["UpdateStrategy"]["entries"], ["ALWAYS_UPDATE", "ONLY_FETCH_ONCE"])

    def test_a_tree_that_is_not_a_git_checkout_records_no_commit(self):
        self.assertIsNone(self.document["reference"]["commit"])


class GenerateAndCheckTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tree = Tree(self)
        code, _, err = self.tree.run()
        self.assertEqual(code, fields.AGREES, err)

    def test_two_runs_write_the_same_bytes(self):
        first = self.tree.out.read_bytes()
        self.assertEqual(self.tree.run()[0], fields.AGREES)
        self.assertEqual(self.tree.out.read_bytes(), first)

    def test_check_passes_on_the_file_it_just_wrote(self):
        code, out, _ = self.tree.run("--check")
        self.assertEqual(code, fields.AGREES)
        self.assertIn("agrees with the tree", out)
        self.assertIn("5 messages, 11 fields, 2 files", out)

    def assert_disagrees(self, *expected: str) -> None:
        before = self.tree.out.read_bytes()
        code, out, err = self.tree.run("--check")
        self.assertEqual(code, fields.DISAGREES, out + err)
        for line in expected:
            self.assertIn(line, out)
        self.assertIn("disagrees with the tree", err)
        self.assertEqual(self.tree.out.read_bytes(), before, "--check must never rewrite the file")

    def test_a_number_changed_in_the_tree_fails_naming_the_field(self):
        self.tree.edit("SampleRecord.kt", "@ProtoNumber(100) var mode", "@ProtoNumber(101) var mode")
        self.assert_disagrees("SampleRecord.mode: number is 100 in the file, 101 in the tree")

    def test_a_field_added_to_the_tree_fails(self):
        self.tree.edit("SampleRecord.kt", "@ProtoNumber(100)", "@ProtoNumber(8) var extra: Long = 0,\n    @ProtoNumber(100)")
        self.assert_disagrees("SampleRecord.extra: in the tree, not in the file")

    def test_a_field_removed_from_the_tree_fails(self):
        self.tree.edit("SampleRecord.kt", "@ProtoNumber(7) var note: String?,", "")
        self.assert_disagrees("SampleRecord.note: in the file, not in the tree")

    def test_two_fields_swapped_in_the_tree_fail_on_order(self):
        self.tree.edit("SampleRecord.kt", "@ProtoNumber(5) var weight: Float = 0F,\n    @ProtoNumber(4) var parts: List<SamplePart> = emptyList(),",
                       "@ProtoNumber(4) var parts: List<SamplePart> = emptyList(),\n    @ProtoNumber(5) var weight: Float = 0F,")
        self.assert_disagrees("message SampleRecord: declaration order is")

    def test_a_default_changed_in_the_tree_fails(self):
        self.tree.edit("SampleRecord.kt", 'var label: String = ""', 'var label: String = "none"')
        self.assert_disagrees("SampleRecord.label: default is '\"\"' in the file, '\"none\"' in the tree")

    def test_a_nullability_changed_in_the_tree_fails(self):
        self.tree.edit("SampleRecord.kt", "var key: String,", "var key: String?,")
        self.assert_disagrees("SampleRecord.key: nullable is False in the file, True in the tree")

    def test_an_enum_reordered_in_the_tree_fails(self):
        (self.tree.root / fields.ENUM_FILE).write_text(ENUM.replace("ALWAYS_UPDATE,", "PLACEHOLDER,")
                                                     .replace("ONLY_FETCH_ONCE,", "ALWAYS_UPDATE,")
                                                     .replace("PLACEHOLDER,", "ONLY_FETCH_ONCE,"))
        self.assert_disagrees("enum UpdateStrategy:")
        self.assertEqual(fields.generate(self.tree.root)["enums"]["UpdateStrategy"]["entries"],
                         ["ONLY_FETCH_ONCE", "ALWAYS_UPDATE"])

    def test_a_class_added_to_the_tree_fails(self):
        (self.tree.models / "SampleExtra.kt").write_text(
            "package sample.models\n\n@Serializable\nclass SampleExtra(@ProtoNumber(1) var id: Long)\n")
        self.assert_disagrees("message SampleExtra: in the tree, not in the file",
                              "files: the file has ['SampleRecord.kt', 'SampleValue.kt']")

    def test_a_file_edited_by_hand_fails(self):
        document = json.loads(self.tree.out.read_text())
        field(document, "SampleRecord", "weight")["number"] = 4
        self.tree.out.write_text(json.dumps(document, indent=2) + "\n")
        self.assert_disagrees("SampleRecord.weight: number is 4 in the file, 5 in the tree")

    def test_a_file_reformatted_by_hand_fails(self):
        self.tree.out.write_text(json.dumps(json.loads(self.tree.out.read_text())) + "\n")
        self.assert_disagrees("not what the script writes")


class InputTest(unittest.TestCase):

    def run_main(self, *argv: str, env: dict[str, str] | None = None,
                 properties_text: str = "sdk.dir=/nowhere\n") -> tuple[int, str]:
        stderr = io.StringIO()
        properties = Path(tempfile.mkdtemp()) / "local.properties"
        self.addCleanup(properties.unlink, missing_ok=True)
        properties.write_text(properties_text)
        environment = {"SAIKOU_LOCAL_PROPERTIES": str(properties), **(env or {})}
        with mock.patch.dict(os.environ, environment):
            if "SAIKOU_UPSTREAM_DIR" not in environment:
                os.environ.pop("SAIKOU_UPSTREAM_DIR", None)
            with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
                code = fields.main(list(argv))
        return code, stderr.getvalue()

    def test_no_tree_named_anywhere_is_a_missing_input(self):
        code, err = self.run_main("--check")
        self.assertEqual(code, fields.BAD_INPUT)
        self.assertIn("missing: the reference tree", err)

    def test_a_blank_environment_variable_is_absent(self):
        code, err = self.run_main("--check", env={"SAIKOU_UPSTREAM_DIR": "  "})
        self.assertEqual(code, fields.BAD_INPUT)
        self.assertIn("missing: the reference tree (", err)

    def test_the_environment_names_the_tree_when_no_argument_does(self):
        tree = Tree(self)
        code, _ = self.run_main("--out", str(tree.out), env={"SAIKOU_UPSTREAM_DIR": str(tree.root)})
        self.assertEqual(code, fields.AGREES)
        self.assertIn("SampleRecord", json.loads(tree.out.read_text())["messages"])

    def test_local_properties_names_the_tree_when_nothing_else_does(self):
        tree = Tree(self)
        code, _ = self.run_main("--out", str(tree.out), properties_text=f"saikou.upstream.dir={tree.root}\n")
        self.assertEqual(code, fields.AGREES)
        self.assertIn("SampleRecord", json.loads(tree.out.read_text())["messages"])

    def test_a_tree_without_the_models_directory_is_a_missing_input(self):
        tree = Tree(self)
        for path in tree.models.iterdir():
            path.unlink()
        tree.models.rmdir()
        code, _, err = tree.run("--check")
        self.assertEqual(code, fields.BAD_INPUT)
        self.assertIn("missing:", err)

    def test_a_parameter_that_is_not_a_property_is_refused(self):
        tree = Tree(self)
        tree.edit("SampleRecord.kt", "@ProtoNumber(2) var label", "@ProtoNumber(2) label")
        code, _, err = tree.run()
        self.assertEqual(code, fields.BAD_INPUT)
        self.assertIn("is not a val or var property", err)
        self.assertFalse(tree.out.exists())


class CommittedFileTest(unittest.TestCase):
    """The committed file is what the script writes, in the shape the :backup test reads."""

    def setUp(self) -> None:
        self.text = fields.DEFAULT_OUT.read_text()
        self.document = json.loads(self.text)

    def test_it_is_byte_for_byte_what_render_writes(self):
        self.assertEqual(fields.render(self.document), self.text)

    def test_it_records_the_nine_model_files_and_the_enum(self):
        self.assertEqual(len(self.document["files"]), 9)
        self.assertEqual(self.document["reference"]["name"], fields.REFERENCE)
        self.assertEqual(self.document["enums"]["UpdateStrategy"]["entries"], ["ALWAYS_UPDATE", "ONLY_FETCH_ONCE"])
        self.assertIn("Backup", self.document["messages"])

    def test_every_field_carries_every_key(self):
        for name, message in self.document["messages"].items():
            for entry in message["fields"]:
                self.assertEqual(set(entry), {"number", "numberedBy", "name", "type", "nullable", "default"},
                                 f"{name}.{entry.get('name')}")


if __name__ == "__main__":
    unittest.main()

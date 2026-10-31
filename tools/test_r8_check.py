#!/usr/bin/env python3
"""Unit tests for tools/r8/check_test_references.py.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The two dumps below are hand written in the shape `dexdump -d` prints, which is the only input the
parser ever sees, so nothing here needs an APK, a build-tools install or a subprocess. Between them
they carry every case the resolver has to separate: a member the class declares, a member only its
supertype declares, a member nothing declares anywhere on a chain that is fully known, a member on
a class whose supertype is a platform class and therefore cannot be decided, a reference to a class
neither dump defines, and a reference whose owner is a platform class and is none of this script's
business.

The mapping tests write a small mapping.txt into a temporary directory for the same reason: the
member de-obfuscation is the part with the most ways to be quietly wrong, and it needs a file.
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
CHECKER_PATH = TOOLS / "r8" / "check_test_references.py"

_spec = importlib.util.spec_from_file_location("check_test_references", CHECKER_PATH)
checker = importlib.util.module_from_spec(_spec)
# Registered before it is executed because the module defines dataclasses, and dataclasses resolves
# a field's annotation by looking its own module up in sys.modules. Without this line the import
# fails with AttributeError on NoneType rather than with anything that names the cause.
sys.modules[_spec.name] = checker
_spec.loader.exec_module(checker)

Reference = checker.Reference


# --- the application dump ---------------------------------------------------------------------

APP_DUMP = """Processing 'classes.dex'...
Opened 'classes.dex', DEX version '038'
Class #0            -
  Class descriptor  : 'Lcom/example/Base;'
  Access flags      : 0x0401 (PUBLIC ABSTRACT)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/example/Base;)
      name          : 'fromTheSupertype'
      type          : '()Ljava/lang/String;'
      access        : 0x0001 (PUBLIC)
  source_file_idx   : 3 (Base.java)

Class #1            -
  Class descriptor  : 'Lcom/example/Child;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Lcom/example/Base;'
  Interfaces        -
    #0              : 'Lcom/example/Marker;'
  Static fields     -
    #0              : (in Lcom/example/Child;)
      name          : '$stable'
      type          : 'I'
      access        : 0x0019 (PUBLIC STATIC FINAL)
  Instance fields   -
  Direct methods    -
    #0              : (in Lcom/example/Child;)
      name          : '<init>'
      type          : '()V'
      access        : 0x10001 (PUBLIC CONSTRUCTOR)
    #1              : (in Lcom/example/Child;)
      name          : 'staticHelper'
      type          : '()V'
      access        : 0x0009 (PUBLIC STATIC)
  Virtual methods   -
    #0              : (in Lcom/example/Child;)
      name          : 'ownMethod'
      type          : '(I)V'
      access        : 0x0001 (PUBLIC)
  source_file_idx   : 4 (Child.java)

Class #2            -
  Class descriptor  : 'Lcom/example/Marker;'
  Access flags      : 0x0601 (PUBLIC INTERFACE ABSTRACT)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
    #0              : (in Lcom/example/Marker;)
      name          : 'MARKER'
      type          : 'Ljava/lang/String;'
      access        : 0x0019 (PUBLIC STATIC FINAL)
  Instance fields   -
  Direct methods    -
  Virtual methods   -
  source_file_idx   : 5 (Marker.java)

Class #3            -
  Class descriptor  : 'Lcom/example/OnThePlatform;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Landroid/app/Application;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
  source_file_idx   : 6 (OnThePlatform.java)
"""


# --- the androidTest dump ---------------------------------------------------------------------

TEST_DUMP = """Processing 'classes.dex'...
Opened 'classes.dex', DEX version '038'
Class #0            -
  Class descriptor  : 'Lcom/example/ExampleTest;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/example/ExampleTest;)
      name          : 'test'
      type          : '()V'
      access        : 0x0001 (PUBLIC)
      code          -
      registers     : 2
      ins           : 1
      outs          : 2
      insns size    : 22 16-bit code units
000100:                                        |[000100] com.example.ExampleTest.test:()V
000110: 2200 0100                              |0000: new-instance v0, Lcom/example/Child; // type@0001
000114: 7010 0200 0000                         |0002: invoke-direct {v0}, Lcom/example/Child;.<init>:()V // method@0002
00011a: 6e10 0300 0000                         |0005: invoke-virtual {v0}, Lcom/example/Child;.fromTheSupertype:()Ljava/lang/String; // method@0003
000120: 6e10 0400 0000                         |0008: invoke-virtual {v0}, Lcom/example/Child;.goneEverywhere:()V // method@0004
000126: 6000 0500                              |000b: sget v0, Lcom/example/Child;.$stable:I // field@0005
00012a: 6200 0600                              |000d: sget-object v0, Lcom/example/Marker;.MARKER:Ljava/lang/String; // field@0006
000130: 7110 0700 0000                         |000f: invoke-static {v0}, Lcom/example/Vanished;.help:(Ljava/lang/String;)V // method@0007
000136: 6e10 0800 0000                         |0012: invoke-virtual {v1}, Lcom/example/OnThePlatform;.onCreate:()V // method@0008
00013c: 6e10 0900 0000                         |0015: invoke-virtual {v1}, Landroid/app/Application;.getPackageName:()Ljava/lang/String; // method@0009
000142: 6e10 0a00 0000                         |0018: invoke-virtual {v0}, Lcom/example/Child;.toString:()Ljava/lang/String; // method@000a
000148: 0e00                                   |001b: return-void
      catches       : (none)
  source_file_idx   : 7 (ExampleTest.java)

Class #1            -
  Class descriptor  : 'Lcom/example/ExampleTest$1;'
  Access flags      : 0x0010 (FINAL)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/example/ExampleTest$1;)
      name          : 'run'
      type          : '()V'
      access        : 0x0011 (PUBLIC FINAL)
      code          -
      registers     : 1
      ins           : 1
      outs          : 1
      insns size    : 4 16-bit code units
000200:                                        |[000200] com.example.ExampleTest$1.run:()V
000210: 6e10 0b00 0000                         |0000: invoke-virtual {v0}, Lcom/example/Child;.goneFromTheLambda:()V // method@000b
000216: 0e00                                   |0003: return-void
      catches       : (none)
  source_file_idx   : 7 (ExampleTest.java)

Class #2            -
  Class descriptor  : 'Lcom/example/OtherTest;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/example/OtherTest;)
      name          : 'test'
      type          : '()V'
      access        : 0x0001 (PUBLIC)
      code          -
      registers     : 1
      ins           : 1
      outs          : 1
      insns size    : 4 16-bit code units
000300:                                        |[000300] com.example.OtherTest.test:()V
000310: 6e10 0c00 0000                         |0000: invoke-virtual {v0}, Lcom/example/Child;.goneFromAnotherSuite:()V // method@000c
000316: 0e00                                   |0003: return-void
      catches       : (none)
  source_file_idx   : 8 (OtherTest.java)
"""

EXAMPLE_TEST = "Lcom/example/ExampleTest;"
CHILD = "Lcom/example/Child;"


def parsed() -> tuple[dict, dict, set]:
    app_classes, _ = checker.parse_dexdump(APP_DUMP, collect_references=False)
    test_classes, test_references = checker.parse_dexdump(TEST_DUMP, collect_references=True)
    return app_classes, test_classes, test_references


def method(owner: str, name: str, signature: str) -> Reference:
    return Reference("method", owner, name, signature, "")



def verdict_of(result):
    """resolve() returns (verdict, declaring class); these assertions are about the verdict."""
    verdict, _ = result
    return verdict

class ParseDexdumpTest(unittest.TestCase):
    def test_reads_the_class_header(self):
        app_classes, _, _ = parsed()
        child = app_classes[CHILD]
        self.assertEqual(child.superclass, "Lcom/example/Base;")
        self.assertEqual(child.interfaces, ["Lcom/example/Marker;"])

    def test_reads_fields_and_methods_into_their_own_sets(self):
        app_classes, _, _ = parsed()
        child = app_classes[CHILD]
        self.assertIn(("$stable", "I"), child.fields)
        self.assertIn(("<init>", "()V"), child.methods)
        self.assertIn(("ownMethod", "(I)V"), child.methods)
        # The static field's name and type must not have been filed as a method, which is what a
        # parser that forgot which section it was in would do.
        self.assertNotIn(("$stable", "I"), child.methods)

    def test_collects_no_references_when_it_is_not_asked_for_them(self):
        _, references = checker.parse_dexdump(TEST_DUMP, collect_references=False)
        self.assertEqual(references, set())

    def test_attributes_each_reference_to_the_class_whose_code_holds_it(self):
        _, _, references = parsed()
        sources = {r.source for r in references if r.name == "goneFromTheLambda"}
        self.assertEqual(sources, {"Lcom/example/ExampleTest$1;"})

    def test_reads_method_field_and_type_references_apart(self):
        _, _, references = parsed()
        self.assertIn(
            Reference("method", CHILD, "fromTheSupertype", "()Ljava/lang/String;", EXAMPLE_TEST,
                      "invoke-virtual"),
            references,
        )
        self.assertIn(Reference("field", CHILD, "$stable", "I", EXAMPLE_TEST, "sget"), references)
        # A type reference is made by opcodes that name no member, so it carries no opcode.
        self.assertIn(Reference("type", CHILD, "", "", EXAMPLE_TEST), references)

    def test_reads_the_opcode_that_makes_each_member_reference(self):
        # The opcode is what says HOW a member is reached, and a member R8 made static resolves
        # perfectly well while still being reached the wrong way. Without this the fifth rewrite
        # class is invisible to the whole script.
        _, _, references = parsed()
        opcodes = {(r.name, r.opcode) for r in references if r.kind in ("method", "field")}
        self.assertIn(("fromTheSupertype", "invoke-virtual"), opcodes)
        self.assertIn(("help", "invoke-static"), opcodes)
        self.assertIn(("<init>", "invoke-direct"), opcodes)
        self.assertIn(("$stable", "sget"), opcodes)

    def test_reads_which_members_are_static(self):
        app_classes, _, _ = parsed()
        child = app_classes[CHILD]
        self.assertIn(("staticHelper", "()V"), child.methods)
        self.assertIn(("staticHelper", "()V"), child.static_methods)
        self.assertIn(("ownMethod", "(I)V"), child.methods)
        self.assertNotIn(("ownMethod", "(I)V"), child.static_methods)


class ResolveTest(unittest.TestCase):
    def definitions(self) -> dict:
        app_classes, test_classes, _ = parsed()
        merged = dict(app_classes)
        merged.update(test_classes)
        return merged

    def test_finds_a_member_the_class_declares(self):
        self.assertEqual(
            verdict_of(checker.resolve(self.definitions(), method(CHILD, "ownMethod", "(I)V"))),
            checker.FOUND,
        )

    def test_finds_a_member_only_the_supertype_declares(self):
        self.assertEqual(
            verdict_of(checker.resolve(
                self.definitions(), method(CHILD, "fromTheSupertype", "()Ljava/lang/String;"))),
            checker.FOUND,
        )

    def test_finds_a_constant_only_an_interface_declares(self):
        self.assertEqual(
            verdict_of(checker.resolve(
                self.definitions(), Reference("field", CHILD, "MARKER", "Ljava/lang/String;", ""))),
            checker.FOUND,
        )

    def test_finds_an_object_method_so_ordinary_chains_terminate(self):
        self.assertEqual(
            verdict_of(checker.resolve(
                self.definitions(), method(CHILD, "toString", "()Ljava/lang/String;"))),
            checker.FOUND,
        )

    def test_reports_a_member_defined_nowhere_as_absent(self):
        self.assertEqual(
            verdict_of(checker.resolve(self.definitions(), method(CHILD, "goneEverywhere", "()V"))),
            checker.ABSENT,
        )

    def test_reports_a_chain_through_a_platform_class_as_unverifiable(self):
        # OnThePlatform extends android.app.Application, whose members cannot be enumerated from
        # two APKs, so the honest answer is neither present nor absent. This is the blind spot the
        # checker's docstring describes, and a resolver that called this ABSENT would be guessing.
        self.assertEqual(
            verdict_of(checker.resolve(
                self.definitions(), method("Lcom/example/OnThePlatform;", "onCreate", "()V"))),
            checker.UNVERIFIABLE,
        )


class AnalyseTest(unittest.TestCase):
    def analyse(self, seeds):
        app_classes, test_classes, references = parsed()
        return checker.analyse(app_classes, test_classes, references, seeds)

    def test_lists_the_member_that_resolves_nowhere(self):
        findings = self.analyse([EXAMPLE_TEST])
        names = {r.name for r in findings.absent_members}
        self.assertIn("goneEverywhere", names)

    def test_lists_a_class_neither_apk_defines(self):
        findings = self.analyse([EXAMPLE_TEST])
        self.assertEqual(findings.absent_classes, ["Lcom/example/Vanished;"])
        # The call made on that class is not also counted as an absent member: one missing class is
        # one finding and one whole-class keep, not a finding per member reached through it.
        self.assertNotIn("help", {r.name for r in findings.absent_members})

    def test_lists_the_undecidable_member_separately_and_does_not_fail_on_it(self):
        findings = self.analyse([EXAMPLE_TEST])
        self.assertEqual([r.name for r in findings.unverifiable], ["onCreate"])
        self.assertNotIn("onCreate", {r.name for r in findings.absent_members})

    def test_ignores_a_reference_whose_owner_is_a_platform_class(self):
        findings = self.analyse([EXAMPLE_TEST])
        owners = {r.owner for r in findings.absent_members + findings.unverifiable}
        self.assertNotIn("Landroid/app/Application;", owners)

    def test_covers_the_nested_classes_the_compiler_emitted_beside_the_seed(self):
        findings = self.analyse([EXAMPLE_TEST])
        self.assertIn("goneFromTheLambda", {r.name for r in findings.absent_members})

    def test_leaves_out_a_suite_the_seed_does_not_name(self):
        findings = self.analyse([EXAMPLE_TEST])
        self.assertNotIn("goneFromAnotherSuite", {r.name for r in findings.absent_members})

    def test_covers_every_class_when_no_seed_is_given(self):
        findings = self.analyse(None)
        self.assertIn("goneFromAnotherSuite", {r.name for r in findings.absent_members})

    def test_is_not_clean_while_anything_is_absent(self):
        self.assertFalse(self.analyse([EXAMPLE_TEST]).clean())

    def test_is_clean_when_only_the_undecidable_is_left(self):
        app_classes, test_classes, references = parsed()
        undecidable = {r for r in references if r.owner == "Lcom/example/OnThePlatform;"}
        findings = checker.analyse(app_classes, test_classes, undecidable, [EXAMPLE_TEST])
        self.assertTrue(findings.clean())
        self.assertEqual(len(findings.unverifiable), 1)


class DescriptorTest(unittest.TestCase):
    def test_splits_a_method_signature(self):
        parameters, return_type = checker.split_signature("(ILjava/lang/String;[JZ)Ljava/util/List;")
        self.assertEqual(parameters, ["I", "Ljava/lang/String;", "[J", "Z"])
        self.assertEqual(return_type, "Ljava/util/List;")

    def test_splits_an_empty_parameter_list(self):
        self.assertEqual(checker.split_signature("()V"), ([], "V"))

    def test_writes_java_types_the_way_a_rule_spells_them(self):
        empty = checker.Mapping()
        self.assertEqual(checker.java_type("I", empty), "int")
        self.assertEqual(checker.java_type("[[B", empty), "byte[][]")
        self.assertEqual(checker.java_type("Lcom/example/Child$Inner;", empty),
                         "com.example.Child$Inner")


class MappingTest(unittest.TestCase):
    MAPPING = """# compiler: R8
com.example.Child -> a:
# {"id":"sourceFile","fileName":"Child.java"}
    int stableField -> b
    1:5:java.lang.Object suspendCall(java.lang.String,kotlin.coroutines.Continuation):22:22 -> c
      # {"id":"com.android.tools.r8.residualsignature","signature":"(Ljava/lang/String;Lx;)Ljava/lang/Object;"}
    2:3:java.lang.String com.example.Other.inlinedHere():9:9 -> c
com.example.Base -> d:
    java.lang.String fromTheSupertype() -> e
com.example.Merged -> a:
"""

    def mapping(self) -> object:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mapping.txt"
            path.write_text(self.MAPPING)
            return checker.read_mapping(path)

    def definitions(self) -> dict:
        # The obfuscated shape of the app dump: Child is `a` and its superclass Base is `d`.
        return {
            "La;": checker.ClassDef("La;", superclass="Ld;"),
            "Ld;": checker.ClassDef("Ld;", superclass="Ljava/lang/Object;"),
        }

    def test_returns_an_empty_mapping_when_the_file_is_not_there(self):
        empty = checker.read_mapping(Path("/nonexistent/mapping.txt"))
        self.assertEqual(empty.original_class("a"), "a")

    def test_turns_an_obfuscated_class_name_back(self):
        self.assertEqual(self.mapping().original_class("d"), "com.example.Base")

    def test_reports_a_name_two_classes_were_merged_behind(self):
        self.assertEqual(self.mapping().alternatives("a"), ["com.example.Child", "com.example.Merged"])
        self.assertEqual(self.mapping().alternatives("d"), [])

    def test_writes_a_renamed_method_with_its_original_name_and_signature(self):
        reference = method("La;", "c", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
        rule = checker.rule_for(reference, self.mapping(), self.definitions())
        self.assertEqual(rule.owner, "com.example.Child")
        self.assertEqual(
            rule.text,
            "java.lang.Object suspendCall(java.lang.String, kotlin.coroutines.Continuation);",
        )

    def test_names_the_class_that_declares_the_member_rather_than_the_one_called(self):
        # The androidTest APK calls through the static type it holds, while mapping.txt files the
        # member under the type that declares it. A rule naming the caller's type keeps nothing.
        reference = method("La;", "e", "()Ljava/lang/String;")
        rule = checker.rule_for(reference, self.mapping(), self.definitions())
        self.assertEqual(rule.owner, "com.example.Base")
        self.assertEqual(rule.text, "java.lang.String fromTheSupertype();")

    def test_ignores_an_inlined_frame_filed_under_the_same_obfuscated_name(self):
        # `com.example.Other.inlinedHere` shares the obfuscated name `c` because it was inlined into
        # it. It is not a member of this class and must never be the name a rule keeps.
        candidates = self.mapping().members["a"]["c"]
        self.assertEqual([m.name for m in candidates], ["suspendCall"])

    def test_falls_back_to_the_androidtest_spelling_for_a_member_r8_removed(self):
        # A member R8 deleted has no mapping line at all, and nothing renamed it either, so the name
        # the androidTest APK carries is already the one a rule has to spell.
        reference = method("La;", "goneEverywhere", "()V")
        rule = checker.rule_for(reference, self.mapping(), self.definitions())
        self.assertEqual(rule.owner, "com.example.Child")
        self.assertEqual(rule.text, "void goneEverywhere();")

    def test_writes_a_field_without_parentheses(self):
        reference = Reference("field", "La;", "b", "I", "")
        rule = checker.rule_for(reference, self.mapping(), self.definitions())
        self.assertEqual(rule.text, "int stableField;")

    def test_writes_a_constructor_as_init(self):
        reference = method("La;", "<init>", "(Ljava/lang/String;)V")
        rule = checker.rule_for(reference, self.mapping(), self.definitions())
        self.assertEqual(rule.text, "<init>(java.lang.String);")


class PlatformTest(unittest.TestCase):
    def test_treats_the_boot_classpath_as_the_platform(self):
        self.assertTrue(checker.is_platform("Ljava/lang/String;"))
        self.assertTrue(checker.is_platform("Landroid/app/Application;"))

    def test_does_not_mistake_androidx_for_the_platform(self):
        # androidx ships inside the APK and is shrunk with everything else, so a member missing from
        # it is this script's business. The prefix test has to keep the slash to see the difference.
        self.assertFalse(checker.is_platform("Landroidx/test/runner/AndroidJUnitRunner;"))
        self.assertFalse(checker.is_platform("Lcom/example/Child;"))


class InvokeKindTest(unittest.TestCase):
    """The fifth R8 rewrite: a method made static keeps its name and descriptor.

    Resolution cannot see this one, because the member really is there. Only the opcode at the call
    site disagrees with the declaration, and the device reports the disagreement as
    IncompatibleClassChangeError rather than as anything resolution would have caught.
    """

    def child(self):
        app_classes, _, _ = parsed()
        return app_classes[CHILD]

    def test_a_virtual_call_to_a_staticized_method_is_a_mismatch(self):
        reference = Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST, "invoke-virtual")
        self.assertFalse(checker.check_invoke_kind(reference, self.child()))

    def test_a_static_call_to_a_staticized_method_is_fine(self):
        reference = Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST, "invoke-static")
        self.assertTrue(checker.check_invoke_kind(reference, self.child()))

    def test_a_static_call_to_an_instance_method_is_a_mismatch(self):
        reference = Reference("method", CHILD, "ownMethod", "(I)V", EXAMPLE_TEST, "invoke-static")
        self.assertFalse(checker.check_invoke_kind(reference, self.child()))

    def test_a_virtual_call_to_an_instance_method_is_fine(self):
        reference = Reference("method", CHILD, "ownMethod", "(I)V", EXAMPLE_TEST, "invoke-virtual")
        self.assertTrue(checker.check_invoke_kind(reference, self.child()))

    def test_range_and_typed_suffixes_do_not_change_the_answer(self):
        reference = Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST,
                              "invoke-virtual/range")
        self.assertFalse(checker.check_invoke_kind(reference, self.child()))

    def test_a_static_field_read_with_sget_is_fine(self):
        reference = Reference("field", CHILD, "$stable", "I", EXAMPLE_TEST, "sget")
        self.assertTrue(checker.check_invoke_kind(reference, self.child()))

    def test_a_static_field_read_with_iget_is_a_mismatch(self):
        reference = Reference("field", CHILD, "$stable", "I", EXAMPLE_TEST, "iget")
        self.assertFalse(checker.check_invoke_kind(reference, self.child()))

    def test_it_declines_rather_than_guessing_when_it_cannot_tell(self):
        # No declaring class, an opcode it does not know, and a member the declaring class does not
        # hold. Each has to come back True: a check that reports a mismatch it cannot substantiate
        # is worse than one that says nothing, because every rule it then generates keeps a member
        # for no reason.
        child = self.child()
        self.assertTrue(checker.check_invoke_kind(
            Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST, "invoke-virtual"), None))
        self.assertTrue(checker.check_invoke_kind(
            Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST, ""), child))
        self.assertTrue(checker.check_invoke_kind(
            Reference("method", CHILD, "notHere", "()V", EXAMPLE_TEST, "invoke-static"), child))

    def test_analyse_reports_a_mismatch_and_refuses_to_call_the_run_clean(self):
        app_classes, test_classes, _ = parsed()
        reference = Reference("method", CHILD, "staticHelper", "()V", EXAMPLE_TEST, "invoke-virtual")
        findings = checker.analyse(app_classes, test_classes, {reference}, [EXAMPLE_TEST])
        self.assertEqual([r.name for r in findings.mismatched], ["staticHelper"])
        self.assertEqual(findings.absent_members, [])
        self.assertFalse(findings.clean())


if __name__ == "__main__":
    unittest.main()

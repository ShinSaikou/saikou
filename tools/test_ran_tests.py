#!/usr/bin/env python3
"""Unit tests for tools/ci/ran_tests.py, which every instrumentation step reads its result with.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The XML and logcat lines are in the shapes AGP 9.4.0 writes under
`<module>/build/outputs/androidTest-results/connected/<variant>/`: one `TEST-<device>.xml` with a
`testsuites` root, and a `<device>/` directory beside it holding `logcat-<class>-<method>.txt` per
test in logcat's threadtime format. Nothing needs a device.

The cases that matter, and why each one is here:
  - a run of zero tests fails, which is the whole of R29's check and must survive the rewrite;
  - the count is per class, so a filter that drops or adds a class shows (R49);
  - a `Skipped:` line is counted once even when two tests' logcat windows both hold it;
  - no logcat at all is reported as such, never as zero skips;
  - the tablet step fails on any skip it does not expect, and on a run it cannot read skips from;
  - the two skips that step expects are spelled in gate.sh the way the tests log them, so a reworded
    log line fails here rather than on the tablet an hour into a run;
  - a required class that ran no test fails the run even when others did, and every class step 5e
    requires is a class that exists under the name gate.sh gives it;
  - gate.sh's expected class lists (DEVICE_CLASSES_*) name exactly the device test classes in the
    tree, module by module, so a class added without its line, or a line left for a deleted class,
    fails here in step 1 rather than on a device; and the download classes units G, H and J left
    unrequired are in the lists and in the deliverables;
  - a failed or errored test case is listed, and fails the run only under --fail-on-failures, so a
    caller that reads nothing but this script's exit can still tell a red run from a green one
    (unit K2's carry-over 4: a run with one failure exited 0 here).
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import re
import shlex
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
_spec = importlib.util.spec_from_file_location("ran_tests", TOOLS / "ci" / "ran_tests.py")
ran_tests = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = ran_tests
_spec.loader.exec_module(ran_tests)

DEVICE_DIR = "saikou_tablet_4g(AVD) - 16"


def xml(cases: dict[str, int]) -> str:
    suites = []
    for name, count in cases.items():
        body = "".join(f'<testcase name="m{i}" classname="{name}" time="0.1" />' for i in range(count))
        suites.append(f'<testsuite name="{name}" tests="{count}" failures="0" skipped="0">{body}</testsuite>')
    total = sum(cases.values())
    return f"<?xml version='1.0' encoding='UTF-8' ?><testsuites tests=\"{total}\">{''.join(suites)}</testsuites>"


def skip_line(tag: str, message: str, second: int = 1) -> str:
    return f"09-23 18:05:{second:02d}.123  4321  4350 I {tag}: Skipped: {message}"


class RanTestsCase(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def results(self, cases: dict[str, int], logcat: dict[str, list[str]] | None = None) -> None:
        (self.dir / f"TEST-{DEVICE_DIR}.xml").write_text(xml(cases))
        if logcat is not None:
            device = self.dir / DEVICE_DIR
            device.mkdir()
            for test, lines in logcat.items():
                started = f"09-23 18:05:00.001  4321  4350 I TestRunner: started: {test}"
                (device / f"logcat-{test}.txt").write_text("\n".join([started, *lines]) + "\n")

    def run_main(self, *options: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ran_tests.main([str(self.dir), *options])
        return code, out.getvalue(), err.getvalue()


class CountTest(RanTestsCase):

    def test_zero_tests_fails(self):
        self.results({})
        code, out, _ = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("0 test(s) in", out)

    def test_a_missing_directory_is_exit_2(self):
        code, _, err = self.run_main_on(self.dir / "absent")
        self.assertEqual(code, 2)
        self.assertIn("missing:", err)

    def run_main_on(self, directory: Path) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = ran_tests.main([str(directory)])
        return code, out.getvalue(), err.getvalue()

    def test_counts_are_per_class_and_summed(self):
        self.results({"com.shinsaikou.AppGraphTest": 8, "com.shinsaikou.ui.TwoPaneLayoutTest": 7}, logcat={})
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("15 test(s) in", out)
        self.assertRegex(out, r"\n\s+8  com\.shinsaikou\.AppGraphTest\n")
        self.assertRegex(out, r"\n\s+7  com\.shinsaikou\.ui\.TwoPaneLayoutTest\n")

    def test_a_suite_root_without_a_testsuites_wrapper_counts(self):
        (self.dir / "TEST-one.xml").write_text(
            '<testsuite name="A" tests="2"><testcase name="a" classname="A" />'
            '<testcase name="b" classname="A" /></testsuite>')
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("2 test(s) in", out)

    def test_an_unreadable_xml_is_ignored_rather_than_counted(self):
        (self.dir / "TEST-broken.xml").write_text("<testsuites")
        code, out, _ = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("0 test(s) in", out)


class SkipTest(RanTestsCase):

    def test_skip_lines_are_counted_with_their_tag(self):
        self.results({"T": 2}, logcat={
            "T-a": [skip_line("TwoPaneLayoutTest", "a: device is not composing tablet UI")],
            "T-b": ["09-23 18:05:02.000  4321  4350 I Other: nothing to see"],
        })
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("1 Skipped: line(s) in the per-test logcat", out)
        self.assertIn("TwoPaneLayoutTest: Skipped: a: device is not composing tablet UI", out)
        self.assertNotIn("09-23 18:05:01.123", out)

    def test_one_line_in_two_windows_counts_once(self):
        line = skip_line("TwoPaneLayoutTest", "a: reason")
        self.results({"T": 2}, logcat={"T-a": [line], "T-b": [line]})
        _, out, _ = self.run_main()
        self.assertIn("1 Skipped: line(s)", out)

    def test_two_skips_with_the_same_text_at_different_times_count_twice(self):
        self.results({"T": 2}, logcat={
            "T-a": [skip_line("X", "same", second=1)],
            "T-b": [skip_line("X", "same", second=2)],
        })
        _, out, _ = self.run_main()
        self.assertIn("2 Skipped: line(s)", out)

    def test_no_logcat_is_said_rather_than_read_as_no_skips(self):
        self.results({"T": 3})
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("no logcat captured", out)
        self.assertNotIn("Skipped: line(s)", out)

    def test_the_tablet_step_fails_on_an_unexpected_skip(self):
        self.results({"T": 2}, logcat={
            "T-a": [skip_line("TwoPaneLayoutTest", "a: device is not composing tablet UI")],
            "T-b": [skip_line("TwoPaneLayoutTest", "b: no extension installed at test time")],
        })
        code, out, err = self.run_main("--fail-on-skips", "--except", "b: no extension installed")
        self.assertEqual(code, 1)
        self.assertIn("b: no extension installed at test time  (expected here)", out)
        self.assertIn("1 Skipped: line(s) this step does not allow", err)

    def test_the_tablet_step_passes_when_every_skip_is_expected(self):
        self.results({"T": 2}, logcat={
            "T-a": [skip_line("TwoPaneLayoutTest", "a: phone half")],
            "T-b": [skip_line("TwoPaneLayoutTest", "b: no extension installed")],
        })
        code, _, _ = self.run_main("--fail-on-skips", "--except", "a: phone", "--except", "b: no ext")
        self.assertEqual(code, 0)

    def test_the_tablet_step_fails_when_it_cannot_see_skips(self):
        self.results({"T": 2})
        code, _, err = self.run_main("--fail-on-skips")
        self.assertEqual(code, 1)
        self.assertIn("no logcat", err)

    def test_zero_tests_still_fails_with_no_skips(self):
        self.results({}, logcat={})
        code, _, _ = self.run_main("--fail-on-skips")
        self.assertEqual(code, 1)


class RequireClassTest(RanTestsCase):

    def test_a_required_class_that_ran_passes(self):
        self.results({"com.shinsaikou.reader.ReaderModeSwitchTest": 2}, logcat={})
        code, _, _ = self.run_main("--require-class", "com.shinsaikou.reader.ReaderModeSwitchTest")
        self.assertEqual(code, 0)

    def test_a_filter_that_matched_another_class_fails(self):
        self.results({"com.shinsaikou.reader.ReaderSmokeTest": 4}, logcat={})
        code, out, err = self.run_main("--require-class", "com.shinsaikou.reader.ReaderModeSwitchTest")
        self.assertEqual(code, 1)
        self.assertIn("4 test(s) in", out)
        self.assertIn("com.shinsaikou.reader.ReaderModeSwitchTest ran no test here", err)

    def test_every_required_class_is_checked_and_named(self):
        self.results({"A": 1}, logcat={})
        code, _, err = self.run_main("--require-class", "A", "--require-class", "B", "--require-class", "C")
        self.assertEqual(code, 1)
        self.assertNotIn("A ran no test", err)
        self.assertIn("B ran no test", err)
        self.assertIn("C ran no test", err)

    def test_a_class_is_matched_whole_not_by_prefix(self):
        self.results({"com.shinsaikou.reader.ReaderModeSwitchTestHelper": 1}, logcat={})
        code, _, _ = self.run_main("--require-class", "com.shinsaikou.reader.ReaderModeSwitchTest")
        self.assertEqual(code, 1)


class FailOnFailuresTest(RanTestsCase):

    def write(self, body: str) -> None:
        (self.dir / f"TEST-{DEVICE_DIR}.xml").write_text(
            f"<?xml version='1.0' encoding='UTF-8' ?><testsuites>{body}</testsuites>")

    # The shapes AGP writes: a <failure> for an assertion, an <error> for an exception the runner
    # caught outside one, and a <skipped/> for an ignored test, which is not a failure.
    FAILED = ('<testsuite name="com.shinsaikou.reader.DownloadedChapterReaderTest" tests="2" failures="1">'
              '<testcase name="opens" classname="com.shinsaikou.reader.DownloadedChapterReaderTest" />'
              '<testcase name="fallsThrough" classname="com.shinsaikou.reader.DownloadedChapterReaderTest">'
              '<failure message="NullPointerException">trace</failure></testcase></testsuite>')
    ERRORED = ('<testsuite name="A" tests="1" errors="1"><testcase name="a" classname="A">'
               '<error message="boom">trace</error></testcase></testsuite>')
    IGNORED = ('<testsuite name="B" tests="1" skipped="1"><testcase name="b" classname="B">'
               '<skipped /></testcase></testsuite>')

    def test_a_failed_case_is_listed_but_does_not_fail_the_run_without_the_flag(self):
        self.write(self.FAILED)
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("1 test(s) failed", out)
        self.assertIn("com.shinsaikou.reader.DownloadedChapterReaderTest#fallsThrough", out)

    def test_a_failed_case_fails_the_run_under_the_flag(self):
        self.write(self.FAILED)
        code, _, err = self.run_main("--fail-on-failures")
        self.assertEqual(code, 1)
        self.assertIn("1 test(s) failed, and this step fails on a failed test", err)

    def test_an_errored_case_counts_as_failed(self):
        self.write(self.ERRORED)
        code, out, _ = self.run_main("--fail-on-failures")
        self.assertEqual(code, 1)
        self.assertIn("A#a", out)

    def test_an_ignored_case_is_not_a_failure(self):
        self.write(self.IGNORED)
        code, out, _ = self.run_main("--fail-on-failures")
        self.assertEqual(code, 0)
        self.assertNotIn("failed", out)

    def test_a_clean_run_passes_under_the_flag_and_prints_no_failure_line(self):
        self.results({"A": 3}, logcat={})
        code, out, _ = self.run_main("--fail-on-failures")
        self.assertEqual(code, 0)
        self.assertNotIn("failed", out)

    def test_failures_in_every_xml_file_are_read(self):
        self.write(self.ERRORED)
        (self.dir / "TEST-other.xml").write_text(f"<testsuites>{self.FAILED}</testsuites>")
        code, out, _ = self.run_main("--fail-on-failures")
        self.assertEqual(code, 1)
        self.assertIn("2 test(s) failed", out)


class RequiredClassSpellingTest(unittest.TestCase):
    """The classes gate.sh requires must exist under those names, or step 5e can never pass."""

    def test_every_required_class_is_a_device_test_in_the_tree(self):
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        loop = re.search(r"for test_class in ([^;]+);", gate)
        self.assertIsNotNone(loop, "step 5e names its classes in one for loop")
        self.assertIn('--require-class "$test_class"', gate)
        classes = loop.group(1).split()
        self.assertEqual(len(classes), 2, "step 5e runs two classes on their own")
        for name in classes:
            path = ROOT / "app" / "src" / "androidTest" / "kotlin" / Path(*name.split(".")).with_suffix(".kt")
            self.assertTrue(path.is_file(), f"{name} is not at {path.relative_to(ROOT)}")
            self.assertIn(f"class {name.rsplit('.', 1)[1]} ", path.read_text())


ANCHORED_TEST = re.compile(r"^[ \t]*@Test[ \t]*$", re.M)
PACKAGE = re.compile(r"^package ([\w.]+)", re.M)
TOP_LEVEL_CLASS = re.compile(r"^(?:(?:internal|open|abstract) )*class (\w+)", re.M)
MODULE_LISTS = {"core/archive": "DEVICE_CLASSES_CORE_ARCHIVE", "reader": "DEVICE_CLASSES_READER",
                "app": "DEVICE_CLASSES_APP"}


def gate_array(gate: str, name: str) -> list[str]:
    block = re.search(rf"^{name}=\(\n(.*?)^\)", gate, re.M | re.S)
    if block is None:
        raise AssertionError(f"gate.sh defines no {name}=( ... ) array")
    return [line.strip() for line in block.group(1).splitlines()
            if line.strip() and not line.strip().startswith("#")]


def device_classes_in_tree(module: str) -> dict[str, Path]:
    """Every class under <module>/src/androidTest with a test in it, by fully qualified name."""
    found = {}
    for path in sorted((ROOT / module / "src" / "androidTest").rglob("*.kt")):
        text = path.read_text()
        if not ANCHORED_TEST.search(text):
            continue
        package = PACKAGE.search(text)
        classes = TOP_LEVEL_CLASS.findall(text)
        if package is None or len(classes) != 1:
            raise AssertionError(f"{path.relative_to(ROOT)} holds tests but not exactly one top-level class")
        found[f"{package.group(1)}.{classes[0]}"] = path
    return found


class DeviceClassListsTest(unittest.TestCase):
    """Phase 2's device steps require every class of these lists by name (unit L)."""

    gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()

    def test_every_module_with_device_tests_has_a_list(self):
        modules = {str(path.parent.parent.relative_to(ROOT)) for path in ROOT.glob("*/src/androidTest")}
        modules |= {str(path.parent.parent.relative_to(ROOT)) for path in ROOT.glob("*/*/src/androidTest")}
        self.assertEqual(modules, set(MODULE_LISTS))
        loop = re.search(r"^    for module in ([^;]+); do$", self.gate, re.M)
        self.assertIsNotNone(loop, "step 5 names its modules in one for loop")
        self.assertEqual({module.replace(":", "/") for module in loop.group(1).split()}, set(MODULE_LISTS))

    def test_each_list_names_exactly_the_device_test_classes_of_its_module(self):
        for module, name in MODULE_LISTS.items():
            with self.subTest(module=module):
                listed = gate_array(self.gate, name)
                self.assertEqual(len(listed), len(set(listed)), f"{name} names a class twice")
                in_tree = device_classes_in_tree(module)
                self.assertEqual(sorted(set(listed) - set(in_tree)), [],
                                 f"{name} names classes with no tests under {module}/src/androidTest")
                self.assertEqual(sorted(set(in_tree) - set(listed)), [],
                                 f"device test classes under {module}/src/androidTest missing from {name}")

    def test_device_checks_maps_each_module_to_its_own_list(self):
        for module, name in MODULE_LISTS.items():
            self.assertRegex(self.gate, rf'\n        {re.escape(module.replace("/", ":"))}\) classes=\("\${{{name}\[@\]}}"\) ;;')

    def test_the_download_classes_no_step_required_are_listed_and_delivered(self):
        app = gate_array(self.gate, "DEVICE_CLASSES_APP")
        for name in ("com.shinsaikou.reader.DownloadedChapterReaderTest",
                     "com.shinsaikou.downloads.DownloadNotifierForegroundChannelTest",
                     "com.shinsaikou.downloads.DownloadAdoptionDeviceTest"):
            with self.subTest(cls=name):
                self.assertIn(name, app)
                path = "app/src/androidTest/kotlin/" + name.replace(".", "/") + ".kt"
                self.assertRegex(self.gate, rf"(?m)^        {re.escape(path)}$", f"{path} is not a deliverable")


class TabletStepSpellingTest(unittest.TestCase):
    """gate.sh's expected skips must match the lines the tests really log."""

    def test_every_except_pattern_matches_a_logged_skip(self):
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        patterns = [shlex.split(m)[0] for m in re.findall(r"--except (\S.*?) (?:\\|\|\|)\s*$", gate, re.M)]
        self.assertEqual(len(patterns), 17, "the tablet step names seventeen expected skips")
        tests = ROOT / "app" / "src" / "androidTest" / "kotlin" / "com" / "shinsaikou"
        source = "".join((tests / name).read_text()
                         for name in ("ui/TwoPaneLayoutTest.kt", "data/SchemaUpgradeTest.kt",
                                      "signin/GoogleRoundTripDeviceTest.kt", "sync/SyncPairTest.kt",
                                      "sync/SyncDeviceCheckTest.kt", "sync/DriveTokenDeviceTest.kt",
                                      "tracking/MyAnimeListSignInDeviceTest.kt", "tracking/TrackSheetDeviceTest.kt",
                                      "tracking/TrackerCompareDeviceTest.kt", "gate/ScaleRowsTest.kt",
                                      "ui/browse/SourcesFilterDeviceTest.kt", "ui/browse/ExtensionFilterDeviceTest.kt",
                                      "ui/browse/ExtensionStoresDeviceTest.kt", "ui/browse/ExtensionsSearchFieldTest.kt",
                                      "ui/browse/FixtureSourcesDeviceTest.kt", "ui/manga/ChapterRowDeviceTest.kt"))
        # The skip reasons are string literals split over two lines with `+`; join them.
        joined = re.sub(r'"\s*\+\s*"', "", source)
        for pattern in patterns:
            self.assertRegex(joined, pattern)

    def test_the_store_guard_pattern_covers_each_guarded_class_step_5c_runs(self):
        # Phase 5 unit TG2: every class step 5c runs that opens Browse or builds the Extensions view
        # model returns before it while a store other than the browse fixture's is configured, and
        # one --except covers each class's line. GateRowsTest logs the same words but is not run by
        # step 5c (its own step does not fail on a skip), and its method name is a separate argument.
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        patterns = [shlex.split(m)[0] for m in re.findall(r"--except (\S.*?) (?:\\|\|\|)\s*$", gate, re.M)]
        guard = [p for p in patterns if "extension store other than" in p]
        self.assertEqual(len(guard), 1, patterns)
        tests = ROOT / "app" / "src" / "androidTest" / "kotlin" / "com" / "shinsaikou"
        for name in ("ui/SearchFieldTypingTest.kt", "ui/TwoPaneLayoutTest.kt",
                     "ui/browse/SourcesFilterDeviceTest.kt", "ui/browse/ExtensionFilterDeviceTest.kt",
                     "ui/browse/ExtensionStoresDeviceTest.kt", "ui/browse/ExtensionsSearchFieldTest.kt",
                     "ui/browse/FixtureSourcesDeviceTest.kt", "ui/browse/BrowseSelectionDeviceTest.kt"):
            source = re.sub(r'"\s*\+\s*"', "", (tests / name).read_text())
            self.assertRegex(source, guard[0], name)
        self.assertIn("an extension store other than the browse fixture store is configured",
                      re.sub(r'"\s*\+\s*"', "", (tests / "gate/GateRowsTest.kt").read_text()))

    def test_the_browse_fixture_pattern_covers_each_method_of_the_browse_selection_class(self):
        # Wave 2 unit browse-selection-device-test: BrowseSelectionDeviceTest logs the browse fixture
        # classes' line, `<method>: no saikou.browse.fixture argument`, so step 5c's one pattern for that
        # line covers it with no --except of its own (R93 item 6); each method passes its own name.
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        patterns = [shlex.split(m)[0] for m in re.findall(r"--except (\S.*?) (?:\\|\|\|)\s*$", gate, re.M)]
        fixture = [p for p in patterns if "browse" in p and "fixture argument" in p]
        self.assertEqual(len(fixture), 1, patterns)
        source = (ROOT / "app/src/androidTest/kotlin/com/shinsaikou/ui/browse/BrowseSelectionDeviceTest.kt").read_text()
        self.assertIn('skip("$method: no saikou.browse.fixture argument")', source)
        methods = re.findall(r"@Test\s+fun (\w+)\(\)", source)
        self.assertEqual(len(methods), 3, methods)
        for method in methods:
            with self.subTest(method=method):
                self.assertIn(f'withAFixtureListing("{method}"', source)
                self.assertRegex(f"BrowseSelectionDeviceTest: Skipped: {method}: no saikou.browse.fixture argument", fixture[0])

    def test_the_sync_device_checks_pattern_covers_each_of_its_steps_lines_and_no_other_classes(self):
        # Unit G's SyncDeviceCheckTest logs one line per step not asked for, `is not <step>`, the
        # step being each asked("...") in its source; one --except covers all of them, and it must
        # cover no other class's line (SyncPairTest's included).
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        patterns = [shlex.split(m)[0] for m in re.findall(r"--except (\S.*?) (?:\\|\|\|)\s*$", gate, re.M)]
        check = [p for p in patterns if "sync" in p and "check" in p]
        self.assertEqual(len(check), 1, patterns)
        source = (ROOT / "app/src/androidTest/kotlin/com/shinsaikou/sync/SyncDeviceCheckTest.kt").read_text()
        steps = re.findall(r'if \(!asked\("([a-z-]+)"\)\) return', source)
        self.assertEqual(len(steps), 6, steps)
        for step in steps:
            with self.subTest(step=step):
                self.assertRegex(f"SyncDeviceCheck: Skipped: saikou.sync.check is not {step}", check[0])
        for other in ("SyncPair: Skipped: no sync pair step named",
                      "SaikouSchemaUpgrade: Skipped: anUpgradedLibraryKeepsItsRowsAndLogsAsPhaseThreeDecides: no "
                      "saikou.upgrade.counts or saikou.upgrade.log argument"):
            self.assertNotRegex(other, check[0])


if __name__ == "__main__":
    unittest.main()

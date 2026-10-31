#!/usr/bin/env python3
"""Ratchet: every Saikou call to a PackageManager method the cold start trace cannot see runs inside
a trace section named for it (ruling R60).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

`main_thread_pm_okhttp_slices` does not count main thread binder calls to the IPackageManager
methods in `tools/bench/perfetto_metrics.py`'s PLATFORM_MAIN_THREAD_PM_METHODS, because the
platform and AndroidX make them on every application's main thread at cold start. A call Saikou makes
to one of them is therefore invisible from the binder side, and the row sees it only through the
`PackageManager*` sections it counts on the main thread. This test keeps that true: it reads the
method list from perfetto_metrics.py (one source of truth), scans every `*.kt` file under a
`src/main/` directory of every module, vendored files included, and fails, naming the file and the
line, on any call to a listed method that is not enclosed in a section of the same name.

The enclosure rule, kept to the one shape the product uses (the extension loader's archive parse):

    Trace.beginSection("PackageManager.<method>")
    try {
        ... the call ...
    } finally {
        Trace.endSection()
    }

A call counts as enclosed when a `Trace.beginSection("PackageManager.<same method>")` with the name
as a string literal sits on one of the OPEN_WINDOW lines above it, no `Trace.endSection()` falls
between that line and the call, and a `Trace.endSection()` follows within CLOSE_WINDOW lines from
the call's line. Anything else fails, including a section held in a constant, an
`androidx.tracing` lambda (neither is used for these calls) and a section named for another method.

What is a call: the method name followed by `(`, not preceded by `fun ` (a declaration) and not
on a line that starts as a comment. The receiver is not checked, so any call by one of these names
counts, which errs on the side of wrapping. Kotlin's property syntax for the one method on the list
that takes no argument, `getSdkSandboxPackageName()`, is `.sdkSandboxPackageName`, and that is
matched too; every other method on the list takes an argument, so it has no property form. Method
references (`PackageManager::getPackageInfo`) are not matched.
"""

from __future__ import annotations

import importlib.util
import os
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
METRICS_PATH = TOOLS / "bench" / "perfetto_metrics.py"

_spec = importlib.util.spec_from_file_location("perfetto_metrics", METRICS_PATH)
perfetto_metrics = importlib.util.module_from_spec(_spec)
sys.modules.setdefault(_spec.name, perfetto_metrics)
_spec.loader.exec_module(perfetto_metrics)

METHODS = perfetto_metrics.PLATFORM_MAIN_THREAD_PM_METHODS

# Lines above the call the section may open on, and lines after it the section must close within.
OPEN_WINDOW = 3
CLOSE_WINDOW = 6

# Kotlin property names of the zero-argument getters on the list.
PROPERTY_FORMS = {"getSdkSandboxPackageName": "sdkSandboxPackageName"}

CALL = re.compile(r"(?<![\w$])(fun\s+)?(" + "|".join(map(re.escape, METHODS)) + r")\s*\(")
PROPERTY = re.compile(
    r"\.(" + "|".join(map(re.escape, PROPERTY_FORMS.values())) + r")(?![\w$(])"
)
BEGIN = re.compile(r'\bTrace\.beginSection\(\s*"PackageManager\.(\w+)"\s*\)')
END = re.compile(r"\bTrace\.endSection\(\s*\)")

# Directories that hold no source of this checkout: build output and dependencies here, and every
# directory whose name starts with a dot (VCS data, Gradle and IDE state, and the other worktrees a
# main checkout keeps under `.worktrees`) in main_kotlin_files.
SKIPPED_DIRS = {"build", "node_modules"}


def calls(text: str) -> list[tuple[int, str]]:
    """Every (1-based line, method) call to a listed method in one Kotlin file."""
    by_property = {prop: method for method, prop in PROPERTY_FORMS.items()}
    found = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.lstrip()
        if stripped.startswith(("//", "/*", "*")):
            continue
        for match in CALL.finditer(line):
            if not match.group(1):
                found.append((number, match.group(2)))
        for match in PROPERTY.finditer(line):
            found.append((number, by_property[match.group(1)]))
    return found


def enclosed(lines: list[str], index: int, method: str) -> bool:
    """Whether the call on lines[index] sits inside a `PackageManager.<method>` section."""
    for above in range(index - 1, max(-1, index - 1 - OPEN_WINDOW), -1):
        if END.search(lines[above]):
            return False
        begin = BEGIN.search(lines[above])
        if begin:
            if begin.group(1) != method:
                return False
            after = lines[index:index + CLOSE_WINDOW + 1]
            return any(END.search(line) for line in after)
    return False


def unwrapped(text: str) -> list[tuple[int, str]]:
    """The calls in one Kotlin file that are not enclosed in their own section."""
    lines = text.splitlines()
    return [(number, method) for number, method in calls(text)
            if not enclosed(lines, number - 1, method)]


def main_kotlin_files(root: Path) -> list[Path]:
    """Every `*.kt` file under a `src/main/` directory below root, tracked or not."""
    found = []
    for directory, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".") and d not in SKIPPED_DIRS]
        parts = Path(directory).relative_to(root).parts
        in_main = any(parts[i] == "src" and parts[i + 1] == "main" for i in range(len(parts) - 1))
        if in_main:
            found.extend(Path(directory) / name for name in files if name.endswith(".kt"))
    return sorted(found)


class CallSiteTest(unittest.TestCase):

    def test_every_call_is_inside_its_section(self):
        failures = []
        for path in main_kotlin_files(ROOT):
            for number, method in unwrapped(path.read_text(encoding="utf-8")):
                failures.append(f"{path.relative_to(ROOT)}:{number}: {method} is not inside a "
                                f'Trace.beginSection("PackageManager.{method}") section')
        self.assertEqual(failures, [], "\n" + "\n".join(failures))

    def test_the_scan_sees_the_sources_and_the_calls(self):
        # A scan that reads nothing passes the test above vacuously (R29): prove it reads the
        # modules and finds at least one call to judge.
        files = main_kotlin_files(ROOT)
        self.assertGreater(len(files), 100)
        self.assertTrue(any(calls(path.read_text(encoding="utf-8")) for path in files))

    def test_the_method_list_is_the_metric_list(self):
        self.assertEqual(len(METHODS), 10)
        self.assertIn("getPackageInfo", METHODS)
        self.assertIn("hasSystemFeature", METHODS)


class RuleTest(unittest.TestCase):
    """The enclosure rule on synthetic Kotlin."""

    WRAPPED = """
        Trace.beginSection("PackageManager.getPackageInfo")
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } finally {
            Trace.endSection()
        }
    """

    def test_a_wrapped_call_passes(self):
        self.assertEqual(unwrapped(self.WRAPPED), [])

    def test_a_bare_call_fails_with_its_line(self):
        text = "val a = 1\nval info = pm.getPackageInfo(name, 0)\n"
        self.assertEqual(unwrapped(text), [(2, "getPackageInfo")])

    def test_a_section_for_another_method_fails(self):
        text = self.WRAPPED.replace("PackageManager.getPackageInfo", "PackageManager.hasSystemFeature")
        self.assertEqual(len(unwrapped(text)), 1)

    def test_a_section_closed_before_the_call_fails(self):
        text = """
            Trace.beginSection("PackageManager.hasSystemFeature")
            Trace.endSection()
            pm.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
        """
        self.assertEqual(len(unwrapped(text)), 1)

    def test_a_section_that_never_closes_fails(self):
        text = """
            Trace.beginSection("PackageManager.hasSystemFeature")
            return pm.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
        """
        self.assertEqual(len(unwrapped(text)), 1)

    def test_a_section_opened_too_far_above_fails(self):
        text = ('Trace.beginSection("PackageManager.getPackageInfo")\n' + "val x = 1\n" * OPEN_WINDOW
                + "pm.getPackageInfo(name, 0)\nTrace.endSection()\n")
        self.assertEqual(len(unwrapped(text)), 1)

    def test_a_section_held_in_a_constant_fails(self):
        text = self.WRAPPED.replace('"PackageManager.getPackageInfo"', "SECTION")
        self.assertEqual(len(unwrapped(text)), 1)

    def test_the_property_form_of_the_zero_argument_getter_is_a_call(self):
        self.assertEqual(unwrapped("val name = pm.sdkSandboxPackageName\n"),
                         [(1, "getSdkSandboxPackageName")])

    def test_declarations_comments_and_longer_names_are_not_calls(self):
        text = """
            fun getPackageInfo(name: String): PackageInfo = TODO()
            // pm.getPackageInfo(name, 0) once did this
             * [PackageManager.getPackageInfo] in a KDoc line
            val c = PackageInfoCompat.getLongVersionCode(info)
            pm.getPackageArchiveInfo(path, 0)
            myGetPackageInfo(name)
            wrapped_getPackageInfo(name)
        """
        self.assertEqual(calls(text), [])

    def test_every_listed_method_is_matched(self):
        for method in METHODS:
            self.assertEqual(calls(f"pm.{method}(x)\n"), [(1, method)], method)


if __name__ == "__main__":
    unittest.main()

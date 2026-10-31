## What and why

<!-- What this changes and the problem it solves. Link the issue: "Fixes #123". -->

## How it was tested

<!-- The tests you added or ran, and on which device or emulator for UI changes. -->

## Checklist

- [ ] `./gradlew checkModuleGraph checkVendoringMarkers testDebugUnitTest` passes
- [ ] `python3 -m unittest discover -s tools -p 'test_*.py'` passes, if `tools/` changed
- [ ] A test covers the change (for a fix, one that failed before it)
- [ ] User-visible strings are in `:i18n`
- [ ] Vendored files changed are marked `modified: yes` with a note
- [ ] CHANGELOG.md has a line under Unreleased, if a user would notice
- [ ] No source, extension or catalog names, and no em dashes (see CONTRIBUTING.md)

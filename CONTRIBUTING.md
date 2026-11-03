# Contributing to Saikou

Thanks for taking the time. This file says how to build and test Saikou, what a change has to pass
before it is merged, and the few rules the repository holds every file to.

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems go
through [SECURITY.md](SECURITY.md), never a public issue.

## Before you start

- **Bugs:** open an issue with the bug report form. Say which version, which Android version and
  device, and how to reproduce it.
- **Features and larger changes:** open an issue first, so the approach can be agreed before you
  spend time on it.
- **Small fixes** (typos, an obvious bug with a test) can go straight to a pull request.

## Setting up

Requirements:

- JDK 17 for the source and target level, and a JVM 21 for the Gradle daemon (the Metro Gradle
  plugin needs it; `gradle/gradle-daemon-jvm.properties` selects it).
- The Android SDK with platform 37 installed: `sdkmanager "platforms;android-37.0"`.
- git on the PATH, and Python 3 for the tools and their tests.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### local.properties

`local.properties` (gitignored) holds the Android SDK location and the private values some tools
and instrumentation tests read. Environment variables win over `local.properties` when both are
set. None of these is needed to build the app or run the unit tests.

| `local.properties` key | Environment variable | Used by | Meaning |
|---|---|---|---|
| `sdk.dir` | `ANDROID_SDK_ROOT` | AGP, tools | Android SDK root |
| `saikou.image.dir` | `SAIKOU_IMAGE_DIR` | Gradle | a local saikou-image checkout to build against instead of the published artifact |
| `saikou.drive.client.id`, `saikou.drive.client.secret` | `SAIKOU_DRIVE_CLIENT_ID`, `SAIKOU_DRIVE_CLIENT_SECRET` | Gradle | the Google Drive OAuth client; without it the build offers no Drive sign-in |
| `saikou.mal.client.id` | `SAIKOU_MAL_CLIENT_ID` | Gradle | the MyAnimeList API client id; without it the build has no MyAnimeList tracking |
| `saikou.repo.url` | `SAIKOU_REPO_URL` | instrumentation, compat job | index URL of the catalog, the same string the app is configured with |
| `saikou.ext.source.repo.url` | `SAIKOU_EXT_SOURCE_REPO_URL` | compat job (`--full`) | git URL of the extensions' source repository |
| `saikou.catalog.signing.key.sha256` | `SAIKOU_CATALOG_SIGNING_KEY_SHA256` | compat job, instrumentation | overrides the fingerprint the index carries |
| `saikou.ref.ext14.pkg` | `SAIKOU_REF_EXT14_PKG` | instrumentation | package of the reference lib-1.4 extension |
| `saikou.ref.ext16.pkg` | `SAIKOU_REF_EXT16_PKG` | instrumentation | package of the reference lib-1.6 extension |
| `saikou.fixtures.dir` | `SAIKOU_FIXTURES_DIR` | instrumentation, compat job, bench | host directory of recorded fixtures, pushed to the device |
| `saikou.backup.file` | `SAIKOU_BACKUP_FILE` | instrumentation | host path of a real `.tachibk` backup; `tools/ci/phase0.sh` pushes it to the device |
| `saikou.strict` | `SAIKOU_STRICT` | instrumentation | `true` makes a missing argument a failure instead of a skip |
| `saikou.compat.sample.size` | `SAIKOU_COMPAT_SAMPLE_SIZE` | compat job | default 320 |
| `saikou.compat.seed` | `SAIKOU_COMPAT_SEED` | compat job | default 20260911 |
| `saikou.anilist.client.id` | `SAIKOU_ANILIST_CLIENT_ID` | Gradle | the AniList API client's id; register its redirect as exactly `http://127.0.0.1:18796/callback`, since the sign-in page doesn't send one and AniList uses the registered address. Blank leaves AniList without a sign-in |
| `saikou.forbidden.words.file` | `SAIKOU_FORBIDDEN_WORDS_FILE` | scan | absolute path of the forbidden-word regex list, kept outside the repository |
| `saikou.upstream.package` | `SAIKOU_UPSTREAM_PACKAGE` | comparison benches | application id of the upstream app the benches compare against |
| `saikou.upstream.scheme` | `SAIKOU_UPSTREAM_SCHEME` | `library_update_seed.sh` | its deep link scheme, without `://` |
| `saikou.upstream.storage.dir` | `SAIKOU_UPSTREAM_STORAGE_DIR` | comparison benches | the folder it creates in shared storage (a bare name) |
| `saikou.upstream.apk` | `SAIKOU_UPSTREAM_APK` | comparison benches | its v0.20.4 APK |
| `saikou.upstream021.apk` | `SAIKOU_UPSTREAM021_APK` | comparison benches | its v0.21.0 APK |
| `saikou.upstream.dir` | `SAIKOU_UPSTREAM_DIR` | gate step 3b | a checkout of its source at v0.20.4, for `tools/gate/upstream_backup_fields.py` |

### Working on saikou-image

The image utilities live in [saikou-image](https://github.com/ShinSaikou/saikou-image) and arrive
from JitPack at the version `gradle/libs.versions.toml` pins. To change them, clone that repository
next to this one and point `saikou.image.dir` at it; Gradle then builds the library from your
checkout (a composite build), so a change there can be tested here before it is tagged.

### Release signing

`keystore.properties` (gitignored, optional) holds `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. The release build, and the `publicDebug` build each release ships beside it, sign
with the debug key unless `-Psaikou.ownerSigning=true` is given, so a fresh clone can always build a
release:

```bash
./gradlew :app:assembleRelease
./gradlew :app:assembleRelease :app:verifyReleaseSigning -Psaikou.ownerSigning=true
```

With the property set, every release task fails when the file is absent or incomplete, and a blank
or unknown value fails the configuration rather than turning signing on.

## Project layout

| Module | What lives there |
|---|---|
| `:app` | the application, its screens and navigation |
| `:reader` | the reader shell, viewers and page pipeline |
| `:extensions` | the extension API, loader, trust checks and catalog clients |
| `:source-contract` | what a source implementation can see |
| `:sources:local` | the Local source |
| `:domain` | models, repository interfaces and interactors |
| `:data` | SQLDelight database and repository implementations |
| `:downloads` | the download engine and the copy from a downloads folder |
| `:library-update` | the library update worker |
| `:backup` | `.tachibk` import and export |
| `:sync` | Google Drive sync |
| `:tracking` | MyAnimeList and AniList |
| `:presentation-core` | shared Compose components and themes |
| `:core:common`, `:core:archive`, `:core:metadata` | shared plumbing, archives, ComicInfo |
| `:i18n` | string resources |
| `:bench` | macrobenchmarks |

`tools/` holds the test, benchmark and release tooling (Python and shell).

## Testing

Unit tests are JUnit 5 with kotest assertions, mockk, turbine and `kotlinx-coroutines-test`. They
never touch the network and never use Robolectric. Instrumentation tests use the AndroidX runner
and live in `:app` (and a few in `:reader`).

```bash
./gradlew testDebugUnitTest                    # every module
./gradlew :data:testDebugUnitTest              # one module
./gradlew :app:connectedDebugAndroidTest       # needs a device or emulator
python3 -m unittest discover -s tools -p 'test_*.py'
```

A missing instrumentation argument skips the test that needs it, unless `saikou.strict=true`.

## What a change has to pass

Run these before you open a pull request:

```bash
./gradlew checkModuleGraph checkVendoringMarkers testDebugUnitTest
python3 -m unittest discover -s tools -p 'test_*.py'
```

- `checkModuleGraph` fails on a project dependency, library or import the module graph does not
  allow, so a misplaced dependency fails the build rather than a review.
- `checkVendoringMarkers` fails on a file in a vendored package whose first line does not say where
  it came from (see [Vendored code](#vendored-code)).
- `tools/scan-forbidden-words.sh` runs the wording rules below over the tracked files, the staged
  diff and unpushed commit messages. Its word list lives outside the repository; without it, still
  follow the rules by hand. It also fails on any em dash.

## Code conventions

- Fresh code lives under `com.shinsaikou.<module>`. Vendored files keep their upstream package (see
  below).
- Where a file with no obvious home goes: shared plumbing (network, preferences, storage) belongs to
  `:core:common`; anything a source implementation can see to `:source-contract`; models,
  repository interfaces and interactors to `:domain`; SQLDelight and repository implementations to
  `:data`; the extension API, the loader, trust and the catalog clients to `:extensions`, which never
  depends on `:data` or `:app`; Compose screens to `:app`, except the reader shell, which is
  `:reader`.
- RxJava and Injekt appear only in `:extensions`, where the extension API needs them.
- `PackageManager` work, `OkHttpClient` construction, class loading and database access run on
  `Dispatchers.IO`, injected as `com.shinsaikou.core.common.coroutines.Dispatchers` so tests can
  substitute them.
- ViewModels expose `StateFlow`.
- User-visible strings live in `:i18n` and are read as `com.shinsaikou.i18n.R.string.*`.
- Java source and target are 17; minSdk 26, targetSdk 36, compileSdk 37.
- Formatting follows `.editorconfig` (Kotlin official style).

## Wording rules

These apply to every tracked file (source, resources, Gradle files, tests, docs, scripts) and to
commit messages, branch names, issues and pull requests. A public repository's history is forever.

Never write:

1. The name of any manga source website, in any spelling, casing or abbreviation.
2. The name of any extension, extension theme, or organization that publishes extensions.
3. The URL of any extension catalog, its index, its repo descriptor or its source repository.
4. Any signing key fingerprint or certificate hash of an extension catalog.
5. Any extension package name.
6. The name of any person or account associated with an extension catalog.

Say instead:

- "third-party extensions" for the extension ecosystem as a whole;
- "the catalog" for an extension repository, "a catalog" when there may be several;
- "the catalog signing key" for its signing certificate;
- "the reference lib-1.4 extension" and "the reference lib-1.6 extension" for the two extensions the
  compatibility runs use;
- placeholders where a value must appear in an example: `<CATALOG_URL>`,
  `<CATALOG_SIGNING_KEY_SHA256>`, `<ref-ext-14>`, `<ref-ext-16>`.

Real values never enter a tracked file. They arrive through `local.properties`, environment
variables, or instrumentation arguments that `app/build.gradle.kts` copies from those two. Test
fixtures that need real URLs or names live outside the repository, in the directory
`SAIKOU_FIXTURES_DIR` names, and are pushed to the device at test time. Every name in a test fixture
is invented.

Typography: no em dash character anywhere. Use a comma, a colon, a period, a semicolon or
parentheses.

## Vendored code

Part of Saikou is taken from an upstream Android manga reader under the Apache License 2.0;
[NOTICE](NOTICE) names it. A vendored file:

- keeps its upstream package and any license header it had (the one exception: packages upstream
  named after itself use the `tachiyomi` prefix here, as [NOTICE](NOTICE) explains);
- starts with the line
  `// Vendored from upstream v<version>: <upstream path> (modified: yes|no) [what changed]`;
- is marked `modified: yes`, with a short note of what changed and why, as soon as you change
  anything in it (an import moved by a refactor counts).

A fresh file that has to live in a vendored package (to reach package-private members, for
example) starts with `// Fresh: <why it lives here>` instead. Code taken from anywhere else keeps
its own header and is added to [NOTICE](NOTICE).

## Releasing

The app updates itself from the latest GitHub release of `ShinSaikou/saikou`; a fork builds with
`-Psaikou.updateRepo=<owner>/<name>` to offer its own releases instead.

1. Raise `versionCode` by one and set `versionName` in `app/build.gradle.kts`, and move the
   **Unreleased** lines of [CHANGELOG.md](CHANGELOG.md) under the new version.
2. Build the release and the debug APK with the owner's key:

   ```bash
   ./gradlew :app:assembleRelease :app:assemblePublicDebug :app:verifyReleaseSigning -Psaikou.ownerSigning=true
   ```

3. Check that the two belong together and give them their release names, `app-release.apk` and
   `app-debug.apk`, in `app/build/outputs/release-assets/`. It refuses a pair whose packages,
   versions or signing keys do not match, and prints the command for the next step:

   ```bash
   python3 tools/release/release_assets.py
   ```

4. Publish with the `gh release create` line step 3 printed, the version name as the tag. It names
   only the APKs step 3 copied. With both it reads:

   ```bash
   gh release create v<version> app/build/outputs/release-assets/app-release.apk app/build/outputs/release-assets/app-debug.apk -R ShinSaikou/saikou --notes-file <notes>
   ```

   and with the release APK alone:

   ```bash
   gh release create v<version> app/build/outputs/release-assets/app-release.apk -R ShinSaikou/saikou --notes-file <notes>
   ```

   0.1.2 (versionCode 5) is the first release whose updater tells the two APKs apart. The updater
   of 0.1.0 and 0.1.1 takes the only `.apk` of a release, so it finds none in a release with two.
   So 0.1.2 ships `app-release.apk` alone, and `app-debug.apk` is attached from the release after
   it. `release_assets.py` enforces this: while `tools/gate/last-release.json` records a versionCode
   below 5, and for the release at 5 itself, it copies only `app-release.apk`, says why, and prints
   the `gh release create` line with that one asset. The pair starts once the file records 5 or
   more, which `install_release.py` writes after 0.1.2 is on the owner's devices; commit it then.
   The notes of the first release with both APKs carry this sentence for anyone still on 0.1.0 or 0.1.1:
   "If the app says no APK was found for your device, download the update from
   https://shinsaikou.com."

`app-release.apk` is the app; the site's Download button links to
`releases/latest/download/app-release.apk`, so that name never changes. `app-debug.apk` is the
`publicDebug` build: debuggable, without R8, as `com.shinsaikou.debug` and labelled "Shin Saikou
Debug", so it installs next to the app with a library of its own, for trying a fix and sending a bug
report (More > About lists both). The `debug` build stays `com.shinsaikou` for local work, the
tools and the device tests.

The updater offers a release whose tag (`v0.0.2` or `0.0.2`) is newer than the installed version,
never a draft or a prerelease. The release app takes only the APKs without `debug` in their name,
and the debug APK only those with it; among those it installs the one named for the device's ABI,
else the one named `universal`, else the only one. It refuses an APK that is not signed with the
same key as the installed app, so a release signed with any other key, the debug key included,
cannot update an installed copy.

## Commits and pull requests

- One topic per pull request, against `main`. Rebase rather than merge `main` into your branch.
- Commit subjects are imperative and under 72 characters, with the module first when it helps
  (`reader: keep the page when the strip splits`). No attribution trailers, no source names, no
  URLs other than links to issues and pull requests of this project or upstream.
- Add a line under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for anything a user would
  notice.
- New behaviour comes with a test. A bug fix comes with a test that failed before it.
- UI changes: add a screenshot or a short recording to the pull request.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License, Version 2.0](LICENSE), the same as the rest of the project.

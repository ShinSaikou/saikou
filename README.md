# Saikou

Saikou is a manga reader for Android. It reads from third-party extensions built for the Tachiyomi
extension API (lib 1.4 and 1.6), from your own files, and from what you have downloaded, and it
keeps your library in step across devices through your own Google Drive or WebDAV server.

No telemetry, no crash reporting, no ads and no server of its own. The only network traffic is to
the sources you read, the extension catalogs you add, MyAnimeList and AniList if you link them,
Google Drive or your WebDAV server if you turn on sync, and GitHub: Saikou asks it for the latest
release at most once a day (**Settings > Advanced > Check for updates automatically** turns that
off) or when you tap Check for updates, and downloads an update from it only when you tap Update.

> Status: early. The current release is 0.0.1, a closed test. Expect rough edges, and back up your
> library before you try it.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/assets/screenshots/library-dark.webp">
    <img src=".github/assets/screenshots/library.webp" alt="The Library tab: a grid of covers with unread counts" width="200">
  </picture>
  <img src=".github/assets/screenshots/entry.webp" alt="An entry's page: cover, author, status, description, genres and the chapter list" width="200">
  <img src=".github/assets/screenshots/reader.webp" alt="The paged reader with its controls open: title, chapter, page slider and actions" width="200">
  <img src=".github/assets/screenshots/long-strip.webp" alt="The long-strip reader showing a colour webcomic" width="200">
</p>
<p align="center"><sub>Screenshots of Saikou 0.0.1 on Android. Pepper&amp;Carrot by David Revoy, <a href="https://www.peppercarrot.com">www.peppercarrot.com</a>, licensed under <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a> (episode 6 English translation by Alex Gryson; pages resized). The other comics are in the public domain: works by Winsor McCay, George Herriman, R. F. Outcault, Katsushika Hokusai, Okamoto Ippei and Kawanabe Kyosai (photographs of the Kyosai book by The Metropolitan Museum of Art, CC0).</sub></p>

## Features

- **Library** with categories and one level of sub-categories, filters, sorting, a fast search
  and per-entry edits of the title and description.
- **Browse** sources from the extension catalogs you add, with global search, a source filter,
  pinned sources, multi-select and migration of entries between sources.
- **Reader** in paged and long-strip (webtoon) modes, with tall-strip splitting, animated images,
  double-page handling, page notes and crop borders.
- **Downloads** as folders of pages, download ahead, automatic cleanup rules, and a copy of an
  existing downloads folder from a compatible reader, whose CBZ chapters open as they are.
- **Local source** for CBZ, ZIP, CBR, RAR, CB7, 7z, CBT, TAR and EPUB files and folders of images on
  the device.
- **Backup and restore** in the `.tachibk` format, read and written as a stream, with a report of
  missing sources and trackers before anything is restored.
- **Sync** of the library, categories, reading progress, history, bookmarks and tracking between
  devices through Google Drive's private app folder (`drive.appdata` only) or a WebDAV server you
  run. Settings and extensions stay on each device.
- **Tracking** with MyAnimeList and AniList.
- **Updates, history and statistics**, an app lock, a secure screen option, and a choice of
  themes.

## How it compares

The same 20,124-entry library and the same steps, timed on the same Android emulator: Saikou 0.0.1
against Mihon v0.21.0, the reader most people come from. Lower is better in every panel, and the
one place Saikou loses, storage, stays in.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/comparison-dark.svg">
  <img src=".github/assets/comparison-light.svg" alt="Benchmark comparison of Saikou 0.0.1 and Mihon v0.21.0; the same numbers are in the table below" width="720">
</picture>

<details>
<summary>The numbers as a table</summary>

| Measurement | Saikou 0.0.1 | Mihon v0.21.0 |
|---|---|---|
| Library update, 2,000 entries across 20 sources | 41.5 s | 255.3 s |
| Cold start to library content, 20,124 entries | 1.24 s | 1.78 s |
| Statistics screen, 20,124 entries | 0.20 s | 1.38 s |
| Restore one 87 MB backup, 20,124 entries | 139.3 s | runs out of memory before it starts |
| Memory with the library open (PSS) | 141 MB | 151 MB |
| Storage after the restore | 1,461 MB | 987 MB |

Measured 2026-10-14 on an Android emulator (Pixel 9 profile, API 36, 4 GB) with the network off,
against the official Mihon v0.21.0 APK. Saikou restored one 87 MB backup holding the 20,124 entries.
Mihon runs out of memory on that file, so it restored the same data split into eleven backups, in
86.5 s in total (Saikou was not timed on those). Memory and storage were
measured after each app's restore. One sample per scenario except the library update (two runs each,
against a local test server that answers in 0.4 to 0.8 s and allows 5 requests per second per
source). Storage was measured before schema 4 dropped unused chapter indexes, about 300 MB at this
size. Mihon v0.20.4 took 246.0 s on the same library update bench; Mihon releases after v0.21.0 were
not measured. The chart is drawn from `tools/charts/comparison.json` by
`python3 tools/charts/comparison.py .github/assets`.

</details>

Next to other readers:

| | Saikou | Mihon | Komikku | TachiyomiSY | Mangayomi | Aidoku |
|---|---|---|---|---|---|---|
| Platforms | Android | Android | Android | Android | Android, iOS, Windows, macOS, Linux | iOS, iPadOS, macOS |
| Reads Tachiyomi extensions | Yes, lib 1.4 and 1.6 | Yes | Yes | Yes | Yes, one of several engines | No, its own sources |
| Sync between devices | Built in: Google Drive or WebDAV | No | Google Drive, WebDAV or a SyncYomi server | Google Drive or a SyncYomi server | A self-hosted sync server | iCloud |
| License | Apache-2.0 | Apache-2.0 | Apache-2.0 | Apache-2.0 | Apache-2.0 | GPL-3.0 |
| Latest release | 0.0.1, closed test | v0.21.3, 2026-10-25 | v1.14.1, 2026-07-17 | 1.13.2, 2026-07-13 | v0.9.9, 2026-10-12 | v0.9-4, 2026-10-16 |

<sub>Features as each project documents them (checked 2026-09-10); licenses and releases read from
each project's GitHub repository on 2026-10-30.</sub>

## Install

There is no store listing yet. Build the APK from source (below) and install it with
`adb install`. Saikou needs Android 8.0 (API 26) or later.

Saikou ships no extensions and no catalog. Add a catalog you trust in **Browse > Extensions**, or
restore a backup that already names one. Extensions are third-party code that runs inside the app,
so only install ones you trust.

## Build

You need JDK 17, a JVM 21 for the Gradle daemon (`gradle/gradle-daemon-jvm.properties` selects
it), and the Android SDK with platform 37 installed (`sdkmanager "platforms;android-37.0"`).

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A release build signs with the debug key unless you pass `-Psaikou.ownerSigning=true` with a
`keystore.properties` in place; see [CONTRIBUTING.md](CONTRIBUTING.md) for that, for the tests and
for the checks a change has to pass.

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

## Related projects

- [saikou-image](https://github.com/ShinSaikou/saikou-image): the image utilities the reader and
  the download engine use, published as a library.
- The website, the [user guide](https://shinsaikou.com/docs/) and the
  [privacy policy](https://shinsaikou.com/privacy-policy) live at
  <https://shinsaikou.com/>.

## Contributing

Bug reports, fixes and ideas are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first, and the
[Code of Conduct](CODE_OF_CONDUCT.md). Report security problems privately, as
[SECURITY.md](SECURITY.md) describes, not in a public issue.

## License

Saikou is licensed under the [Apache License, Version 2.0](LICENSE). It includes code from other
projects under their own licenses; [NOTICE](NOTICE) lists them.

Saikou does not host, provide or endorse any content. Extensions and the sources they read are
maintained by third parties.

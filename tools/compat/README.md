# Compatibility job

`compat_job.py` samples the catalog, verifies every sampled jar against the catalog signing key,
resolves each jar's class references with `jdeps` against the classes the shipped release APK
defines, and optionally replays recorded fixtures on a device.

Run:

```bash
export SAIKOU_REPO_URL=...                 # or local.properties saikou.repo.url
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew :app:assembleRelease                        # the APK the job resolves against
python3 tools/compat/compat_job.py --smoke            # 40 jars
python3 tools/compat/compat_job.py --full             # 320 jars, needs SAIKOU_EXT_SOURCE_REPO_URL
SAIKOU_SERIAL=<serial> python3 tools/compat/compat_job.py --full --replay
```

Every environment variable is listed in contract 01, section 7.2. Outputs land in
`SAIKOU_COMPAT_OUT` (default `tools/out/compat/<UTC timestamp>/`), which is gitignored: `report.json`,
`summary.txt`, `sample.txt`, `unresolved.txt`, `jdeps/` and `replay/`. Package names appear only
there, never on stdout.

Exit codes: 0 pass; 1 a pass criterion failed (the report is still written); 2 missing or invalid
input, which includes the release APK not being built; 3 network failure after 3 retries; 4 a
required program is missing.

Every configured input comes from an environment variable or, when that is unset, from the matching
`local.properties` key (contract 01, section 10). Nothing is read from a tracked file.

## What the sample is resolved against

The job reads the class definitions out of every `classes*.dex` entry of
`app/build/outputs/apk/release/app-release.apk`, so **run `./gradlew :app:assembleRelease` first**;
without it the job exits 2 and names the path it looked for. Step 2 of `tools/ci/phase0.sh` builds
that APK before step 6 runs the job, so the gate needs no extra build. `--apk <path>` points the
read somewhere else.

The shipped APK rather than a Gradle classpath, for two reasons. It is the set of classes an
installed extension can actually reach, after R8 has removed and renamed everything the consumer
rules did not keep, so a class an extension names that R8 stripped is reported as the real gap it
is; a classpath taken from the module graph hides exactly that. And the classpath that was read
before belonged to `:extensions` alone, while `:app` carries classes `:extensions` does not, so
classes that really do ship read as unresolved and failed a row whose floor is 1.0.

`android.jar` is indexed alongside the APK so `android.*` resolves against the real platform, and
`java.*` stays allowlisted by prefix (contract 01, section 11, decision 19).

`index_fields.json` holds the protobuf field numbers of the index. A unit test in `:extensions`
asserts they equal the `@ProtoNumber` annotations of the Kotlin client, so the two parsers cannot
drift.

## What the sample asks Injekt for

The job and `CompatReplayTest` only load extensions. An extension that asks Injekt for a type the
host never registered loads fine and throws on first use, usually from a file-level initializer, so
neither sees it. `injekt_types.py` reads every requested type out of the APKs in
`<saikou.fixtures.dir>/compat/` (the `FullTypeReference<T>` Signature annotations `dexdump -a`
prints) and checks each against the `Injekt.addSingleton<T>` and `Injekt.addSingletonFactory<T>`
calls in `InjektBootstrap.kt`. No device and no build is needed:

```bash
python3 tools/compat/injekt_types.py                 # or --apks <dir>
```

It exits 1 and names the fixture files when a requested type is not registered. It found the
`ProtoBuf` gap two of the 320 sampled extensions hit before `InjektBootstrap` registered it.

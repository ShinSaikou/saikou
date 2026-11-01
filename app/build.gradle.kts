import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import saikou.gradle.gitShortSha
import saikou.gradle.signing.OwnerSigning
import saikou.gradle.signing.ReleaseKey
import saikou.gradle.signing.VerifyReleaseSigning

plugins {
    alias(libs.plugins.saikou.android.application)
    alias(libs.plugins.saikou.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

// local.properties and keystore.properties are gitignored; nothing read here is written to a tracked file.
fun loadProperties(name: String): Properties = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val localProperties = loadProperties("local.properties")
// Phase 3 unit R1: the release keeps the debug key unless -Psaikou.ownerSigning=true asks for the
// owner's, even when keystore.properties exists, so a fresh clone and phases 0 to 2 build as they
// always did. With the flag and no usable keystore.properties, every release task fails
// (checkOwnerSigning below) and the release has no signing config at all, so nothing can fall back
// to the debug key unseen.
val releaseKey = OwnerSigning.releaseKey(
    requested = OwnerSigning.requested(providers.gradleProperty(OwnerSigning.PROPERTY).orNull),
    keystoreProperties = rootProject.file(OwnerSigning.KEYSTORE_PROPERTIES),
    rootDir = rootDir,
)
// Blank counts as absent. An exported-but-empty variable is worse than an unset one here: the value
// becomes an empty `-e <key> ` pair on the am instrument command line, which shifts every argument
// after it, and the runner answers "Invalid userId -2" and runs no tests at all while Gradle reports
// BUILD SUCCESSFUL. SAIKOU_REPO_URL is exported empty by tools/ci/phase0.sh when local.properties has
// no catalog URL, which is how the whole :app device suite silently ran zero tests.
// The environment wins and local.properties is the fallback, which is contract 01 section 10 and
// what tools/common/localprops.sh and tools/compat/compat_job.py have always done. This file read
// them the other way round, so a value exported for a single run was silently overridden by a
// stale line in local.properties and the run measured something other than what it was told to.
// Blank is absent on both sides, so an exported-but-empty variable falls through to the file
// rather than shadowing it.
fun secret(key: String, env: String): String? =
    listOfNotNull(System.getenv(env), localProperties.getProperty(key)).firstOrNull { it.isNotBlank() }

/** This string as a Java string literal, quotes and backslashes escaped, for a `buildConfigField`. */
fun String.asJavaString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The GitHub repository whose latest release the in-app updater offers, as `owner/name`. A fork
// points it at its own releases with -Psaikou.updateRepo=owner/name. A blank or malformed value fails
// the configuration rather than shipping an updater that asks GitHub for nothing.
val updateRepo: String = providers.gradleProperty("saikou.updateRepo").orNull.let { value ->
    when {
        value == null -> "ShinSaikou/saikou"
        Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(value.trim()) -> value.trim()
        else -> throw GradleException("-Psaikou.updateRepo must be owner/name, got '$value'")
    }
}

android {
    namespace = "com.shinsaikou"

    defaultConfig {
        applicationId = "com.shinsaikou"
        // 0.0.1 was the closed test (2026-10-01) and 0.1.0 the first public release (2026-11-01); the
        // app stays on 0.x until the owner says otherwise. versionCode is a plain counter: it must be
        // above the code in tools/gate/last-release.json for install_release.py to accept the build,
        // and it goes up by one per release. The earlier major-times-10000 formula no longer applies.
        versionCode = 3
        versionName = "0.1.0"
        buildConfigField("String", "COMMIT_SHA", "\"${gitShortSha()}\"")
        buildConfigField("String", "UPDATE_REPO", updateRepo.asJavaString())
        // Whether coming to the foreground checks for an app update by itself: the release build
        // only. A debug build checks only when asked, and the benchmark build never by itself, so a
        // measured launch makes no request to GitHub.
        buildConfigField("boolean", "APP_UPDATE_AUTO_CHECK", "false")

        // The Google Drive client the owner registers (phase 3 Q1, owner item 2), a Desktop app
        // client whose secret ships in the APK by design. Empty when absent, and then the app offers
        // no Drive sign-in. The environment wins over local.properties, as for every value here.
        buildConfigField("String", "DRIVE_CLIENT_ID", secret("saikou.drive.client.id", "SAIKOU_DRIVE_CLIENT_ID").orEmpty().asJavaString())
        buildConfigField("String", "DRIVE_CLIENT_SECRET", secret("saikou.drive.client.secret", "SAIKOU_DRIVE_CLIENT_SECRET").orEmpty().asJavaString())
        // The MyAnimeList client the owner registers (phase 3 unit T2), type "other", which MyAnimeList
        // gives no secret: the id is all there is. Empty when absent, and then MyAnimeList stays in the
        // graph but offers no sign-in.
        buildConfigField("String", "MAL_CLIENT_ID", secret("saikou.mal.client.id", "SAIKOU_MAL_CLIENT_ID").orEmpty().asJavaString())
        // The AniList client the owner registers, whose redirect is the trackers' loopback
        // (http://127.0.0.1:18796/callback). Its implicit grant needs no secret, so the id is all
        // there is. Empty when absent, and then AniList stays in the graph but offers no sign-in.
        buildConfigField("String", "ANILIST_CLIENT_ID", secret("saikou.anilist.client.id", "SAIKOU_ANILIST_CLIENT_ID").orEmpty().asJavaString())

        // Phase 0 runs on x86_64 emulators and the owner's devices are arm64. Shipping the other
        // two ABIs added 22.7 MB of native code no target device can load.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Instrumentation arguments (section 8.3). Absent keys are simply not passed.
        //
        // saikou.fixtures.dir and saikou.backup.file are deliberately absent from this list. Both
        // name a path, and a path means two different things on the two sides of adb: the value in
        // local.properties is where the file sits on this machine, while the value the
        // instrumentation reads has to be where it sits on the device. Forwarding the host path
        // here handed the device a path it cannot open, and the failure reads as a missing file
        // rather than as the wrong side of the cable. tools/ci/phase0.sh pushes both and passes
        // the device paths on the Gradle command line, which overrides anything set here.
        listOf(
            "saikou.repo.url" to "SAIKOU_REPO_URL",
            "saikou.ref.ext14.pkg" to "SAIKOU_REF_EXT14_PKG",
            "saikou.ref.ext16.pkg" to "SAIKOU_REF_EXT16_PKG",
            "saikou.strict" to "SAIKOU_STRICT",
            // The sync fixture's base address for Google's calls in a debug build (phase 3 unit O).
            "saikou.google.base" to "SAIKOU_GOOGLE_BASE",
            // The MyAnimeList fake's base address for every MyAnimeList call in a debug build (phase 3 unit T2).
            "saikou.mal.base" to "SAIKOU_MAL_BASE",
        ).forEach { (key, env) -> secret(key, env)?.let { testInstrumentationRunnerArguments[key] = it } }
    }

    signingConfigs {
        if (releaseKey is ReleaseKey.Owner) {
            create("release") {
                storeFile = releaseKey.storeFile
                storePassword = releaseKey.storePassword
                keyAlias = releaseKey.keyAlias
                keyPassword = releaseKey.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            buildConfigField("boolean", "APP_UPDATE_AUTO_CHECK", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Two members unit R1's in-place upgrade check reaches and nothing shipped calls, kept in
            // the release built for that check alone. See the file.
            if (providers.gradleProperty("saikou.upgradeCheck").isPresent) proguardFiles("upgrade-check-rules.pro")
            signingConfig = when (releaseKey) {
                is ReleaseKey.Owner -> signingConfigs.getByName("release")
                is ReleaseKey.Unavailable -> null
                ReleaseKey.Debug -> signingConfigs.getByName("debug")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            buildConfigField("boolean", "APP_UPDATE_AUTO_CHECK", "false")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "benchmark-rules.pro")
        }
    }

    // AGP registers connected-test tasks only for this variant. It stays on debug for ordinary
    // runs; Phase 0 step 7 passes -Psaikou.testBuildType=release for the one run whose job is to
    // catch R8 stripping something the extension loader reaches by name.
    testBuildType = providers.gradleProperty("saikou.testBuildType").getOrElse("debug")

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

val checkOwnerSigning = tasks.register("checkOwnerSigning") {
    group = "verification"
    description = "Fails when -P${OwnerSigning.PROPERTY}=true was given without a usable keystore.properties."
    val reason = (releaseKey as? ReleaseKey.Unavailable)?.reason
    doLast { if (reason != null) throw GradleException(reason) }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkOwnerSigning) }

// Q18's row release_build_owner_signed reads the report this writes. The certificate comes from the
// release signing config's own values, so the check is of the key the build signed with.
val verifyReleaseSigning = tasks.register<VerifyReleaseSigning>("verifyReleaseSigning") {
    group = "verification"
    description = "Prints, per API range, whether the release APK carries the key keystore.properties names."
    ownerSigning.set(releaseKey is ReleaseKey.Owner)
    (releaseKey as? ReleaseKey.Owner)?.let { key ->
        val config = android.signingConfigs.getByName("release")
        storeFile.set(config.storeFile)
        storePassword.set(config.storePassword)
        keyAlias.set(config.keyAlias)
    }
    apksigner.set(
        androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/apksigner") },
    )
    report.set(layout.buildDirectory.file("outputs/release-signing/verifyReleaseSigning.json"))
}
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        verifyReleaseSigning.configure { apkDirectory.set(variant.artifacts.get(SingleArtifact.APK)) }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:archive"))
    implementation(project(":core:metadata"))
    implementation(project(":source-contract"))
    implementation(project(":sources:local"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":backup"))
    implementation(project(":reader"))
    implementation(project(":downloads"))
    implementation(project(":library-update"))
    implementation(project(":sync"))
    implementation(project(":tracking"))
    implementation(project(":presentation-core"))
    implementation(project(":i18n"))
    implementation(project(":extensions"))

    implementation(libs.bundles.androidx.lifecycle)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.sqldelight)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appCompat)
    // The app lock's prompt (phase 4 unit AL), at the version upstream v0.20.4 pins, in :app only.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work)
    implementation(libs.conscrypt)
    implementation(libs.material)
    // libs.metrox.android is not declared: its AAR requires minSdk 28 and contract 08 D1 does
    // not use MetroAppComponentFactory (see design/contract/01-build-ERRATA.md).
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodelCompose)
    // Only for SubsamplingScaleImageView.preferredBitmapConfig, which is a process-wide static and
    // must be written once from Application.onCreate rather than per reader activity.
    implementation(libs.subsamplingScaleImageView)
    // Upstream's chapter row swipe (phase 4 unit CS), the same library and version upstream pins. Apache
    // 2.0 by its POM.
    implementation(libs.swipe)
    // Upstream's drag reorder of the migration sources (phase 4 unit MB) and the library's drag handle
    // (phase 4 unit MS), the library and version upstream pins. Apache 2.0 by its POM.
    implementation(libs.reorderable)
    // Upstream's title matching for the batch migration (phase 4 unit MB): NormalizedLevenshtein, the
    // library and version upstream pins. MIT by its POM.
    implementation(libs.stringSimilarity)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // The sync worker's tests build it the way WorkManager does.
    testImplementation(libs.androidx.work.testing)
    // Migration's tests run its one transaction against the real schema and triggers (phase 4 unit MA).
    testImplementation(libs.sqldelight.sqliteDriver)

    // AGP pins the androidTest runtime classpath to the versions the app runtime resolved, so the
    // two APKs cannot disagree about a class. androidx.test:core:1.7.0 pulls concurrent-futures
    // 1.2.0 while androidx.core drags 1.1.0 into the app, and the pin turns that into a hard
    // resolution failure the first time :app resolves an androidTest classpath at all. Raising the
    // app side to 1.2.0 is what makes the two agree; nothing here uses the library directly.
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }

    androidTestImplementation(libs.bundles.androidx.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The compose BOM is applied to `implementation` by the convention plugin only, so the
    // instrumentation configuration has to platform() it again or ui-test-junit4 resolves with no
    // version. MainActivitySmokeTest is the only caller.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.uiTest)
    debugImplementation(libs.androidx.compose.uiTestManifest)
}

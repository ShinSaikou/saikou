plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.sync"

    // SyncHttpClientTest builds the real NetworkHelper, whose cookie jar and Cloudflare interceptor
    // touch platform statics at construction; returning defaults lets them construct on the JVM.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":domain"))

    implementation(libs.okhttp.logging)

    testImplementation(libs.kotlinx.coroutines.test)

    // The sync engine's tests run against a real database through :data's SyncStore. Test scope
    // only: checkModuleGraph reads the api and implementation configurations, and :sync's main
    // code reaches the database through :domain's interface alone.
    testImplementation(project(":data"))
    testImplementation(libs.sqldelight.sqliteDriver)

    // The convergence suite restores its devices from one generated backup through the real
    // importer, as the owner's two devices were; the rest are the types its constructor takes.
    testImplementation(project(":backup"))
    testImplementation(project(":source-contract"))
    testImplementation(project(":tracking"))
    testImplementation(project(":library-update"))
    // Auto-download's two-store test runs `:downloads`' rule over the pulling device's database
    // (phase 3 unit AD); :sync's main code reaches it through :domain's AutoDownload alone.
    testImplementation(project(":downloads"))
    testImplementation(libs.kotlinx.serialization.protobuf)

    // The WebDAV backend's TLS test serves a self-signed certificate; every other WebDAV test runs
    // against the sync fixture, tools/bench/sync_fixture_server.py.
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
}

// MergeColumnCoverageTest reads the synced tables' columns from :data's .sq files, so a column
// added there reruns it and fails until a merge rule or the exclusion list names the column.
val sqlDelightSources = rootProject.layout.projectDirectory.dir("data/src/main/sqldelight")

// SyncVolumePushTest names its library's chapters from the volume parser's corpus in :domain's
// tests, so the share of names with a volume is the corpus's (phase 4 unit VG).
val volumeCorpus = rootProject.layout.projectDirectory.file(
    "domain/src/test/resources/com/shinsaikou/domain/chapter/volume-corpus.tsv",
)

// The WebDAV tests start the sync fixture with python3; a change to it reruns them.
val syncFixtureServer = rootProject.layout.projectDirectory.file("tools/bench/sync_fixture_server.py")
val updateFixtureServer = rootProject.layout.projectDirectory.file("tools/bench/update_fixture_server.py")
tasks.withType<Test>().configureEach {
    inputs.dir(sqlDelightSources).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("saikou.sqldelight.dir", sqlDelightSources.asFile.absolutePath)
    inputs.files(syncFixtureServer, updateFixtureServer).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("saikou.sync.fixture", syncFixtureServer.asFile.absolutePath)
    inputs.file(volumeCorpus).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("saikou.volume.corpus", volumeCorpus.asFile.absolutePath)
}

// SyncDigestMemoryTest digests 200,000 rows in a 64 MB heap, which only a fork of its own can give it
// (phase 5 unit SC1): testDebugUnitTestLowHeap runs that class alone with -Xmx64m, every other test
// task leaves it out, and testDebugUnitTest depends on it, so the gate's unit test step runs it.
val digestMemoryTest = "com.shinsaikou.sync.engine.SyncDigestMemoryTest"
val lowHeapTest = tasks.register<Test>("testDebugUnitTestLowHeap") {
    description = "Runs $digestMemoryTest in a 64 MB heap."
    group = "verification"
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    filter.includeTestsMatching(digestMemoryTest)
    maxHeapSize = "64m"
}
tasks.withType<Test>().configureEach {
    if (name != lowHeapTest.name) filter.excludeTestsMatching(digestMemoryTest)
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(lowHeapTest) }

plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.backup"

    // The worker test builds the restore notification on the JVM, and its cancel Intent is a
    // platform stub; returning defaults lets it construct instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":tracking"))
    implementation(project(":library-update"))
    implementation(project(":i18n"))

    implementation(libs.androidx.core)
    implementation(libs.bundles.serialization)
    implementation(libs.okio)
    implementation(libs.androidx.work)

    // Sync's secret store writes into the same preference store the exporter reads, and phase 3 Q8
    // holds that no secret reaches a backup. Test scope only: checkModuleGraph reads the api and
    // implementation configurations, and :backup itself knows nothing of :sync.
    testImplementation(project(":sync"))
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqldelight.sqliteDriver)
    testImplementation(libs.turbine)
}

// The owner's real backup is named by an environment variable, never by a path in the repo. A
// Gradle daemon that was already running when the variable was exported does not see it, so the
// value is read at configuration time and forwarded to the test workers.
tasks.withType<Test>().configureEach {
    environment("SAIKOU_OWNER_BACKUP", System.getenv("SAIKOU_OWNER_BACKUP").orEmpty())
}

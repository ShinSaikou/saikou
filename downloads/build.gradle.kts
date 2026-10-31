plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.downloads"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:archive"))
    implementation(project(":core:metadata"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.work)

    // The engine's tests run against the real repositories on the in-memory JVM driver, so the
    // manifest insert and the queue delete are checked inside a real transaction. Test scope only:
    // checkModuleGraph reads the api and implementation configurations, and :downloads itself still
    // sees :data only through :domain's interfaces.
    testImplementation(project(":data"))
    testImplementation(libs.sqldelight.sqliteDriver)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

plugins {
    alias(libs.plugins.saikou.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.shinsaikou.data"
}

sqldelight {
    databases {
        create("Database") {
            // The generated class stays tachiyomi.data.Database so vendored .sq files and
            // mappers keep their package (05-data decision 7). Fresh Kotlin is com.shinsaikou.data.
            packageName.set("tachiyomi.data")
            dialect(libs.sqldelight.sqliteDialect338)
            schemaOutputDirectory.set(project.file("src/main/sqldelight/schemas"))
            // The .sq files are the current schema, version 6. Version 1 shipped at 49c3732 and is
            // the committed schemas/1.db; migrations/1.sqm takes it to 2 (phase 3 unit C),
            // migrations/2.sqm to 3 (phase 5 unit SC4), migrations/3.sqm to 4 (the chapter index
            // diet), migrations/4.sqm to 5 (merged entries) and migrations/5.sqm to 6 (the read and
            // bookmark clocks). Verification applies every migration to each committed snapshot and
            // diffs the result against the .sq files, so schemas/2.db to 6.db are committed beside
            // 1.db and a drift fails.
            verifyMigrations.set(true)
            generateAsync.set(true)
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":source-contract"))
    implementation(project(":domain"))

    implementation(libs.kotlinx.serialization.json)

    // api, not implementation: :backup runs its restore inside a database transaction and needs
    // the SQLDelight runtime types on its compile classpath.
    api(libs.bundles.sqldelight)
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(libs.sqldelight.sqliteDriver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}

// The schema snapshot in src/main/sqldelight/schemas is written by generateDatabaseSchema and
// read by verifyDatabaseMigration. Gradle rejects that undeclared read when both run in one
// invocation. Order them without forcing generation, so a verify run checks the committed
// snapshot instead of silently rewriting it.
tasks.matching { it.name.startsWith("verify") && it.name.endsWith("DatabaseMigration") }
    .configureEach {
        mustRunAfter(tasks.matching { it.name.startsWith("generate") && it.name.endsWith("DatabaseSchema") })
    }

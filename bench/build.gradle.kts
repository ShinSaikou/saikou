plugins {
    alias(libs.plugins.saikou.android.test)
}

android {
    namespace = "com.shinsaikou.bench"
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += "release"
        }
    }
}

dependencies {
    implementation(libs.bundles.androidx.test)
    implementation(libs.androidx.benchmark.macroJunit4)
    implementation(libs.androidx.test.uiautomator)
}

androidComponents {
    beforeVariants { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

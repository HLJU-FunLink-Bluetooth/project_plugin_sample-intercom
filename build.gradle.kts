plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hlju.funlinkbluetooth.plugin.sample.intercom"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core:plugin-api"))
    implementation(project(":core:designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.base)
    implementation(libs.bundles.miuix.base)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.bundles.unit.test.base)
    testImplementation(libs.test.mockk)
}

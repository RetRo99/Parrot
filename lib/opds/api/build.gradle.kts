version = "1.0"

// lib/opds/api: protocol contracts and the normalized OPDS model (plan §3.1).
// UI, database, server-registry, and app-error dependencies are excluded;
// version-specific parsing and wire handling live in lib/opds/implementation.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.opds.api"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines)
        }
    }
}

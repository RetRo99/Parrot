plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.kotlinxSerialization)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.server.implementation"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.serialization)
            implementation(libs.coroutines)
            implementation(libs.datetime)
            implementation(libs.kermit)
            implementation(projects.base)
            implementation(projects.lib.server.api)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.database.api)
            implementation(projects.lib.user.api)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

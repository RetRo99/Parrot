plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.base"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.datetime)
            implementation(libs.coroutines)
            api(libs.kotlin.result)
            api(libs.kotlin.result.coroutines)
            api(libs.kermit)
            implementation(projects.translations)
            implementation(projects.lib.analytics.api)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

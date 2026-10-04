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
        namespace = "com.retro99.feature.saved.data"
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
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.datetime)
            implementation(projects.base)
            implementation(projects.lib.database.api)
            implementation(projects.feature.saved.domain)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlin.result)
            implementation(libs.coroutines.test)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
                implementation(libs.kotlin.result)
            }
        }
    }
}

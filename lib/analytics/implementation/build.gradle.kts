plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.analytics.implementation"
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
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.preferences.api)
            implementation(libs.kermit)
            implementation(libs.datetime)
            api(libs.gitlive.firebase.kotlin.crashlytics)
            api(libs.gitlive.firebase.kotlin.analytics)
            implementation(projects.base)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
        androidMain.dependencies {
            implementation(libs.firebase.crashlytics.android)
            implementation(libs.firebase.analytics.android)
            implementation(libs.firebase.common)
        }
    }
}

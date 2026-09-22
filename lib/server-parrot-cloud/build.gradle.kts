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
        namespace = "com.retro99.server.parrotcloud"
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
            implementation(libs.supabase.postgrest)
            implementation(projects.base)
            implementation(projects.lib.cloud.implementation)
            implementation(projects.lib.database.api)
            implementation(projects.lib.server.api)
            implementation(projects.lib.user.api)
            implementation(projects.feature.cloudAccount.domain)
            implementation(projects.feature.sync.domain)
            implementation(projects.feature.sync.data)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

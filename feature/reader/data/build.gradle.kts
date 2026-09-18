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
        namespace = "com.retro99.feature.reader.data"
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
            implementation(libs.filekit.core)
            api(libs.datetime)
            implementation(projects.base)
            implementation(projects.lib.network.api)
            implementation(projects.lib.network.implementation)
            implementation(projects.lib.server.api)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.preferences.implementation)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.database.api)
            implementation(projects.feature.reader.domain)
            implementation(projects.feature.books.domain)
            implementation(projects.feature.books.data)
        }

        androidMain.dependencies {
            implementation(libs.readium.shared)
            implementation(libs.readium.streamer)
            implementation(libs.readium.navigator)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

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
        namespace = "com.retro99.server.storyteller"
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
            implementation(libs.serialization)
            implementation(libs.coroutines)
            implementation(libs.datetime)
            implementation(libs.ktor.client.core)
            implementation(projects.base)
            implementation(projects.lib.cloud.implementation)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.server.api)
            implementation(projects.lib.server.implementation)
            implementation(projects.lib.network.implementation)
            implementation(projects.lib.database.api)
            implementation(projects.lib.user.api)
            implementation(projects.feature.books.domain)
            implementation(projects.feature.sync.domain)
            implementation(projects.feature.sync.data)
            implementation(projects.feature.reader.domain)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}

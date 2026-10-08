plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    androidLibrary {
        namespace = "com.retro99.server.opds"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(projects.base)
            implementation(projects.lib.server.api)
            implementation(projects.lib.opds.api)
            implementation(projects.lib.opds.implementation)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.user.api)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(projects.lib.server.implementation)
            implementation(projects.lib.user.implementation)
        }
        named("androidHostTest") { dependencies { implementation(libs.kotlin.testJunit) } }
    }
}

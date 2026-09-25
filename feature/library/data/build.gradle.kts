plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.feature.library.data"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(projects.feature.cloudAccount.domain)
            implementation(projects.base)
            implementation(projects.lib.database.api)
            implementation(projects.lib.server.api)
            implementation(projects.lib.user.api)
            implementation(projects.feature.library.domain)
            implementation(projects.feature.sync.domain)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(projects.lib.serverLocal)
            implementation(projects.lib.serverParrotCloud)
            implementation(projects.feature.cloudAccount.domain)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

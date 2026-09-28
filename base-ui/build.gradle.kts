plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.base.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources {
            enable = true
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.lib.analytics.api)
            implementation(projects.base)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.koin.compose)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            api(compose.components.resources)
            api(libs.androidx.lifecycle.viewmodelCompose)
            api(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.datetime)
            api(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            api(projects.translations)
            api(compose.materialIconsExtended)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "resources.icons"
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.feature.catalogue.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
        androidResources {
            enable = true
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coroutines)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(compose.components.resources)
            implementation(libs.serialization)
            implementation(projects.base)
            implementation(projects.baseUi)
            implementation(projects.translations)
            implementation(projects.feature.catalogue.domain)
            implementation(projects.lib.server.api)
            implementation(projects.lib.database.api)
            implementation(projects.lib.user.api)
            implementation(projects.lib.analytics.api)
            implementation(libs.kotlin.result)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(projects.lib.opds.api)
            implementation(projects.lib.opds.implementation)
        }
        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "resources.catalogue.ui"
}

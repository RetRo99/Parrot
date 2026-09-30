plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.koinCompilerPlugin)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.feature.settings.ui"
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
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coroutines)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.navigation3.ui)
            implementation(libs.navigationevent.compose)
            implementation(libs.navigation3.viewmodel)
            implementation(libs.colorpicker.compose)
            implementation(libs.filekit.compose)
            implementation(projects.base)
            implementation(projects.baseUi)
            implementation(projects.translations)
            implementation(projects.feature.settings.domain)
            implementation(projects.feature.reader.domain)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.server.api)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(projects.feature.books.domain)
        }
    }
}

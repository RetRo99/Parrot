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
        namespace = "com.retro99.feature.statistics.ui"
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
            implementation(projects.base)
            implementation(projects.baseUi)
            implementation(projects.translations)
            implementation(projects.feature.statistics.domain)
            implementation(projects.feature.reader.domain)
            implementation(projects.feature.reader.ui)
            implementation(projects.feature.books.domain)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.preferences.api)
            implementation(libs.kotlin.result)
            implementation(libs.datetime)
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
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(projects.feature.statistics.data)
                implementation(projects.lib.database.api)
                implementation(projects.lib.database.implementation)
                implementation(projects.lib.user.api)
            }
        }
    }
}

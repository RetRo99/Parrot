import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotzilla)
    alias(libs.plugins.koinCompilerPlugin)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.parrot"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            export(projects.feature.reader.ui)
            export(projects.feature.login.data)
            export(projects.feature.books.data)
            // Swift uses SmilParser and SmilClip by their plain names.
            export(projects.lib.epub.api)
            export(projects.lib.epub.implementation)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.firebase.crashlytics.android)
            implementation(libs.firebase.analytics.android)
            implementation(libs.firebase.common)
            implementation(libs.datetime)
        }
        iosMain.dependencies {
            // Use api() to allow export in framework block
            api(projects.feature.reader.ui)
            api(projects.feature.login.data)
            api(projects.feature.books.data)
            api(projects.lib.epub.api)
            api(projects.lib.epub.implementation)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.kotzilla.sdk.compose)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.navigation3.ui)
            implementation(libs.navigation3.viewmodel)
            implementation(libs.coil.network.ktor)
            implementation(projects.base)
            implementation(projects.baseUi)
            implementation(projects.lib.network.api)
            implementation(projects.lib.network.implementation)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.preferences.implementation)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.analytics.implementation)
            implementation(projects.lib.database.api)
            implementation(projects.lib.database.implementation)
            implementation(projects.lib.epub.api)
            implementation(projects.lib.epub.implementation)
            implementation(projects.lib.server.api)
            implementation(projects.lib.server.implementation)
            implementation(projects.lib.serverStoryteller)
            implementation(projects.lib.serverAudiobookshelf)
            implementation(projects.lib.serverLocal)
            implementation(projects.lib.serverParrotCloud)
            implementation(projects.lib.user.api)
            implementation(projects.lib.user.implementation)
            implementation(projects.lib.cloud.implementation)
            implementation(projects.feature.cloudAccount.domain)
            implementation(projects.feature.cloudAccount.data)
            implementation(projects.feature.cloudAccount.ui)
            implementation(projects.feature.sync.domain)
            implementation(projects.feature.sync.data)
            implementation(projects.feature.auth.domain)
            implementation(projects.feature.auth.data)
            implementation(projects.feature.login.ui)
            implementation(projects.feature.login.domain)
            implementation(projects.feature.login.data)
            implementation(projects.feature.home.ui)
            implementation(projects.feature.home.data)
            implementation(projects.feature.books.ui)
            implementation(projects.feature.books.domain)
            implementation(projects.feature.books.data)
            implementation(projects.feature.reader.ui)
            implementation(projects.feature.reader.domain)
            implementation(projects.feature.reader.data)
            implementation(projects.feature.settings.ui)
            implementation(projects.feature.settings.domain)
            implementation(projects.feature.settings.data)
            implementation(projects.feature.statistics.ui)
            implementation(projects.feature.statistics.domain)
            implementation(projects.feature.statistics.data)
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

compose.desktop {
    application {
        mainClass = "com.retro99.parrot.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.retro99.parrot"
            packageVersion = "1.0.0"
        }
    }
}

compose.resources {
    publicResClass = false
    generateResClass = always
}

kotzilla {
    versionName = "1.0.0"
    keyGeneration = io.kotzilla.gradle.ext.KotzillaKeyGeneration.COMPOSE
    composeInstrumentation = true
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

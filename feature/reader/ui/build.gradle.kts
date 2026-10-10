plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.feature.reader.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        androidResources {
            enable = true
        }

        optimization {
            consumerKeepRules.file("consumer-rules.pro")
            consumerKeepRules.publish = true
        }

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.lib.dictionary)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coroutines)
            implementation(libs.datetime)
            implementation(libs.serialization)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.navigation3.ui)
            implementation(libs.navigation3.viewmodel)
            implementation(projects.base)
            implementation(projects.baseUi)
            implementation(projects.translations)
            implementation(projects.feature.reader.domain)
            implementation(projects.feature.saved.domain)
            implementation(projects.feature.saved.ui)
            implementation(projects.lib.epub.api)
            implementation(projects.lib.epub.implementation)
            implementation(projects.feature.sync.domain)
            implementation(projects.feature.books.domain)
            implementation(projects.feature.books.ui)
            implementation(projects.feature.statistics.domain)
            implementation(projects.lib.analytics.api)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.preferences.implementation)
            implementation(libs.reorderable)
        }

        androidMain.dependencies {
            implementation("org.jsoup:jsoup:1.23.2")
            implementation(libs.readium.navigator)
            implementation(libs.readium.navigatorMedia)
            implementation(libs.readium.shared)
            implementation(libs.readium.streamer)
            implementation(libs.readium.adapterExoplayer)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.datasource)
            implementation(libs.media3.session)
            implementation(libs.coroutines.guava)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.fragment)
            implementation(libs.androidx.lifecycle.process)
            implementation(projects.feature.reader.data)
            implementation(projects.lib.server.api)
            // Prepared-chapter backup reads the cloud account's own switches
            // and attestation; the upload itself goes through books.domain.
            implementation(projects.feature.cloudAccount.domain)
            implementation(projects.lib.user.api)
            implementation(files("libs/sherpa-onnx-1.13.8.aar"))
        }

        commonTest.dependencies {
            implementation(libs.coroutines.test)
            implementation(libs.kotlin.test)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

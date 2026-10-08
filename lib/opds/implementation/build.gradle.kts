version = "1.0"

// lib/opds/implementation: OPDS 1.x/2.0 parsing, OpenSearch, URI templates and
// RFC 3986 resolution, bounded transport, and feed caching (plan §3.1).
// Depends on lib/opds/api only; no feature modules, no database, no server
// registry.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.opds.implementation"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.lib.opds.api)
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.xmlutil.serialization)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

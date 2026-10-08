// Phase 0 spike harness (docs/opds-server-implementation-plan.md §7 Phase 0).
// Purpose: demonstrate the parser/transport/URL choices and record library behavior
// on both platforms. It must never become an application dependency.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

version = "1.0"

// Phase 0 finding (docs/opds-phase0-spikes.md): Kotlin/Native simulator tests
// built by Gradle run from an executable without a bundle, so
// `src/commonTest/resources` cannot be read there. The fixture files stay in
// test resources as the authored source of truth; their embedded copies live
// in src/commonTest/kotlin/.../EmbeddedFixtures.kt (checked in) and a parity
// test proves the two representations stay identical on Android (the only
// target able to read the resources).
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.opds.phase0.spike"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.serialization)
            implementation(libs.xmlutil.serialization)
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.mock)
            }
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
            }
        }
    }
}

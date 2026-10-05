plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    androidLibrary {
        namespace = "com.retro99.packs"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api("com.squareup.okio:okio:3.16.2")
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        named("androidHostTest") { dependencies { implementation(libs.kotlin.testJunit) } }
    }
}

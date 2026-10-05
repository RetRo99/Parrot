import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    androidLibrary {
        namespace = "com.retro99.dictionary"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(projects.lib.packs)
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.sqldelight.runtime)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.runtime)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        named("androidHostTest") { dependencies { implementation(libs.kotlin.testJunit) } }
    }
}
sqldelight { databases { create("DictionaryDatabase") { packageName.set("com.retro99.dictionary") } } }

compose.resources {
    packageOfResClass = "com.retro99.dictionary.resources"
    generateResClass = always
}

// The binary is versioned with the app. Fail a build rather than ship a missing
// resource or a pack that disagrees with ENGLISH_PACK's pinned manifest.
val bundledDictionary = layout.projectDirectory.file("src/commonMain/composeResources/files/english.sqlite")
val verifyBundledDictionary by tasks.registering {
    inputs.file(bundledDictionary)
    val file = bundledDictionary.asFile
    doLast {
        check(file.length() == 21_991_424L) { "Bundled dictionary size does not match ENGLISH_PACK" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(hash == "850da5c0f3efea6d911a17908155091230e4867a437cfa61bb3d6249e1efc053") {
            "Bundled dictionary checksum does not match ENGLISH_PACK"
        }
    }
}
tasks.matching { it.name in setOf("preAndroidMainBuild", "compileKotlinIosArm64", "compileKotlinIosSimulatorArm64") }
    .configureEach { dependsOn(verifyBundledDictionary) }

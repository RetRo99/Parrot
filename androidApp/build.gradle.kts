plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.googleServices)
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val releaseStoreFile = keystoreProperties.getProperty("storeFile")?.let(rootProject::file)
val hasReleaseSigning = releaseStoreFile?.isFile == true

android {
    namespace = "com.retro99.parrot.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.retro99.parrot"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 21
        versionName = "0.4.5"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug")
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the debug key; see verifyReleaseSigning.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        isCoreLibraryDesugaringEnabled = true
    }
}

// Fails only when a release artifact is packaged, so debug builds, tests
// and IDE sync keep working without a keystore.
val verifyReleaseSigning by tasks.registering {
    val signingAvailable = hasReleaseSigning
    doLast {
        if (!signingAvailable) {
            throw GradleException(
                "Release signing is not configured: add keystore.properties " +
                    "(storeFile, storePassword, keyAlias, keyPassword) at the repo root.",
            )
        }
    }
}
tasks.matching { task -> task.name == "packageRelease" || task.name == "bundleRelease" }
    .configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.firebase.bom))
    implementation(projects.composeApp)
    implementation(projects.base)
    implementation(projects.feature.login.data)
    implementation(projects.feature.home.ui)
    implementation(projects.feature.reader.ui)
    implementation(projects.feature.sync.domain)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.koin.android)
    implementation(libs.kotzilla.sdk.compose)
    implementation(libs.datetime)
    debugImplementation(libs.compose.uiTooling)
}

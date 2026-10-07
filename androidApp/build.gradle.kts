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
val releaseSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val missingSigningKeys = releaseSigningKeys.filter { key ->
    keystoreProperties.getProperty(key).isNullOrBlank()
}
val releaseStoreFile = keystoreProperties.getProperty("storeFile")?.let(rootProject::file)
val releaseSigningProblem: String? = when {
    !keystorePropertiesFile.exists() -> "keystore.properties is missing at the repo root"
    missingSigningKeys.isNotEmpty() -> "keystore.properties lacks ${missingSigningKeys.joinToString()}"
    releaseStoreFile?.isFile != true -> "keystore file $releaseStoreFile does not exist"
    else -> null
}
val hasReleaseSigning = releaseSigningProblem == null

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
        getByName("debug") {
            // Test devices are arm64; the other ABIs' ONNX runtimes inflate debug installs
            // past what wireless adb can push. Release keeps its full ABI set.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the debug key; see the release signing gate.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        isCoreLibraryDesugaringEnabled = true
    }
}

// Runs inside the release packaging tasks themselves, so `-x` cannot skip it,
// while debug builds, tests and IDE sync keep working without a keystore.
val releasePackagingTasks = setOf(
    "packageRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
    "bundleRelease",
)
tasks.matching { task -> task.name in releasePackagingTasks }.configureEach {
    val problem = releaseSigningProblem
    doFirst {
        if (problem != null) {
            throw GradleException(
                "Release signing is not configured: $problem. Provide storeFile, " +
                    "storePassword, keyAlias and keyPassword in keystore.properties.",
            )
        }
    }
}

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
    implementation(libs.datetime)
    debugImplementation(libs.compose.uiTooling)
}

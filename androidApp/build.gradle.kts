plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.crashlytics)
    alias(libs.plugins.googleServices)
}

android {
    namespace = "com.retro99.parrot.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.retro99.parrot"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 8
        versionName = "0.0.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.firebase.bom))
    implementation(projects.composeApp)
    implementation(projects.base)
    implementation(projects.feature.home.ui)
    implementation(projects.feature.reader.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.kotzilla.sdk.compose)
    implementation(libs.datetime)
    debugImplementation(libs.compose.uiTooling)
}


plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.retro99.parrot.fixtures"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.retro99.parrot.fixtures"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }
    sourceSets["main"].assets.srcDir("../dictionary/dist/oewn-2025-1")
    // This harness is never shipped as a release app.
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.enable = false
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(projects.base)
    implementation(projects.baseUi)
    implementation(projects.feature.books.domain)
    implementation(projects.feature.books.ui)
    implementation(projects.feature.reader.ui)
    implementation(projects.feature.reader.domain)
    implementation(projects.feature.sync.domain)
    implementation(projects.feature.saved.ui)
    implementation(projects.feature.catalogue.domain)
    implementation(projects.feature.catalogue.ui)
    implementation(projects.lib.dictionary)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.coroutines)
    implementation(projects.lib.server.api)
    implementation(libs.androidx.activity.compose)
    implementation(compose.foundation)
    implementation(compose.material3)
}

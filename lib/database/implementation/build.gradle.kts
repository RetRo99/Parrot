import app.cash.sqldelight.gradle.SqlDelightTask
import app.cash.sqldelight.gradle.VerifyMigrationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.koinCompilerPlugin)
    alias(libs.plugins.sqldelight)
}

version = "1.0"

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())

    androidLibrary {
        namespace = "com.retro99.database.implementation"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.koin.core)
            api(libs.koin.annotations)
            implementation(libs.datetime)
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(projects.lib.database.api)
            implementation(projects.lib.preferences.api)
            implementation(projects.lib.user.api)
            implementation(projects.base)
            implementation(projects.lib.analytics.api)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.testJunit)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.retro99.database.implementation")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// SQLDelight 2.0.2's interface compiler validates the historical chain from an empty schema.
// Migrations 1–27 predate the baseline and are not a complete creation history. Keep them for
// version numbering, but validate supported upgrades using the snapshot verification task.
afterEvaluate {
    tasks.named<SqlDelightTask>("generateCommonMainAppDatabaseInterface") {
        verifyMigrations.set(false)
    }
    tasks.named<VerifyMigrationTask>("verifyCommonMainAppDatabaseMigration") {
        // The plugin discovers snapshots but omits them from its default task input patterns.
        include("**/*.db")
    }
}

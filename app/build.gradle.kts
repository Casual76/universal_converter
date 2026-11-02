import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* -------------------------------------------------------------------------- */
/* Conversion engine build                                                     */
/* -------------------------------------------------------------------------- */

// Il motore di conversione e' un programma a se' (GPL-2.0): i task che lo costruiscono (npm, vite,
// la cache dei formati, la divisione fra asset nell'APK e pacchetto scaricabile) stanno con lui, in
// engine/engine.gradle.kts. Qui si applicano e si leggono i tre valori che servono all'app.
apply(from = rootProject.file("engine/engine.gradle.kts"))

val engineVersion = extra["engineVersion"] as String
val engineAssetBaseUrl = extra["engineAssetBaseUrl"] as String
@Suppress("UNCHECKED_CAST")
val engineAssetsDir = extra["engineAssetsDir"] as Provider<Directory>

/* -------------------------------------------------------------------------- */
/* Android                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * Release signing, read from an uncommitted `keystore.properties` in the repo
 * root. Without it the release build is still produced, just unsigned, so a
 * checkout without the key is not broken - it simply cannot publish.
 */
val keystoreProperties: Properties? =
    rootProject.layout.projectDirectory.file("keystore.properties").asFile
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.p2r3.convert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.p2r3.convert"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "2.2.0"

        buildConfigField("String", "ENGINE_VERSION", "\"$engineVersion\"")
        buildConfigField("String", "ENGINE_ASSET_BASE_URL", "\"$engineAssetBaseUrl\"")
    }

    signingConfigs {
        keystoreProperties?.let { properties ->
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                // The store's publisher checks for a v1 (JAR) signature, which
                // AGP drops on its own once minSdk is 24 or higher.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Lets a test build sit next to the installed release version.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(engineAssetsDir)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi"
        )
    }
}

tasks.named("preBuild") { dependsOn("engineSplitAssets") }

dependencies {
    // Fluid Engine: tema, tipografia e componenti condivisi con le altre app.
    implementation(project(":engine-ui"))
    // I tipi dei tool e il ponte: il convertitore espone i suoi strumenti a PampAI/Aria.
    implementation(project(":engine-ai"))
    implementation(project(":engine-ai-bridge"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material3.window)
    implementation(libs.material.icons.extended)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.core.ktx)
    implementation(libs.webkit)
    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
}

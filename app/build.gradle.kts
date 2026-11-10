plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.cyclonedx)
}

android {
    namespace = "com.muxlume.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.muxlume.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from environment variables (CI secrets or your shell); no keys in the repo.
    // If any are missing, release builds are produced unsigned.
    val keystorePath = System.getenv("MUXLUME_KEYSTORE_PATH")
    val releaseSigning = if (
        keystorePath != null &&
        listOf("MUXLUME_KEYSTORE_PASSWORD", "MUXLUME_KEY_ALIAS", "MUXLUME_KEY_PASSWORD").all { System.getenv(it) != null }
    ) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("MUXLUME_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MUXLUME_KEY_ALIAS")
            keyPassword = System.getenv("MUXLUME_KEY_PASSWORD")
        }
    } else null

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // F-Droid and reproducible builds: no Google dependency-metadata blob in the APK/AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Security checks fail the build instead of being one warning among many.
        error += setOf(
            "AddJavascriptInterface", "SetJavaScriptEnabled", "ExportedContentProvider", "ExportedReceiver",
            "ExportedService", "UnspecifiedImmutableFlag", "MutableImplicitPendingIntent", "UnsafeIntentLaunch",
            "HardcodedDebugMode", "TrustAllX509TrustManager", "BadHostnameVerifier", "AllowAllHostnameVerifier",
            "UnsafeDynamicallyLoadedCode", "WorldReadableFiles", "WorldWriteableFiles",
            "PackagedPrivateKey", "GetInstance", "SecureRandom", "AuthLeak",
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

tasks.withType<Test>().configureEach {
    // Optional override for environments with a read-only home directory (Robolectric caches SDK jars there).
    providers.gradleProperty("testUserHome").orNull?.let { systemProperty("user.home", it) }
    // Opt-in developer renders of key screens (see VisualRender.kt).
    providers.gradleProperty("muxlume.visual").orNull?.let { systemProperty("muxlume.visual", file(it).absolutePath) }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Software bill of materials (CycloneDX) of what ships in the release APK.
// ./gradlew :app:cyclonedxDirectBom → app/build/reports/sbom/muxlume-bom.json
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "muxlume"
    componentVersion = android.defaultConfig.versionName
    includeBuildSystem = true
    jsonOutput = layout.buildDirectory.file("reports/sbom/muxlume-bom.json")
    xmlOutput.unsetConvention()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui) // SubtitleView only

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    constraints {
        // GHSA-xxph-c9ww-hj94 (high): Media3 brings Guava 33.3.1; 33.7.2 is the first fixed release.
        implementation(libs.guava) { because("Security fix for a transitive dependency") }
    }

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

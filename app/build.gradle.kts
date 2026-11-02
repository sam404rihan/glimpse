import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Machine-local settings, never committed. local.properties may set glimpse.applicationId to keep an existing
// install's id (and with it its data). keystore.properties holds the release signing key (see README).
fun props(name: String) = Properties().apply { rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val local = props("local.properties")
val keystore = props("keystore.properties")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.glimpse"
    compileSdk = 36

    defaultConfig {
        applicationId = local.getProperty("glimpse.applicationId") ?: "io.github.sam404rihan.glimpse"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!keystore.isEmpty) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // x86_64 for the emulator
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without keystore.properties the release build is signed with the debug key: fine for your own phone only.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            ndk { abiFilters += "arm64-v8a" } // Galaxy A54 and every other modern phone
        }
    }

    // Models are memory-mapped straight out of the APK, so they must be stored uncompressed.
    androidResources { noCompress += "ort" }
    packaging { jniLibs { useLegacyPackaging = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.biometric:biometric:1.1.0") // fingerprint / face / PIN before deleting face data

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the real org.json: android.jar's is stubbed in unit tests
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

val modelDir = file("src/main/assets/models")
val checkModels by tasks.registering {
    doLast {
        val missing = listOf("det.ort", "rec.ort").filter { !modelDir.resolve(it).exists() }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing face models in app/src/main/assets/models: ${missing.joinToString()}.\n" +
                "Build them once with:  pip install -r tools/requirements.txt && python tools/export_models.py\n" +
                "(InsightFace models: non-commercial use only. See README.)")
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(checkModels) }

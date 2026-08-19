plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

// ---------------------------------------------------------------------------
// Release signing is configured LAZILY: debug builds, lint, and unit tests must
// work on a clean clone with no signing material. Credentials are read from
// gitignored gradle.properties (THRIVE_KEYSTORE_*) or the environment. Tasks
// that produce or install a release artifact fail closed with a clear message
// when the keystore or passwords are missing.
// ---------------------------------------------------------------------------
val keystoreFile = rootProject.file("thrive-release.keystore")
val ksStorePassword = providers.gradleProperty("THRIVE_KEYSTORE_PASSWORD").orNull
    ?: System.getenv("THRIVE_KEYSTORE_PASSWORD")
val ksKeyPassword = providers.gradleProperty("THRIVE_KEYSTORE_KEY_PASSWORD").orNull
    ?: System.getenv("THRIVE_KEYSTORE_KEY_PASSWORD")
val ksKeyAlias = providers.gradleProperty("THRIVE_KEYSTORE_KEY_ALIAS").orNull
    ?: System.getenv("THRIVE_KEYSTORE_KEY_ALIAS")
    ?: "thrive"

val hasReleaseSigning = keystoreFile.isFile && !ksStorePassword.isNullOrBlank() && !ksKeyPassword.isNullOrBlank()

android {
    namespace = "com.thrive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thrive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "1.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Google Sign-In web client ID (OAuth "Web application" client). Set it
        // in local.properties as GOOGLE_CLIENT_ID=... or as the environment
        // variable THRIVE_GOOGLE_CLIENT_ID. Empty means Google Sign-In is hidden
        // and the app keeps working with code backups / no backup.
        val googleClientId = (project.findProperty("GOOGLE_CLIENT_ID") as String?)
            ?: System.getenv("THRIVE_GOOGLE_CLIENT_ID")
            ?: ""
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${googleClientId}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Emulator-only default: the Android emulator reaches the host at
            // 10.0.2.2. Release ships NO default server — backup/sync stay
            // honestly "not configured" until the user sets an HTTPS endpoint.
            buildConfigField("String", "DEFAULT_SYNC_URL", "\"http://10.0.2.2:4000\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Phones are arm64/armeabi — release APKs don't need emulator ABIs.
            // Drops x86/x86_64: much smaller update downloads and lighter native
            // lib merging. The debug build keeps all ABIs for emulator QA.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("String", "DEFAULT_SYNC_URL", "\"\"")
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

    testOptions {
        unitTests {
            // Robolectric needs real resources/assets (bundled feeds) to test
            // the repository's cache/ETag behavior against a real Context.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Fail closed only where signing is actually required.
tasks.configureEach {
    if (name.matches(Regex("(assemble|bundle|package|install).*[Rr]elease.*"))) {
        doFirst {
            if (!hasReleaseSigning) {
                throw GradleException(
                    "Release signing is not configured.\n" +
                        "Provide THRIVE_KEYSTORE_PASSWORD / THRIVE_KEYSTORE_KEY_PASSWORD in " +
                        "local gradle.properties (gitignored) or the environment, and place " +
                        "thrive-release.keystore in the project root.\n" +
                        "Debug, lint, and unit tests do not need signing material."
                )
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Store logos are served as SVG by Wikimedia's Special:FilePath — Coil needs
    // the SVG decoder artifact to render them (without it every logo is blank).
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Google Sign-In: the app signs into a Google account and sends its ID
    // token to the Thrive backend, which verifies it and stores the user's
    // saved deals / pantry / budget under that account (no backup code needed).
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // On-device LLM inference (MediaPipe LLM Inference API) — runs a small
    // Qwen2.5 model fully on the phone, no API keys, no internet needed once
    // the model file is downloaded. Pantry recipe generation uses it when the
    // model is present and falls back to the deterministic engine otherwise.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // tasks-genai is compiled against protobuf-javalite but does not resolve it
    // transitively; R8 needs the real classes (Internal$ProtoNonnullApi,
    // ProtoPresenceBits) on the classpath or the release build fails.
    implementation("com.google.protobuf:protobuf-javalite:4.26.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

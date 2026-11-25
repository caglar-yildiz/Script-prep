import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.13.2"
    id("com.google.gms.google-services") version "4.4.4" apply false
}

// Ensemble rehearsal talks to Firebase. The project config comes from the Firebase console
// (see docs/ensemble-setup.md); without it the app builds and runs with the feature hidden.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// On-device neural text-to-speech (https://github.com/k2-fsa/sherpa-onnx, Apache-2.0).
// The release AAR is about 50 MB, so it is downloaded once and checked instead of committed.
val sherpaOnnxVersion = "2.0.1"
val sherpaOnnxSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile

fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaOnnxAar.exists() || sha256(sherpaOnnxAar) != sherpaOnnxSha256) {
    sherpaOnnxAar.parentFile.mkdirs()
    val partial = File(sherpaOnnxAar.path + ".part")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    check(sha256(partial) == sherpaOnnxSha256) { "Checksum mismatch for $url" }
    partial.renameTo(sherpaOnnxAar)
}

android {
    namespace = "com.scriptprep.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        // The store identity; the Java package keeps its original name.
        applicationId = "com.memoeverything.app"
        // Google Play automatic protection requires Android 7.0 (API 24) or newer.
        minSdk = 24
        targetSdk = 36
        // Release builds get these from the release workflow so every upload counts up.
        versionCode = (System.getenv("VERSION_CODE") ?: "2").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.3.0"
        ndk {
            // Phones (arm) and the common emulator image; 32-bit x86 is left out to save space.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Release signing uses the upload key from the environment (see .github/workflows/release.yml).
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        // A fixed debug key, so every CI debug APK installs as an update and keeps the user's
        // texts. It only signs test builds; store releases use the upload key below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    bundle {
        language {
            // The app switches its own language, so every translation must be installed.
            enableSplit = false
        }
    }

    packaging {
        jniLibs {
            // Compressed native libraries keep the download small.
            useLegacyPackaging = true
            // The Java bindings only need the JNI library and ONNX Runtime.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Only needed for certificate-encrypted PDFs, which are not supported; it also ships
        // trust-all TLS helpers that Google Play's security scan flags.
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
    }
    implementation(files(sherpaOnnxAar))
    // The sherpa-onnx bindings are written in Kotlin.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("com.android.billingclient:billing:9.1.0")
    // Ensemble rehearsal: shared texts and recordings (Firestore) behind Google sign-in.
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    // On-device text recognition for scanned PDF pages. The bundled model works offline from the
    // first launch and needs no Google Play services download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // FileProvider, so the camera app can save a page photo for text recognition.
    implementation("androidx.core:core:1.13.1")

    // Plain JVM tests for the text parsers (./gradlew :app:testDebugUnitTest).
    testImplementation("junit:junit:4.13.2")
}

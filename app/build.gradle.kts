import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.13.2"
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
        minSdk = 23
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
}

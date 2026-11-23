import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // Release signing comes from environment variables (set by the release workflow from repository
    // secrets). Without them, release builds are unsigned and only debug builds can be installed.
    val keystore = System.getenv("JARVIS_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    // Lists the app's languages so Android 13+ offers Jarvis under Settings > Apps > Language.
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            if (project.hasProperty("real")) {
                // ./gradlew testDebugUnitTest -Preal : run the app's voice code on the real sherpa-onnx
                // engine (Linux build) with real models. See test-native/README.md.
                val native = rootProject.file("test-native")
                val lib = native.resolve("sherpa-onnx-v1.13.8-linux-x64-jni/lib").absolutePath
                it.systemProperty("jarvis.nativeDir", native.absolutePath)
                it.environment("LD_LIBRARY_PATH", lib)
                it.systemProperty("java.library.path", lib)
                it.filter.includeTestsMatching("*RealEngineTest*")
            } else {
                // The normal suite simulates phones where the native engine can't load at all.
                it.exclude("**/RealEngineTest*")
            }
        }
    }
    packaging {
        jniLibs {
            excludes += "**/x86/*.so" // The speech AAR also contains 32-bit x86.
            useLegacyPackaging = true // compress native libraries: much smaller APK to sideload
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/versions/**",
            )
        }
    }
}

val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

val fetchSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx Android library from its GitHub releases."
    outputs.file(sherpaAar)
    onlyIf { !sherpaAar.exists() }
    doLast {
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        logger.lifecycle("Downloading $url")
        val part = File(sherpaAar.path + ".part")
        sherpaAar.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != sherpaSha256) {
            part.delete()
            throw GradleException("sherpa-onnx download is corrupt (sha256 $digest)")
        }
        part.renameTo(sherpaAar)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Claude (official Anthropic Java SDK)
    implementation("com.anthropic:anthropic-java:2.34.0")
    // Kokoro voice, "Hey Jarvis" and offline speech recognition: the official sherpa-onnx Android build,
    // downloaded on the first build (too big to keep in git).
    implementation(files(sherpaAar).builtBy(fetchSherpaOnnx))
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

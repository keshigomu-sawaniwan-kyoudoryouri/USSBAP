import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.jin.bitperfect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jin.bitperfect"
        minSdk = 34
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val releaseStore = keystoreProperties.getProperty("storeFile")
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else {
                // Local fallback for reproducible debug builds. Never use this
                // key for a published release.
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // A different suffix can be supplied for side-by-side device QA when
            // an older debug signing key is unavailable. Normal builds remain
            // com.jin.bitperfect.debug and preserve the established workflow.
            applicationIdSuffix = providers.gradleProperty("qaApplicationIdSuffix").orNull ?: ".debug"
            resValue("string", "app_name", "Debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // HTP DSP must mmap its architecture-specific Skel library from a real
        // filesystem path; it cannot load that binary directly from base.apk.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.cardview:cardview:1.0.0")
    
    // NewPipe Extractor (pure Java YT/DASH audio extraction)
    implementation("com.github.teamnewpipe:newpipeextractor:v0.26.4")
    
    // OkHttp (NewPipe downloader backend)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Media3 (ExoPlayer)
    val media3Version = "1.9.3"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation(files("libs/decent-usb-audio-driver-release.aar"))
    implementation(files("libs/decent-usb-audio-wrapper-media3-release.aar"))
    // Qualcomm QNN build: HTP (Hexagon NPU) and Adreno GPU execution providers.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")

    // FFmpeg Kit (Maintained version - Full bundle)
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
    
    // Explicitly add missing transitive dependencies for FFmpeg Kit
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("com.arthenica:smart-exception-common:0.2.1")

    // Encrypted SharedPreferences for cookie storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // yt-dlp for Android (embedded Python + yt-dlp)
    implementation("dev.ffmpegkit-maintained:yt-dlp-android:2.0.2")

    testImplementation("junit:junit:4.13.2")
}

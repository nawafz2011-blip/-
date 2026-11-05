plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// رقم النسخة ييجي من GitHub Actions (رقم البناء) عشان التحديث يشتغل
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.nawaf.mohammel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nawaf.mohammel"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("mohammel.jks")
            storePassword = "mohammel"
            keyAlias = "mohammel"
            keyPassword = "mohammel"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }


    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // مكتبة yt-dlp تحتاج المكتبات الأصلية مفكوكة
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil:2.7.0")
}

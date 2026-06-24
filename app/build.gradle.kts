plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bootstudio"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bootstudio"
        compileSdk = 35
        minSdk = 26
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    androidResources {
        localeFilters += "en"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.kotlin_module"
        }
        jniLibs {
            // Exclude all FFmpeg-related native libraries from the APK
            // since they are downloaded at runtime.
            excludes += "**/libav*.so"
            excludes += "**/libsw*.so"
            excludes += "**/libffmpeg*.so"
            excludes += "**/libx264.so"
            excludes += "**/libx265.so"
            excludes += "**/libvpx.so"
            excludes += "**/libwebp.so"
            excludes += "**/libopus.so"
            excludes += "**/libvorbis*.so"
            excludes += "**/libtheora.so"
            excludes += "**/libmp3lame.so"
            excludes += "**/libass.so"
            excludes += "**/libfreetype.so"
            excludes += "**/libfribidi.so"
            excludes += "**/libfontconfig.so"
            excludes += "**/libgnutls.so"
            excludes += "**/libgmp.so"
            excludes += "**/libiconv.so"
            excludes += "**/libxml2.so"
            excludes += "**/libopencore-amr*.so"
            excludes += "**/libshine.so"
            excludes += "**/libsnappy.so"
            excludes += "**/libsoxr.so"
            excludes += "**/libspeex.so"
            excludes += "**/libvidstab.so"
            excludes += "**/libzimg.so"
            excludes += "**/libsrt.so"
            excludes += "**/libtesseract.so"
            excludes += "**/librubberband.so"
            excludes += "**/libopenh264.so"
            excludes += "**/libchromaprint.so"
            excludes += "**/libkvazaar.so"
            excludes += "**/libdav1d.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.ffmpeg.kit)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "pro.darc.viruaisor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = providers.gradleProperty("viruaisor.applicationId").orElse("pro.darc.viruaisor").get()
        minSdk = 37
        targetSdk = 37
        versionCode = providers.gradleProperty("viruaisor.versionCode").map(String::toInt).orElse(1).get()
        versionName = providers.gradleProperty("viruaisor.versionName").orElse("1.0").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true // Extract the bounded native Venus worker into the executable library directory.
        jniLibs.keepDebugSymbols += "**/libvenus_crosvm.so" // Preserve the reviewed executable SHA-256.
        jniLibs.keepDebugSymbols += "**/libvenus_desktop_crosvm.so"
        jniLibs.keepDebugSymbols += "**/librootfs_e2fsck.so"
        jniLibs.keepDebugSymbols += "**/librootfs_resize2fs.so"
        jniLibs.keepDebugSymbols += "**/librootfs_debugfs.so"
        jniLibs.keepDebugSymbols += "**/librootfs_copy.so"
    }
}

dependencies {
    implementation(libs.libsu.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

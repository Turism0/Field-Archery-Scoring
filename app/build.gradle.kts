plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "uk.co.fnurble.fieldarchery"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.fnurble.fieldarchery"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "2.4.01"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Kotlin is built into AGP 9+ (no kotlin-android plugin); its JVM target follows compileOptions.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    // Room schema history, used to write migrations when the database changes
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    // OpenStreetMap map of shoot locations (no API key needed)
    implementation(libs.osmdroid.android)
    // Live groups: draw the join QR code (no permissions) and read one with Google's scanner (no camera permission)
    implementation(libs.zxing.core)
    implementation(libs.play.services.code.scanner)
    // Live groups with no signal: phone to phone over Bluetooth / Wi-Fi (2.3).
    implementation(libs.play.services.nearby)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

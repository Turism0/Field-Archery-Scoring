plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "uk.co.fieldarcheryscoring"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.fieldarcheryscoring"
        minSdk = 26
        targetSdk = 36
        versionCode = 90
        versionName = "3.2.28"
    }

    buildTypes {
        release {
            // 3.2.13 (Play Console: DEX code optimisation): R8 shrinks, optimises and obfuscates the release build. Crash
            // reports stay readable: the bundle carries the mapping file, which Play Console uses to translate them.
            // Rules for this app's own code are in proguard-rules.pro; the libraries bring their own.
            isMinifyEnabled = true
            isShrinkResources = true
            // 3.2.11: the libraries' native debug symbols go in the bundle, for readable crash reports in Play Console.
            ndk { debugSymbolLevel = "FULL" }
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
    // 3.2.13: a current fragment library in place of the old 1.1.0 that Google Play services bring in.
    implementation(libs.androidx.fragment)
    // 3.2.15: asks Google Play whether a newer version is available (the "Update available" notice on Home).
    implementation(libs.play.app.update.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

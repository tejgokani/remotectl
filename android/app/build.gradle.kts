plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.remotectl.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.remotectl.app"
        // API 30+ so BiometricPrompt can offer "biometric or device PIN" on every supported phone.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Supplied by CI or a local keystore.properties-style environment; never committed.
            val path = System.getenv("REMOTECTL_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("REMOTECTL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REMOTECTL_KEY_ALIAS")
                keyPassword = System.getenv("REMOTECTL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("REMOTECTL_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

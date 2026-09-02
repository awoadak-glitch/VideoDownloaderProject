plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.awr.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awr.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.2.0-ultra"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation("network.mysterium.openvpn:icsopenvpn:0.7.55-myst")
    implementation("androidx.core:core-ktx:1.13.1")
}

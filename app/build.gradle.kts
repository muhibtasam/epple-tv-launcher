plugins {
    id("com.android.application")
}

android {
    namespace = "com.eppletv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eppletv.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // base: no Google dependencies. google: may use Google TV / Assistant capabilities (added behind interfaces).
    flavorDimensions += "services"
    productFlavors {
        create("base") { dimension = "services" }
        create("google") { dimension = "services" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Private sideload build for the owner's TV. Replace with a private release key before any wider use.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

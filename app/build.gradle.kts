import java.util.Properties

plugins {
    id("com.android.application")
}

val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    // JVM unit tests: android.util.Log and other framework stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "com.eppletv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eppletv.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Optional content keys; without them those providers fall back gracefully. Never committed (local.properties is ignored).
        fun prop(vararg names: String) = names.firstNotNullOfOrNull { local.getProperty(it)?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: ""
        buildConfigField("String", "TMDB_KEY", "\"${prop("tmdb_key", "tmdb.key")}\"")
        buildConfigField("String", "WATCHMODE_KEY", "\"${prop("watchmode_key", "watchmode.key")}\"")
        buildConfigField("String", "TRAKT_ID", "\"${prop("trakt_client_id", "trakt.client_id")}\"")
        buildConfigField("String", "YOUTUBE_KEY", "\"${prop("youtube_key", "youtube.key")}\"")
        buildConfigField("String", "CONTENT_REGION", "\"${prop("content_region", "content.region")}\"")
    }

    // base: no Google dependencies. google: may use Google TV / Assistant capabilities (added behind interfaces).
    flavorDimensions += "services"
    productFlavors {
        create("base") { dimension = "services" }
        create("google") { dimension = "services" }
    }

    // The project's own wallpapers/ folder (not in git), resized by scripts/build.sh, is packed in as assets/wallpapers.
    sourceSets["main"].assets.srcDir("build/bundled-wallpapers")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // v1.0.0 is signed with the debug key; later updates must use the same key (or users reinstall).
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

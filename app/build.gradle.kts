import java.util.Properties

plugins {
    id("com.android.application")
}

val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

// Release signing: Epple TV's permanent release key (the certificate v1.0.0 shipped with; SHA-256 2f8a1837…5afa2b).
// Never in git: the values come from the environment or ~/.gradle/gradle.properties (scripts/build.sh reads the
// passwords from the macOS Keychain). A release build without all four stops; it never falls back to the debug key.
val releaseSigning = listOf("EPPLE_RELEASE_STORE_FILE", "EPPLE_RELEASE_STORE_PASSWORD", "EPPLE_RELEASE_KEY_ALIAS", "EPPLE_RELEASE_KEY_PASSWORD")
    .associateWith { (System.getenv(it) ?: providers.gradleProperty(it).orNull)?.takeIf { v -> v.isNotBlank() } }
val missingSigning = releaseSigning.filterValues { it == null }.keys.toList()

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
    // Public releases (scripts/build.sh --public) pass -PeppleBundleWallpapers=false: personal pictures are never packed.
    if (providers.gradleProperty("eppleBundleWallpapers").orNull != "false") sourceSets["main"].assets.srcDir("build/bundled-wallpapers")

    signingConfigs {
        create("release") {
            if (missingSigning.isEmpty()) {
                storeFile = file(releaseSigning.getValue("EPPLE_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigning.getValue("EPPLE_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigning.getValue("EPPLE_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigning.getValue("EPPLE_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        // Debug builds: their own app (com.eppletv.launcher.debug, "Epple TV (Debug)"), signed with the standard debug
        // config, so they install beside the public app and can never replace it.
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

// Release packaging stops when the signing values are missing (rather than producing an unsigned APK).
tasks.configureEach {
    if ((name.startsWith("package") || name.startsWith("validateSigning")) && name.endsWith("Release")) {
        val missing = missingSigning
        doFirst { if (missing.isNotEmpty()) throw GradleException("Release signing is not configured: set ${missing.joinToString()} (scripts/build.sh reads them from the Keychain).") }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

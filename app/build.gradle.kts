import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Release signing. Keystore + passwords live OUTSIDE the repository.
 * Path of a properties file (storeFile, storePassword, keyAlias, keyPassword):
 *   -PgiltuneSigning=/abs/path/keystore.properties  or env GILTUNE_SIGNING_PROPERTIES,
 *   default ../../keys/keystore.properties (relative to the project root).
 * If absent, release is built unsigned.
 */
val signingPropsFile: File = (providers.gradleProperty("giltuneSigning").orNull
    ?: System.getenv("GILTUNE_SIGNING_PROPERTIES"))?.let { file(it) }
    ?: rootProject.file("../../keys/keystore.properties")
// Client ID: ../../spotify/client_id.env (SPOTIFY_CLIENT_ID=...), outside the repo.
val spotifyEnv = rootProject.file("../../spotify/client_id.env")
val spotifyClientId = (if (spotifyEnv.isFile) spotifyEnv.readLines()
        .firstOrNull { it.trim().startsWith("SPOTIFY_CLIENT_ID=") }?.substringAfter("=")?.trim()?.trim('"') else null)
    ?: ""
val signingProps = Properties().apply { if (signingPropsFile.isFile) signingPropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.sanora.giltune"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sanora.giltune"
        minSdk = 28
        targetSdk = 34
        versionCode = 9
        versionName = "6.1-contest"
    }
    buildFeatures { buildConfig = true }
    defaultConfig {
        // Spotify: empty client id => disabled, local MP3 fallback
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        // Spotify auth-lib redirect activity: giltune://spotify-callback
        manifestPlaceholders["redirectSchemeName"] = "giltune"
        manifestPlaceholders["redirectHostName"] = "spotify-callback"
    }

    // demo: plain Android, no Pleos SDK. pleos: official Pleos Playground SDKs, direct calls.
    flavorDimensions += "sdk"
    productFlavors {
        create("demo") {
            dimension = "sdk"
            buildConfigField("boolean", "PLEOS_SDK_INCLUDED", "false")
        }
        create("pleos") {
            dimension = "sdk"
            isDefault = true
            buildConfigField("boolean", "PLEOS_SDK_INCLUDED", "true")
        }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    // Spotify App Remote SDK 0.8.0, official aar from github.com/spotify/android-sdk releases (+ Gson per docs)
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1")
    // Spotify Android auth-lib (Maven Central), token for Web API search
    implementation("com.spotify.android:auth:2.1.2")
    implementation("androidx.core:core:1.13.1") // FileProvider for sharing drive-log CSV
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests of the rules parser
    // https://document.pleos.ai/api-reference/connect-sdk-pleos/FusedLocation/project-setup
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk
    // https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
}

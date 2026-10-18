import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from a gitignored keystore.properties in the repo root (storeFile, keyAlias,
// storePassword, keyPassword). Without it the release APK still builds, just unsigned.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use { load(it) } }
val hasReleaseKey = listOf("storeFile", "keyAlias", "storePassword", "keyPassword").all { keystoreProps.getProperty(it) != null }
if (!hasReleaseKey) logger.lifecycle("MicroDeck: no keystore.properties, the release APK will be unsigned")

// The update check asks GitHub for the latest MicroDeck release. Only a test build overrides that
// with -PmicrodeckUpdateUrl=<url> (a local fake server); a normal build always uses GitHub. A test
// build pointing at plain http also gets cleartext traffic, which a normal build never has.
val realUpdateUrl = "https://api.github.com/repos/Crazy8697/MicroDeck/releases/latest"
val updateUrlOverride = providers.gradleProperty("microdeckUpdateUrl").orNull
if (updateUrlOverride != null) {
    require(Regex("""^https?://[A-Za-z0-9.:/_-]+$""").matches(updateUrlOverride)) { "microdeckUpdateUrl: not a plain URL" }
    logger.lifecycle("MicroDeck: TEST BUILD, update check goes to $updateUrlOverride")
}
val updateUrl = updateUrlOverride ?: realUpdateUrl

android {
    namespace = "net.darkrelay.microdeck"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.darkrelay.microdeck"
        minSdk = 33
        targetSdk = 36
        versionCode = 11
        versionName = "0.13.0"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        manifestPlaceholders["cleartext"] = updateUrl.startsWith("http://").toString()
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v2 for every supported Android version, v3 so the key can be rotated later
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
            // no META-INF/version-control-info.textproto (it carries the private commit hash)
            vcsInfo {
                include = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // test only, never packaged into the app
    testImplementation("junit:junit:4.13.2")
    // the real org.json for UpdateCheckTest (the one in android.jar is a stub off-device)
    testImplementation("org.json:json:20260814")
}

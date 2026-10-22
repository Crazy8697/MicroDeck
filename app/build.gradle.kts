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

// The update check asks GitHub for the latest MicroDeck release and downloads from its tag path. A
// release build always does. Only a DEBUG build can be pointed at a local fake server, with
// -PmicrodeckTestServer=http://host:port (it then also gets cleartext traffic), and only a debug build
// can be signed with the release key, with -PmicrodeckDebugReleaseKey=true, to test in-app updates.
val realUpdateUrl = "https://api.github.com/repos/Crazy8697/MicroDeck/releases/latest"
val realDownloadBase = "https://github.com/Crazy8697/MicroDeck/releases/download"
val testServer = providers.gradleProperty("microdeckTestServer").orNull
if (testServer != null) {
    require(Regex("""^https?://[A-Za-z0-9.:_-]+$""").matches(testServer)) { "microdeckTestServer: not a plain http(s)://host:port" }
    logger.lifecycle("MicroDeck: debug builds check $testServer for updates")
}
val debugReleaseKey = providers.gradleProperty("microdeckDebugReleaseKey").orNull == "true"

android {
    namespace = "net.darkrelay.microdeck"
    compileSdk = 36
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.darkrelay.microdeck"
        minSdk = 33
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0"
        buildConfigField("String", "UPDATE_URL", "\"$realUpdateUrl\"")
        buildConfigField("String", "DOWNLOAD_BASE", "\"$realDownloadBase\"")
        manifestPlaceholders["cleartext"] = "false"
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
        debug {
            if (testServer != null) {
                buildConfigField("String", "UPDATE_URL", "\"$testServer/repos/Crazy8697/MicroDeck/releases/latest\"")
                buildConfigField("String", "DOWNLOAD_BASE", "\"$testServer/releases/download\"")
                manifestPlaceholders["cleartext"] = testServer.startsWith("http://").toString()
            }
            if (debugReleaseKey) signingConfig = signingConfigs.findByName("release")
        }
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

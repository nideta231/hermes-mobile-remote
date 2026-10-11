import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release key lives outside the repo: keystore.properties locally (gitignored), environment
// variables in CI (see .github/workflows/release.yml).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    System.getenv("ANDROID_KEYSTORE_FILE")?.let { file ->
        setProperty("storeFile", file)
        setProperty("storePassword", System.getenv("ANDROID_KEYSTORE_PASSWORD"))
        setProperty("keyAlias", System.getenv("ANDROID_KEY_ALIAS"))
        setProperty("keyPassword", System.getenv("ANDROID_KEY_PASSWORD") ?: System.getenv("ANDROID_KEYSTORE_PASSWORD"))
    }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// Release builds take their version from the git tag (v1.0.0 -> 1.0.0, code 10000).
val appVersionName = System.getenv("APP_VERSION")?.removePrefix("v") ?: "1.0.0"
val appVersionCode = appVersionName.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "io.github.nideta231.hermesremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nideta231.hermesremote"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json", "/META-INF/*.kotlin_module")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// CHANGELOG.md ships inside the APK: the app shows it offline ("Changelog" in Settings) and, after an
// update, the part between the old and the new version.
val changelogAssets = layout.buildDirectory.dir("generated/changelogAssets")
val copyChangelog by tasks.registering(Copy::class) {
    from(rootProject.file("../CHANGELOG.md"))
    into(changelogAssets)
}
android.sourceSets["main"].assets.srcDir(changelogAssets)
tasks.named("preBuild") { dependsOn(copyChangelog) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

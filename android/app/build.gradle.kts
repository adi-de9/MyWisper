import java.util.Properties

// The MyWisper server (functions/ in the repo): Groq + each user's sync key. Same as desktop firebase-config.js apiBase.
val apiBase = "https://asia-south1-mywisper-c041c.cloudfunctions.net/api"

plugins {
    id("com.android.application")
    id("com.google.gms.google-services") // reads app/google-services.json (Firebase project)
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.mywisper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mywisper.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.0"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
        manifestPlaceholders["crashlytics"] = "false"
    }

    buildFeatures {
        buildConfig = true
    }

    // Release signing comes from android/keystore.properties + release.jks (both kept out of git).
    val keystore = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystore.exists()) create("release") {
            val p = Properties().apply { keystore.inputStream().use { load(it) } }
            storeFile = rootProject.file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Release-signed but debuggable, with the test hooks (fake mic, test tap): installs over the real app for USB tests.
        create("qa") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.findByName("release")
            matchingFallbacks += listOf("debug")
        }
        release {
            // R8 shrinks the APK and obfuscates the code.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["crashlytics"] = "true" // crash reports only from real (release) installs
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    // Google sign-in + sync (same Firebase project as the Windows app)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (android.jar only has stubs)
}

// The cleanup prompts come from the desktop app (../src/main/prompts.js), so both stay in sync.
val exportPrompts by tasks.registering(Exec::class) {
    val repo = rootProject.projectDir.parentFile
    workingDir = repo
    commandLine(
        if (System.getProperty("os.name").lowercase().contains("windows")) listOf("cmd", "/c", "node", "scripts/export-mobile-prompts.js")
        else listOf("node", "scripts/export-mobile-prompts.js")
    )
    inputs.file(File(repo, "src/main/prompts.js"))
    inputs.file(File(repo, "src/main/groq.js"))
    outputs.file(file("src/main/assets/prompts.json"))
}
tasks.named("preBuild") { dependsOn(exportPrompts) }

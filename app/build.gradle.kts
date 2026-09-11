import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// Web client ID for Google Sign-In via Credential Manager.
// Put WEB_CLIENT_ID=xxxxx.apps.googleusercontent.com in local.properties (git-ignored).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val webClientId: String = localProps.getProperty("WEB_CLIENT_ID") ?: ""

// Image bytes live in a Supabase Storage bucket (Firebase Storage needs the Blaze plan).
// SUPABASE_URL and SUPABASE_ANON_KEY come from Supabase -> Project Settings -> API.
// The anon key is a public identifier, not a secret; access is gated by the user's
// Firebase ID token and the bucket policies in supabase/storage-policies.sql.
val supabaseUrl: String = localProps.getProperty("SUPABASE_URL") ?: ""
val supabaseAnonKey: String = localProps.getProperty("SUPABASE_ANON_KEY") ?: ""
val supabaseBucket: String = localProps.getProperty("SUPABASE_BUCKET") ?: "roll"

// Point a debug build at a local Firebase Emulator Suite instead of a real project.
// Set USE_FIREBASE_EMULATOR=true in local.properties to try the app end to end
// without creating a Firebase project at all.
val useFirebaseEmulator: Boolean =
    localProps.getProperty("USE_FIREBASE_EMULATOR")?.toBoolean() ?: false
val emulatorHost: String =
    localProps.getProperty("FIREBASE_EMULATOR_HOST") ?: "10.0.2.2"

android {
    namespace = "com.rollapp.shared"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rollapp.shared"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "INVITE_HOST", "\"roll.page.link\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "SUPABASE_BUCKET", "\"$supabaseBucket\"")
    }

    // Release signing comes from local.properties (git-ignored). Without it the release
    // build still assembles, unsigned, which is what CI and a fresh clone get.
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }
    if (releaseStore != null && releaseStore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ""
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "$useFirebaseEmulator")
            buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
        }
        release {
            // A release build never talks to an emulator, whatever local.properties says.
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.concurrent.futures.ktx)
    implementation(libs.guava)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.auth)
    implementation(libs.googleid)

    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

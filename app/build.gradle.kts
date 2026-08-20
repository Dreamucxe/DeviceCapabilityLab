import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.devicelab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devicelab"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.devicelab.HiltTestRunner"

        // Room exports its schema so snapshot-history migrations can be tested
        // against the real historical schema rather than an approximation.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    // Optional release signing: reads `keystore.properties` when present so that
    // `assembleRelease` emits a signed, directly installable APK.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val hasKeystore = keystorePropsFile.exists()
    val keystoreProps = Properties().apply {
        if (hasKeystore) keystorePropsFile.inputStream().use { load(it) }
    }
    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 stays off: full minification is memory-hungry on an on-device ARM
            // build host with ~1.7 GB free. Keep-rules are retained so it can be
            // switched on when building on a workstation.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    lint {
        // Lint runs as its own Gradle invocation on this build host rather than as part
        // of `assembleRelease`. It is not a saving of work: the same checks run, in a JVM
        // of their own. With the Kotlin compiler in-process and no daemon, one invocation
        // that compiles a variant *and* runs lint's full analysis exhausts metaspace.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.window)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.biometric)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Local JVM tests. Everything under src/test targets the Android-free seams --
    // the capability model, the status roll-up, the diff engine, the export
    // renderers, the search index -- so no framework double is needed and these
    // run on any JDK without a device.
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}

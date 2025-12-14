import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("androidx.navigation.safeargs.kotlin")
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// Release signing secrets are expected from local.properties or env vars
val releaseStoreFile = localProperties.getProperty("release.storeFile") ?: System.getenv("RP_RELEASE_STORE_FILE")
val releaseStorePassword = localProperties.getProperty("release.storePassword") ?: System.getenv("RP_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProperties.getProperty("release.keyAlias") ?: System.getenv("RP_RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProperties.getProperty("release.keyPassword") ?: System.getenv("RP_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

fun readSentryDsn(propertyKey: String, envKey: String): String =
    (localProperties.getProperty(propertyKey) ?: System.getenv(envKey) ?: "").trim()

val sentryDsnDev = readSentryDsn("sentry.dsn.dev", "SENTRY_DSN_DEV")
val sentryDsnStaging = readSentryDsn("sentry.dsn.staging", "SENTRY_DSN_STAGING")
val sentryDsnProd = readSentryDsn("sentry.dsn.prod", "SENTRY_DSN_PROD")

android {
    namespace = "com.example.rocketplan_android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.rocketplan_android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("maps.api.key", "")
    }

    // Product Flavors for different environments and device types
    flavorDimensions += listOf("environment", "device")
    productFlavors {
        // Environment flavors
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            // Dev environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-qa-mongoose-br2wu78v1.rocketplantech.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"DEV\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")
            buildConfigField("Boolean", "ENABLE_ROCKET_DRY", "true")
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsnDev\"")
            buildConfigField("Boolean", "SENTRY_ENABLED", "${sentryDsnDev.isNotBlank()}")

            // Custom resources for dev
            resValue("string", "app_name", "RocketPlan Dev")
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"

            // Staging/Test environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-staging-mongoose-n5tr2spgf.rocketplantech.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"STAGING\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")
            buildConfigField("Boolean", "ENABLE_ROCKET_DRY", "true")
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsnStaging\"")
            buildConfigField("Boolean", "SENTRY_ENABLED", "${sentryDsnStaging.isNotBlank()}")

            // Custom resources for staging
            resValue("string", "app_name", "RocketPlan Staging")
        }

        create("prod") {
            dimension = "environment"

            // Production environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-public.rocketplantech.com/\"")
            buildConfigField("String", "ENVIRONMENT", "\"PROD\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "false")
            buildConfigField("Boolean", "ENABLE_ROCKET_DRY", "true")
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsnProd\"")
            buildConfigField("Boolean", "SENTRY_ENABLED", "${sentryDsnProd.isNotBlank()}")

            // Custom resources for production
            resValue("string", "app_name", "RocketPlan")
        }

        // Device flavors
        create("flir") {
            dimension = "device"
            // FLIR devices have thermal camera support
            buildConfigField("Boolean", "HAS_FLIR_SUPPORT", "true")
            ndk {
                abiFilters += "arm64-v8a"
            }
        }

        create("standard") {
            dimension = "device"
            // Standard Android devices without thermal camera
            buildConfigField("Boolean", "HAS_FLIR_SUPPORT", "false")
            ndk {
                // Include x86_64 for emulator support, ARM for physical devices
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (!hasReleaseSigning) {
                logger.warn("Release signing credentials not configured; falling back to debug signing for release builds.")
            } else {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true  // Enable BuildConfig generation
    }

    packaging {
        jniLibs {
            // Keep legacy packaging so the FLIR native binaries load correctly
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
        resources {
            // Exclude duplicate SLF4J files bundled in FLIR SDK
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "META-INF/DEPENDENCIES"
        }
    }

    splits {
        abi {
            // ABI filtering is now handled via product flavors (flir vs standard)
            isEnable = false
        }
    }
}

dependencies {
    // Core library desugaring for java.time API support on Android 7+
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp.logging)

    // Crash reporting
    implementation(libs.sentry.android)
    // Pusher for standard builds (includes SLF4J)
    "standardImplementation"(libs.pusher)
    "standardImplementation"("org.slf4j:slf4j-android:1.7.36")
    // Pusher for FLIR builds - exclude SLF4J since FLIR SDK bundles its own
    "flirImplementation"(libs.pusher) {
        exclude(group = "org.slf4j")
    }

    // DataStore for secure storage
    implementation(libs.androidx.datastore.preferences)

    // Security/Encryption
    implementation(libs.androidx.security.crypto)

    // Chrome Custom Tabs for OAuth flow
    implementation(libs.androidx.browser)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)

    // ViewPager2 and SwipeRefreshLayout for projects list
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.coil)
    implementation(libs.androidx.paging.runtime)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // FLIR Atlas Android SDK (local AARs; place in app/libs)
    // Only include for FLIR device flavor (arm64)
    "flirImplementation"(files("libs/androidsdk-release.aar"))
    "flirImplementation"(files("libs/thermalsdk-release.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.core)
}

// Auto-start log capture after installing dev build
// NOTE: Disabled for now - run manually with ./scripts/auto-log.sh live
// tasks.register<Exec>("autoLogDev") {
//     group = "logging"
//     description = "Auto-start live log capture after dev build installs"
//
//     workingDir(rootProject.projectDir)
//     commandLine("bash", "-c", """
//         # Kill any existing log captures first
//         pkill -f 'capture-logs.sh' 2>/dev/null || true
//         sleep 1
//
//         # Start new capture in background
//         ./scripts/auto-log.sh live > /dev/null 2>&1 &
//         echo "🎬 Log capture started in background"
//     """.trimIndent())
//
//     isIgnoreExitValue = true
// }
//
// // Hook into dev install tasks
// tasks.whenTaskAdded {
//     if (name == "installDevDebug" || name == "installDevRelease") {
//         finalizedBy("autoLogDev")
//     }
// }

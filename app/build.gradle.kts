plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("androidx.navigation.safeargs.kotlin")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.rocketplan_android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.rocketplan_android"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Product Flavors for different environments
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"

            // Dev environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-qa-mongoose-br2wu78v1.rocketplantech.com\"")
            buildConfigField("String", "ENVIRONMENT", "\"DEV\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")

            // Custom resources for dev
            resValue("string", "app_name", "RocketPlan Dev")
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"

            // Staging/Test environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-staging-mongoose-n5tr2spgf.rocketplantech.com\"")
            buildConfigField("String", "ENVIRONMENT", "\"STAGING\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")

            // Custom resources for staging
            resValue("string", "app_name", "RocketPlan Staging")
        }

        create("prod") {
            dimension = "environment"

            // Production environment BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-public.rocketplantech.com\"")
            buildConfigField("String", "ENVIRONMENT", "\"PROD\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "false")

            // Custom resources for production
            resValue("string", "app_name", "RocketPlan")
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

            // Signing config for release builds
            // signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true  // Enable BuildConfig generation
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

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.okhttp.logging)

    // DataStore for secure storage
    implementation(libs.androidx.datastore.preferences)

    // Biometric authentication
    implementation(libs.androidx.biometric)

    // Security/Encryption
    implementation(libs.androidx.security.crypto)

    // Chrome Custom Tabs for OAuth flow
    implementation(libs.androidx.browser)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    kapt(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)

    // ViewPager2 and SwipeRefreshLayout for projects list
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.coil)
    implementation(libs.androidx.paging.runtime)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")

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

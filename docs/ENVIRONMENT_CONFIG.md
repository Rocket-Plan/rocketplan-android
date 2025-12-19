# Environment Configuration Guide

This document explains how to manage different environments (Dev, Staging, Production) in the RocketPlan Android app.

## Overview

The app uses **Android Product Flavors** to manage three environments:
- **dev** - Development environment
- **staging** - Staging/Staging environment
- **prod** - Production environment

Each flavor can be combined with build types (`debug` or `release`), creating variants like:
- `devDebug`, `devRelease`
- `stagingDebug`, `stagingRelease`
- `prodDebug`, `prodRelease`

## Environment Configuration

### Current Settings

| Setting | Dev | Staging | Prod |
|---------|-----|------|------|
| **API Base URL** | `https://api-qa-mongoose-br2wu78v1.rocketplantech.com` | `https://api-staging-mongoose-n5tr2spgf.rocketplantech.com` | `https://api-public.rocketplantech.com` |
| **Application ID** | `com.example.rocketplan_android.dev` | `com.example.rocketplan_android.staging` | `com.example.rocketplan_android` |
| **App Name** | RocketPlan Dev | RocketPlan Staging | RocketPlan |
| **Logging Enabled** | ✅ Yes | ✅ Yes | ❌ No |
| **ProGuard/R8** | ❌ Debug builds | ❌ Debug builds | ✅ Release builds |

### Accessing Configuration in Code

Use the `AppConfig` object to access environment settings:

```kotlin
import com.example.rocketplan_android.config.AppConfig

// Get API base URL
val apiUrl = AppConfig.apiBaseUrl

// Check current environment
when {
    AppConfig.isDevelopment -> println("Running in DEV")
    AppConfig.isStaging -> println("Running in STAGING")
    AppConfig.isProduction -> println("Running in PROD")
}

// Conditional logging
if (AppConfig.isLoggingEnabled) {
    Log.d("TAG", "Debug message")
}

// Log full configuration
AppConfig.logConfiguration()
```

Or access BuildConfig directly:

```kotlin
import com.example.rocketplan_android.BuildConfig

val apiUrl = BuildConfig.API_BASE_URL
val environment = BuildConfig.ENVIRONMENT
val isLoggingEnabled = BuildConfig.ENABLE_LOGGING
```

## Building for Different Environments

### Command Line Builds

**Development:**
```bash
export JAVA_HOME=/usr/local/opt/openjdk@17

# Debug
./gradlew assembleDevDebug

# Release
./gradlew assembleDevRelease
```

**Staging/Staging:**
```bash
# Debug
./gradlew assembleStagingDebug

# Release
./gradlew assembleStagingRelease
```

**Production:**
```bash
# Debug (for staginging prod config)
./gradlew assembleProdDebug

# Release (actual production build)
./gradlew assembleProdRelease
```

### Install Specific Variant

```bash
# Install dev debug build
./gradlew installDevDebug

# Install staging release build
./gradlew installStagingRelease

# Install prod release build
./gradlew installProdRelease
```

### Running Specific Variant

```bash
# Build and install dev
./gradlew installDevDebug

# Launch the app
adb shell am start -n com.example.rocketplan_android.dev/.MainActivity
```

### Android Studio

1. Open **Build Variants** panel (View → Tool Windows → Build Variants)
2. Select desired variant from dropdown:
   - `devDebug`
   - `stagingDebug`
   - `prodRelease`
   - etc.
3. Click **Run** or **Build**

## Adding New Environment Variables

### 1. Add to build.gradle.kts

Edit `app/build.gradle.kts` and add to the appropriate flavor:

```kotlin
productFlavors {
    create("dev") {
        // ... existing config
        buildConfigField("String", "NEW_API_KEY", "\"dev-key-123\"")
        buildConfigField("Int", "MAX_RETRY_COUNT", "5")
    }
}
```

### 2. Update AppConfig.kt

Add the new field to `AppConfig.kt`:

```kotlin
object AppConfig {
    val newApiKey: String = BuildConfig.NEW_API_KEY
    val maxRetryCount: Int = BuildConfig.MAX_RETRY_COUNT
}
```

### 3. Sync and Rebuild

```bash
./gradlew clean build
```

## Sensitive Configuration (API Keys, Secrets)

**Never commit sensitive data to git!**

### Using local.properties (Recommended)

1. Copy the example file:
   ```bash
   cp local.properties.example local.properties
   ```

2. Add your secrets to `local.properties`:
   ```properties
   api.key.dev=your-dev-key
   api.key.prod=your-prod-key
   ```

3. Read in `build.gradle.kts`:
   ```kotlin
   val localProperties = Properties()
   val localPropertiesFile = rootProject.file("local.properties")
   if (localPropertiesFile.exists()) {
       localProperties.load(FileInputStream(localPropertiesFile))
   }

   android {
       productFlavors {
           create("dev") {
               val devKey = localProperties.getProperty("api.key.dev", "")
               buildConfigField("String", "API_KEY", "\"$devKey\"")
           }
       }
   }
   ```

4. `local.properties` is already in `.gitignore` ✅

### Environment Variables

You can also use environment variables:

```kotlin
productFlavors {
    create("prod") {
        val prodKey = System.getenv("PROD_API_KEY") ?: ""
        buildConfigField("String", "API_KEY", "\"$prodKey\"")
    }
}
```

## Multiple Apps Installed Simultaneously

Due to different `applicationId` values, you can install all three environments on the same device:

- Dev: `com.example.rocketplan_android.dev`
- Staging: `com.example.rocketplan_android.staging`
- Prod: `com.example.rocketplan_android`

Each will appear as a separate app with its own name (RocketPlan Dev, RocketPlan Staging, RocketPlan).

## Different App Icons per Environment

To use different icons for each environment:

1. Create flavor-specific resource directories:
   ```
   app/src/dev/res/mipmap-*/
   app/src/staging/res/mipmap-*/
   app/src/prod/res/mipmap-*/
   ```

2. Place environment-specific icons in each directory

3. Android will automatically use the flavor-specific resources when building

## Firebase/Google Services per Environment

For environment-specific Firebase configs:

1. Create flavor-specific directories:
   ```
   app/src/dev/google-services.json
   app/src/staging/google-services.json
   app/src/prod/google-services.json
   ```

2. Each will be used for its corresponding build variant

## Sentry Crash Reporting

- Add DSN values per environment to `local.properties` (or use CI env vars):
  - `sentry.dsn.dev` / `SENTRY_DSN_DEV`
  - `sentry.dsn.staging` / `SENTRY_DSN_STAGING`
  - `sentry.dsn.prod` / `SENTRY_DSN_PROD`
- Leaving a DSN blank disables Sentry for that flavor; staging/prod should be set so crashes are captured.
- Release/environment info is sent automatically as `<applicationId>@<versionName>+<versionCode>` with the `ENVIRONMENT` tag for filtering in Sentry.

## Best Practices

✅ **DO:**
- Use `BuildConfig` for environment-specific URLs and flags
- Keep secrets in `local.properties` (not committed)
- Use different app names and IDs for each environment
- Staging prod configuration with `prodDebug` builds
- Enable logging for dev/staging, disable for prod
- Use ProGuard/R8 only for release builds

❌ **DON'T:**
- Commit API keys or secrets to git
- Use production credentials in dev/staging
- Enable verbose logging in production
- Skip staginging release builds before deployment

## Troubleshooting

**BuildConfig fields not found:**
- Sync Gradle: File → Sync Project with Gradle Files
- Clean and rebuild: `./gradlew clean build`
- Check `buildFeatures { buildConfig = true }` is enabled

**Wrong environment loaded:**
- Check selected Build Variant in Android Studio
- Verify correct variant in command: `./gradlew assembleDev`**Debug**
- Clear app data and reinstall

**Multiple apps not installing:**
- Ensure `applicationIdSuffix` is set for dev/staging
- Check each has unique `applicationId`

## Additional Resources

- [Android Product Flavors Documentation](https://developer.android.com/build/build-variants)
- [BuildConfig Fields](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/dsl/BuildType#buildConfigField(java.lang.String,java.lang.String,java.lang.String))
- [Managing App Resources](https://developer.android.com/guide/topics/resources/providing-resources)

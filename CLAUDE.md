# RocketPlan Android

## Build Variants

- **Dev**: `devStandardDebug` - Development build with `.dev` suffix
- **Staging**: `stagingStandardDebug` - Staging build with `.staging` suffix
- **Production**: `prodStandardRelease` - Production release build

## Fastlane

Fastlane is used for build automation and deployment.

### Setup

```bash
bundle install
```

### Available Lanes

| Command | Description |
|---------|-------------|
| `bundle exec fastlane bump` | Increment build number in build.gradle.kts |
| `bundle exec fastlane build_debug` | Build dev debug APK |
| `bundle exec fastlane build_staging` | Build staging APK |
| `bundle exec fastlane build_release` | Build production release AAB |
| `bundle exec fastlane test` | Run unit tests |
| `bundle exec fastlane bump_and_build` | Bump version + build debug |
| `bundle exec fastlane clean` | Clean build directory |
| `bundle exec fastlane deploy_internal` | Bump + build + deploy to Play Store internal track |
| `bundle exec fastlane deploy_beta` | Bump + build + deploy to Play Store beta track |
| `bundle exec fastlane deploy_production` | Bump + build + deploy to Play Store production |

### Play Store Deployment

To deploy to Play Store, add your Google Play JSON key file path to `fastlane/Appfile`:

```ruby
json_key_file("path/to/your/play-store-key.json")
```

## Gradle Commands

```bash
./gradlew assembleDevStandardDebug      # Build debug APK
./gradlew installDevStandardDebug       # Install to connected device
./gradlew testDevStandardDebugUnitTest  # Run unit tests
./gradlew compileDevStandardDebugKotlin # Compile only (fast check)
```

## Version Management

Build number is in `app/build.gradle.kts`:

```kotlin
val buildNumber = 5
versionCode = buildNumber
versionName = "1.29 ($buildNumber)"
```

Use `fastlane bump` to increment automatically.

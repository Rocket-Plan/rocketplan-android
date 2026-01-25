# RocketPlan Android

## Build Variants

- **Dev**: `devStandardDebug` - Development build with `.dev` suffix
- **Staging**: `stagingStandardDebug` - Staging build with `.staging` suffix
- **Production**: `prodStandardRelease` - Production release build

## Fastlane

Fastlane is used for build automation and deployment.

### Setup

Requires Homebrew Ruby (system Ruby won't work):

```bash
brew install ruby
/usr/local/opt/ruby/bin/bundle _2.5.0_ install
```

### Running Lanes

Use the Homebrew Ruby bundle with version specifier:

```bash
/usr/local/opt/ruby/bin/bundle _2.5.0_ exec fastlane <lane>
```

Or add an alias to your shell config:

```bash
alias fastlane='/usr/local/opt/ruby/bin/bundle _2.5.0_ exec fastlane'
```

### Available Lanes

| Lane | Description |
|------|-------------|
| `bump` | Increment build number in build.gradle.kts |
| `build_debug` | Build dev debug APK |
| `build_staging` | Build staging APK |
| `build_release` | Build production release AAB |
| `test` | Run unit tests |
| `bump_and_build` | Bump version + build debug |
| `clean` | Clean build directory |
| `deploy_internal` | Bump + build + deploy to Play Store internal track |
| `deploy_beta` | Bump + build + deploy to Play Store beta track |
| `deploy_production` | Bump + build + deploy to Play Store production |

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

## FLIR Device

FLIR builds use the `flir` product flavor with ARM64 native libraries.

### Build & Install

```bash
./gradlew installDevFlirDebug           # Build and install FLIR debug APK
```

### WiFi Debugging

FLIR ixx device IP: `192.168.0.57`

To connect over WiFi (after enabling once via USB):

```bash
adb connect 192.168.0.57:5555
```

To enable WiFi debugging (requires USB connection once):

```bash
adb tcpip 5555
adb connect <device-ip>:5555
# Then unplug USB
```

## Version Management

Build number is in `app/build.gradle.kts`:

```kotlin
val buildNumber = 6
versionCode = buildNumber
versionName = "1.29 ($buildNumber)"
```

Use `fastlane bump` to increment automatically.

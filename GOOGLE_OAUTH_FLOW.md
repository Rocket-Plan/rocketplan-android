# Google OAuth Authentication Flow

This document describes the backend-driven OAuth authentication flow implemented for Google Sign-In, matching the iOS implementation.

## Overview

The Android app uses a **backend-driven OAuth flow** via Chrome Custom Tabs, which mirrors the iOS implementation using `ASWebAuthenticationSession`. This approach is more secure and maintainable than client-side OAuth implementations.

## Flow Architecture

```
┌─────────────┐     1. Tap      ┌──────────────────┐
│   Android   │──────────────────│  LoginFragment   │
│     App     │                  └──────────────────┘
└─────────────┘                           │
       │                                  │ 2. Open Chrome Custom Tab
       │                                  ▼
       │                    ┌──────────────────────────────────┐
       │                    │  Backend OAuth Endpoint          │
       │                    │  /oauth2/redirect/google?schema= │
       │                    └──────────────────────────────────┘
       │                                  │
       │                                  │ 3. Redirect to Google
       │                                  ▼
       │                    ┌──────────────────────────────────┐
       │                    │  Google OAuth Consent Screen     │
       │                    └──────────────────────────────────┘
       │                                  │
       │                                  │ 4. User authorizes
       │                                  ▼
       │                    ┌──────────────────────────────────┐
       │                    │  Backend handles OAuth callback  │
       │                    │  Exchanges code for tokens       │
       │                    │  Generates JWT for app           │
       │                    └──────────────────────────────────┘
       │                                  │
       │                                  │ 5. Redirect to deep link
       │                                  ▼
       │              rocketplan://oauth2/redirect?token=JWT&status=200
       │                                  │
       │ 6. Deep link                     │
       │    intercepted                   │
       ▼                                  │
┌─────────────────────────┐              │
│  MainActivity           │◄─────────────┘
│  handleOAuthCallback()  │
│  - Saves JWT token      │
│  - Navigates to home    │
└─────────────────────────┘
```

## Implementation Components

### 1. Environment-Specific Configuration

The OAuth flow uses different URL schemes per environment:

| Environment | OAuth Endpoint | Deep Link Scheme |
|-------------|---------------|------------------|
| **Dev** | `https://api-qa-mongoose-br2wu78v1.rocketplantech.com/oauth2/redirect/google?schema=rocketplan-dev` | `rocketplan-dev://oauth2/redirect` |
| **Staging** | `https://api-staging-mongoose-n5tr2spgf.rocketplantech.com/oauth2/redirect/google?schema=rocketplan-staging` | `rocketplan-staging://oauth2/redirect` |
| **Production** | `https://api-public.rocketplantech.com/oauth2/redirect/google?schema=rocketplan` | `rocketplan://oauth2/redirect` |

### 2. Key Files

#### `LoginFragment.kt:72-111`
Initiates OAuth flow by opening Chrome Custom Tab:
```kotlin
private fun signInWithGoogle() {
    val schema = when (BuildConfig.ENVIRONMENT) {
        "DEV" -> "rocketplan-dev"
        "STAGING" -> "rocketplan-staging"
        "PROD" -> "rocketplan"
        else -> "rocketplan-dev"
    }

    val oauthUrl = "${BuildConfig.API_BASE_URL}/oauth2/redirect/google?schema=$schema"

    val customTabsIntent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()

    customTabsIntent.launchUrl(requireContext(), Uri.parse(oauthUrl))
}
```

#### `MainActivity.kt:150-209`
Handles OAuth callback deep link:
```kotlin
private fun handleOAuthCallback(intent: Intent?) {
    intent?.data?.let { uri ->
        if (uri.scheme?.startsWith("rocketplan") == true &&
            uri.host == "oauth2" &&
            uri.path == "/redirect") {

            val token = uri.getQueryParameter("token")
            val status = uri.getQueryParameter("status")?.toIntOrNull()

            if (status == 200 && !token.isNullOrEmpty()) {
                lifecycleScope.launch {
                    authRepository.saveAuthToken(token)
                    navController.navigate(R.id.nav_home)
                }
            }
        }
    }
}
```

#### `AndroidManifest.xml:29-53`
Deep link intent filter configuration:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />

    <!-- All environment schemes -->
    <data android:scheme="rocketplan-dev" android:host="oauth2" android:path="/redirect" />
    <data android:scheme="rocketplan-staging" android:host="oauth2" android:path="/redirect" />
    <data android:scheme="rocketplan" android:host="oauth2" android:path="/redirect" />
</intent-filter>
```

### 3. Dependencies

**Chrome Custom Tabs** (only dependency needed):
```kotlin
// build.gradle.kts
implementation(libs.androidx.browser)

// libs.versions.toml
browserVersion = "1.8.0"
androidx-browser = { group = "androidx.browser", name = "browser", version.ref = "browserVersion" }
```

**Removed dependencies** (no longer needed):
- ❌ `google-play-services-auth`
- ❌ `androidx-credentials`
- ❌ `androidx-credentials-play-services`
- ❌ `google-googleid`

## OAuth Callback Format

The backend redirects to the app with this URL structure:

```
{scheme}://oauth2/redirect?token={JWT_TOKEN}&status={HTTP_STATUS}
```

**Success example:**
```
rocketplan-dev://oauth2/redirect?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...&status=200
```

**Failure example:**
```
rocketplan-dev://oauth2/redirect?status=401
```

## Security Considerations

### ✅ Advantages of This Approach

1. **No client secrets in app** - OAuth credentials only on backend
2. **Backend validates tokens** - Google ID tokens never reach the app
3. **Consistent flow** - Same logic as iOS app
4. **Easier updates** - OAuth config changes only require backend updates
5. **Better security** - Tokens can't be extracted from decompiled APK

### 🔒 Token Storage

The JWT token received from the backend is stored securely using:
- `EncryptedSharedPreferences` via `SecureStorage.kt`
- Android Keystore for encryption keys
- Cleared on logout

## Backend Requirements

The backend must implement the following endpoints:

### 1. OAuth Initiation
**Endpoint:** `GET /oauth2/redirect/google`

**Query Parameters:**
- `schema` - The app's custom URL scheme for callback

**Response:** HTTP redirect to Google OAuth consent screen

### 2. OAuth Callback Handler
**Endpoint:** `GET /oauth2/callback/google` (handled by backend, not app)

**Function:**
- Receives authorization code from Google
- Exchanges code for Google access token
- Validates user with Google API
- Creates or updates user in database
- Generates JWT token for app session
- Redirects to: `{schema}://oauth2/redirect?token={JWT}&status=200`

## Testing the Flow

### Manual Test Steps

1. **Launch app** in dev environment
2. **Tap "Sign in with Google"** button
3. **Chrome Custom Tab opens** showing Google consent screen
4. **Select Google account** and authorize
5. **Browser redirects** back to app automatically
6. **App receives token** via deep link
7. **Navigate to home** screen automatically
8. **Verify token saved** by killing and restarting app (should stay logged in)

### Debugging

Enable logging to see OAuth flow:

```kotlin
// Dev builds have logging enabled by default
if (BuildConfig.ENABLE_LOGGING) {
    Log.d("LoginFragment", "OAuth URL: $oauthUrl")
    Log.d("MainActivity", "OAuth callback - Token present: ${token != null}")
}
```

### Testing Deep Links via ADB

```bash
# Test dev environment deep link
adb shell am start -W -a android.intent.action.VIEW \
  -d "rocketplan-dev://oauth2/redirect?token=test_token_123&status=200" \
  com.example.rocketplan_android.dev

# Test staging environment
adb shell am start -W -a android.intent.action.VIEW \
  -d "rocketplan-staging://oauth2/redirect?token=test_token_123&status=200" \
  com.example.rocketplan_android.staging

# Test production environment
adb shell am start -W -a android.intent.action.VIEW \
  -d "rocketplan://oauth2/redirect?token=test_token_123&status=200" \
  com.example.rocketplan_android
```

## Troubleshooting

### Issue: Chrome Custom Tab doesn't open

**Solution:** Ensure Chrome or a Custom Tabs compatible browser is installed:
```kotlin
try {
    customTabsIntent.launchUrl(requireContext(), Uri.parse(oauthUrl))
} catch (e: ActivityNotFoundException) {
    // Fallback to default browser
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl))
    startActivity(intent)
}
```

### Issue: Deep link not intercepted

**Verify intent filter:**
```bash
adb shell dumpsys package com.example.rocketplan_android.dev | grep -A 10 "oauth2"
```

Should show intent filter with your custom schemes.

### Issue: App doesn't return from browser

**Check:**
- Intent filter includes `android.intent.category.BROWSABLE`
- Activity has `android:launchMode="singleTop"`
- `onNewIntent()` is implemented in MainActivity

### Issue: Token not saving

**Verify:**
1. `handleOAuthCallback()` is called (add logs)
2. Token is not null/empty
3. Status is 200
4. `AuthRepository.saveAuthToken()` completes successfully

## Comparison with Previous Implementation

| Aspect | Old (Credential Manager) | New (Backend OAuth) |
|--------|-------------------------|---------------------|
| **Client Configuration** | Android OAuth client needed | ❌ Not needed |
| **Dependencies** | 4 Google libraries | 1 browser library |
| **Token Flow** | ID token → Backend | Backend → JWT token |
| **Emulator Support** | Required fallback | ✅ Works natively |
| **Security** | Client has OAuth config | ✅ Backend-only config |
| **iOS Consistency** | ❌ Different flow | ✅ Same flow |
| **Maintenance** | App + backend updates | Backend updates only |

## Migration Notes

If migrating from the old Credential Manager implementation:

1. ✅ **Remove old OAuth credentials** from Google Cloud Console (Android client type)
2. ✅ **Keep web client credentials** - backend needs these
3. ✅ **Update dependencies** - remove Google Sign-In libraries
4. ✅ **Test all environments** - dev, staging, production
5. ✅ **Verify backend endpoints** - ensure `/oauth2/redirect/google` exists

## Future Enhancements

Potential improvements to consider:

- **State parameter** - Add CSRF protection via state parameter
- **PKCE** - Implement Proof Key for Code Exchange for additional security
- **Token refresh** - Handle JWT token expiration and refresh
- **Error handling** - More granular error messages from backend
- **Analytics** - Track OAuth flow completion rates

---

**Last Updated:** 2025-10-09
**Implementation Version:** 1.0
**Matches iOS Version:** SocialSignInService.swift

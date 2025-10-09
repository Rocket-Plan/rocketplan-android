# Google Sign-In Setup Guide

This guide explains how to complete the Google Sign-In integration for the RocketPlan Android app.

## Overview

The Google Sign-In implementation has been added to the app using the modern Credential Manager API. The integration includes:

- **Frontend**: Credential Manager API for secure Google authentication
- **Backend Integration**: Sends Google ID token to Laravel backend for verification
- **Token Management**: Stores authentication token securely after successful sign-in

## Implementation Status

✅ **Completed:**
- Added Google Sign-In dependencies to `build.gradle.kts`
- Created data models for Google Sign-In (`GoogleSignInRequest`, `GoogleSignInResponse`)
- Added `/auth/google` API endpoint in `AuthService.kt`
- Implemented `signInWithGoogle()` in `AuthRepository.kt`
- Added Google Sign-In handler in `LoginViewModel.kt`
- Implemented Google Sign-In flow in `LoginFragment.kt`

⚠️ **Requires Configuration:**
1. Google Cloud Project setup
2. OAuth 2.0 Client ID configuration
3. Update the client ID in the app
4. Backend API endpoint implementation

## Setup Instructions

### 1. Google Cloud Console Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing project
3. Enable **Google Sign-In API**:
   - Navigate to "APIs & Services" > "Library"
   - Search for "Google Sign-In API"
   - Click "Enable"

### 2. Create OAuth 2.0 Credentials

1. Navigate to "APIs & Services" > "Credentials"
2. Click "Create Credentials" > "OAuth client ID"
3. Select "Web application" (for backend verification)
4. Configure:
   - **Name**: RocketPlan Android Backend
   - **Authorized redirect URIs**: Your backend URLs
   - Click "Create"
5. **Save the Client ID** - you'll need this for the app

### 3. Get SHA-1 Certificate Fingerprint

For development builds:

```bash
# Debug keystore SHA-1
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

For production builds:
```bash
# Your release keystore SHA-1
keytool -list -v -keystore /path/to/your/release.keystore
```

### 4. Add Android OAuth Client

1. In Google Cloud Console > "Credentials"
2. Click "Create Credentials" > "OAuth client ID"
3. Select "Android"
4. Configure:
   - **Name**: RocketPlan Android App
   - **Package name**: `com.example.rocketplan_android` (for dev: `com.example.rocketplan_android.dev`)
   - **SHA-1 certificate fingerprint**: Paste the SHA-1 from step 3
   - Click "Create"

### 5. Update App Configuration

Open `LoginFragment.kt` and replace the placeholder with your **Web Client ID** (from step 2):

```kotlin
// File: app/src/main/java/com/example/rocketplan_android/ui/login/LoginFragment.kt
// Line ~83

val googleIdOption = GetGoogleIdOption.Builder()
    .setFilterByAuthorizedAccounts(false)
    .setServerClientId("YOUR_ACTUAL_CLIENT_ID_HERE") // Replace this!
    .build()
```

**Important**: Use the **Web Client ID** (from step 2), NOT the Android Client ID!

### 6. Backend API Implementation

The app expects a Laravel backend endpoint at `POST /auth/google` that:

1. Receives the Google ID token
2. Verifies it with Google
3. Creates/finds the user
4. Returns a Sanctum authentication token

**Expected Request:**
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjdlM..."
}
```

**Expected Response (Success):**
```json
{
  "token": "1|plainTextTokenString...",
  "user": {
    "id": "123",
    "email": "user@example.com",
    "firstName": "John",
    "lastName": "Doe"
  }
}
```

**Expected Response (Error - 401/422):**
```json
{
  "message": "Invalid Google credentials"
}
```

### 7. Laravel Backend Example

Here's a sample Laravel controller implementation:

```php
use Google_Client;
use Illuminate\Http\Request;

class AuthController extends Controller
{
    public function googleSignIn(Request $request)
    {
        $request->validate([
            'id_token' => 'required|string',
        ]);

        $client = new Google_Client(['client_id' => config('services.google.client_id')]);

        try {
            $payload = $client->verifyIdToken($request->id_token);

            if (!$payload) {
                return response()->json(['message' => 'Invalid Google credentials'], 422);
            }

            // Find or create user
            $user = User::firstOrCreate(
                ['email' => $payload['email']],
                [
                    'first_name' => $payload['given_name'] ?? null,
                    'last_name' => $payload['family_name'] ?? null,
                    'email_verified_at' => now(),
                ]
            );

            // Create Sanctum token
            $token = $user->createToken('android-app')->plainTextToken;

            return response()->json([
                'token' => $token,
                'user' => [
                    'id' => $user->id,
                    'email' => $user->email,
                    'firstName' => $user->first_name,
                    'lastName' => $user->last_name,
                ]
            ]);

        } catch (\Exception $e) {
            return response()->json(['message' => 'Google authentication failed'], 401);
        }
    }
}
```

Add to your `config/services.php`:
```php
'google' => [
    'client_id' => env('GOOGLE_CLIENT_ID'),
],
```

### 8. Testing

1. Build and run the app:
   ```bash
   ./gradlew installDevDebug
   ```

2. Click the "Google Sign-In" button
3. Select a Google account
4. Verify the app receives the token and navigates to the home screen

### 9. Environment-Specific Configuration

The app uses different Google Client IDs for each environment:

- **Dev**: `com.example.rocketplan_android.dev`
- **Staging**: `com.example.rocketplan_android.staging`
- **Prod**: `com.example.rocketplan_android`

You may need to create separate OAuth clients for each package name.

### 10. Troubleshooting

**"Google Sign-In failed: No credentials available"**
- Ensure the Web Client ID is correctly configured
- Verify SHA-1 certificate is added to Google Cloud Console
- Check package name matches your OAuth Android client

**"Invalid Google credentials" (422 error)**
- Backend cannot verify the ID token
- Check backend Google Client ID matches the Web Client ID
- Ensure backend has `google/apiclient` package installed

**"Sign up is not supported at this time"**
- Google account picker is filtering accounts
- Set `.setFilterByAuthorizedAccounts(false)` in LoginFragment

## Files Modified

- `gradle/libs.versions.toml` - Added Google Sign-In dependencies
- `app/build.gradle.kts` - Added implementation dependencies
- `app/src/main/java/com/example/rocketplan_android/data/model/AuthModels.kt` - Added data models
- `app/src/main/java/com/example/rocketplan_android/data/api/AuthService.kt` - Added API endpoint
- `app/src/main/java/com/example/rocketplan_android/data/repository/AuthRepository.kt` - Added repository method
- `app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt` - Added ViewModel method
- `app/src/main/java/com/example/rocketplan_android/ui/login/LoginFragment.kt` - Implemented UI flow

## Security Considerations

1. **Never commit** the Google Client ID to version control if it's sensitive
2. Consider using BuildConfig or local.properties for client IDs
3. Backend should always verify ID tokens with Google
4. Use HTTPS for all API communications
5. Implement rate limiting on the backend endpoint

## Additional Resources

- [Google Sign-In for Android](https://developers.google.com/identity/sign-in/android/start)
- [Credential Manager API](https://developer.android.com/training/sign-in/credential-manager)
- [Laravel Socialite](https://laravel.com/docs/socialite)
- [Google API Client Library for PHP](https://github.com/googleapis/google-api-php-client)

# Login Implementation

This document describes the login functionality that has been implemented based on the iOS RocketPlan app.

## Overview

The login screen replicates the iOS authentication flow with:
- Email and password input fields
- Form validation
- "Forgot Password" link
- Clean, branded UI matching RocketPlan's design system
- MVVM architecture pattern

## Architecture

### Files Created

**Data Models** (`app/src/main/java/com/example/rocketplan_android/data/model/AuthModels.kt`):
- `CheckEmailRequest` / `CheckEmailResponse` - Email validation
- `SignInRequest` / `SignInResponse` - Login credentials and response
- `ResetPasswordRequest` / `ResetPasswordResponse` - Password reset
- `User` - User data model
- `Company` - Company data model

**API Service** (`app/src/main/java/com/example/rocketplan_android/data/api/AuthService.kt`):
- Retrofit interface for authentication endpoints
- `/auth/check-email` - Check if email is registered
- `/auth/signin` - Sign in with credentials
- `/auth/reset-password` - Request password reset

**UI Components** (`app/src/main/java/com/example/rocketplan_android/ui/components/TextInputField.kt`):
- Custom input field component matching iOS design
- Features:
  - Title label
  - Placeholder text
  - Error message display
  - Focus state handling with color changes (purple when focused)
  - Auto-clearing errors on text change
  - Support for email and password input types

**ViewModel** (`app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt`):
- Handles login business logic
- Email validation (format check)
- Password validation (minimum 6 characters)
- Loading state management
- Error handling
- Navigation events

**Fragment** (`app/src/main/java/com/example/rocketplan_android/ui/login/LoginFragment.kt`):
- UI implementation for login screen
- View binding for type-safe view access
- ViewModel observers for reactive UI updates
- Keyboard handling

**Layout** (`app/src/main/res/layout/fragment_login.xml`):
- Logo at top (RocketPlan horizontal logo)
- "Log In" title
- Email input field
- Password input field
- "Forgot Password?" link in purple
- Sign In button (fixed at bottom)
- Loading indicator
- Error message display

## Design Features

### Matches iOS Design
- ✅ RocketPlan logo at top
- ✅ Clean white background
- ✅ Purple branding (`#9A00FF`)
- ✅ Input fields with:
  - Title labels
  - Bottom border (changes to purple on focus)
  - Error state (red border and message)
- ✅ Underlined "Forgot Password?" text button
- ✅ Primary purple button at bottom

### Validation
- **Email**:
  - Required field
  - Valid email format check using Android's built-in pattern matcher
- **Password**:
  - Required field
  - Minimum 6 characters
- Real-time error clearing when user starts typing

### States
- **Loading**: Shows progress indicator, disables input
- **Error**: Displays error message in red
- **Success**: Navigates to home screen

## Navigation Flow

1. **App Launch** → Login Screen (entry point)
2. **Successful Login** → Home Screen (with drawer and toolbar visible)
3. **Forgot Password** → (TODO: Implement forgot password screen)

The navigation is configured to:
- Start at `loginFragment`
- Hide toolbar, drawer, and FAB on login screen
- Show toolbar, drawer, and FAB after login
- Prevent back navigation to login after successful login (`popUpToInclusive`)

## Modified Files

**MainActivity.kt**:
- Added destination listener to hide/show UI elements based on screen
- Login screen: toolbar, drawer, and FAB hidden
- Other screens: toolbar, drawer, and FAB visible

**mobile_navigation.xml**:
- Added `loginFragment` as start destination
- Added navigation action from login to home

**build.gradle.kts** & **libs.versions.toml**:
- Added Retrofit 2.9.0 for networking
- Added Gson 2.10.1 for JSON serialization
- Added OkHttp 4.12.0 logging interceptor

## Usage

### Running the App

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
./gradlew assembleDevDebug
./gradlew installDevDebug
```

The app will launch directly to the login screen.

### Testing Login

Currently, the login is **simulated** (no actual API call yet). After a 1-second delay, it will:
- Show loading indicator
- Log attempt to console (in dev/staging environments)
- Navigate to home screen

### Accessing Configuration

The ViewModel uses `AppConfig` for environment-specific settings:

```kotlin
AppConfig.apiBaseUrl  // Dev: https://api-qa-mongoose-br2wu78v1.rocketplantech.com
AppConfig.isLoggingEnabled  // true for dev/staging
```

## Next Steps (TODO)

### 1. Implement Actual API Integration

Create a Retrofit client and connect to real authentication endpoints:

```kotlin
// In LoginViewModel.kt, replace the simulated sign-in:

val authService = RetrofitClient.create<AuthService>(AppConfig.apiBaseUrl)
val response = authService.signIn(SignInRequest(email, password))

if (response.isSuccessful) {
    val token = response.body()?.token
    // Store token securely
    _signInSuccess.value = true
} else {
    _errorMessage.value = "Invalid credentials"
}
```

### 2. Token Storage

Implement secure token storage using Android's:
- **Shared Preferences** (with encryption) for simple cases
- **DataStore** (recommended) for modern apps
- **Keystore** for highly sensitive data

Example using DataStore:

```kotlin
// Create AuthRepository.kt
class AuthRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
    }

    suspend fun saveToken(token: String) {
        dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
        }
    }

    fun getToken(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[TOKEN_KEY]
    }

    suspend fun clearToken() {
        dataStore.edit { preferences ->
            preferences.remove(TOKEN_KEY)
        }
    }
}
```

### 3. Create Forgot Password Screen

Implement `AuthResetPasswordFragment` matching iOS design:
- Email input field
- Submit button
- Success/error messaging
- Navigation back to login

### 4. Add Remember Me

- Store encrypted credentials if user opts in
- Auto-login on app launch if token is valid

### 5. Social Login (Optional)

iOS app supports:
- Facebook
- Google
- Apple

Implement using respective Android SDKs.

### 6. Loading States & Error Handling

- Network error handling
- Timeout handling
- Server error messages
- Offline mode detection

### 7. Initial Email Check Flow

Match iOS flow:
1. User enters email
2. Check if email is registered (`/auth/check-email`)
3. If registered → show password field (sign in)
4. If not registered → navigate to sign up

### 8. Dependency Injection

Set up Hilt or Koin for:
- ViewModel injection
- Repository injection
- Network client injection

Example with Hilt:

```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    // ...
}
```

### 9. Unit Tests

Add tests for:
- Email validation logic
- Password validation logic
- Sign-in flow
- Error handling

### 10. UI Tests

Add Espresso tests for:
- Input field interactions
- Button clicks
- Navigation flow
- Error message display

## API Endpoints

Based on iOS implementation and current configuration:

### Dev Environment
**Base URL**: `https://api-qa-mongoose-br2wu78v1.rocketplantech.com`

### Staging Environment
**Base URL**: `https://api-staging-mongoose-n5tr2spgf.rocketplantech.com`

### Production Environment
**Base URL**: `https://api-public.rocketplantech.com`

### Expected Endpoints

```
POST /auth/check-email
Body: { "email": "user@example.com" }
Response: { "registered": true }

POST /auth/signin
Body: { "email": "user@example.com", "password": "password123" }
Response: { "token": "eyJ0...", "user": { ... } }

POST /auth/reset-password
Body: { "email": "user@example.com" }
Response: { "message": "Password reset email sent" }
```

## Design System Used

- **Primary Purple**: `#9A00FF` (`@color/main_purple`)
- **Warning Red**: `#E82828` (`@color/warning_red`)
- **White Background**: `#FFFFFF` (`@color/white`)
- **Light Border**: `#E8E7ED` (`@color/light_border`)
- **Logo**: `@drawable/logo_horizontal`

## Notes

- All UI components follow Material Design 3
- Input fields use custom component for consistency with iOS
- Form validation happens on submit (not real-time)
- Errors clear automatically when user types
- Loading state disables all inputs during API call
- Navigation uses Jetpack Navigation Component with Safe Args

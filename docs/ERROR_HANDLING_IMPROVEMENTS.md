# Error Handling Improvements

This document outlines the comprehensive error handling improvements made to the RocketPlan Android app.

## Overview

The authentication flow now includes graceful error handling with user-friendly messages that provide clear guidance on what went wrong and how to resolve issues.

## Key Changes

### 1. Fixed API Endpoint Issues

**Problem:** The app was calling incorrect API endpoints, resulting in 404 errors.

**Solution:** Updated all authentication endpoints in `AuthService.kt` to match the backend API:

- `auth/check-email` → `api/auth/email-check`
- `auth/login` → `api/auth/login`
- `auth/register` → `api/auth/register`
- `auth/reset-password` → `api/auth/forgot-password`

### 2. Created ApiError Model

**File:** `app/src/main/java/com/example/rocketplan_android/data/model/ApiError.kt`

A sealed class hierarchy that categorizes and provides user-friendly messages for different types of errors:

#### Error Types

1. **NetworkError** - No internet connection or network failures
   - Message: "Unable to connect. Please check your internet connection and try again."

2. **AuthenticationError** - 401 Unauthorized
   - Message: "Your session has expired. Please sign in again."

3. **ValidationError** - 422 Unprocessable Entity
   - Parses Laravel validation errors and displays specific field errors
   - Falls back to generic validation message

4. **NotFoundError** - 404 Not Found
   - Message: "The requested resource could not be found. Please try again later."

5. **RateLimitError** - 429 Too Many Requests
   - Message: "Too many attempts. Please try again later."

6. **ServerError** - 5xx Server errors
   - Message: "We're experiencing technical difficulties. Please try again later."

7. **TimeoutError** - Connection timeouts
   - Message: "The request took too long. Please check your connection and try again."

8. **UnknownError** - Unexpected errors
   - Message: "Something went wrong. Please try again."

#### Special Auth Scenarios

The `ApiError.forAuthScenario()` method provides context-specific messages:

- `invalid_credentials` - "The email or password you entered is incorrect. Please try again."
- `email_exists` - "An account with this email already exists. Please sign in instead."
- `weak_password` - "Password must be at least 8 characters long."
- `passwords_mismatch` - "Passwords don't match. Please try again."

### 3. Enhanced AuthRepository Error Handling

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/AuthRepository.kt`

All repository methods now use `ApiError` to parse and format errors:

```kotlin
suspend fun signIn(email: String, password: String, rememberMe: Boolean): Result<LoginResponse> {
    return try {
        val response = authService.login(LoginRequest(email, password))

        if (response.isSuccessful && response.body() != null) {
            // Handle success...
        } else {
            val apiError = ApiError.fromHttpResponse(response.code(), errorBody)
            Result.failure(Exception(apiError.displayMessage))
        }
    } catch (e: Exception) {
        val apiError = ApiError.fromException(e)
        Result.failure(Exception(apiError.displayMessage))
    }
}
```

**Benefits:**
- Consistent error handling across all API calls
- User-friendly error messages
- Better distinction between network, validation, and server errors
- Laravel validation error parsing

### 4. Simplified ViewModel Error Handling

Updated all ViewModels to use the pre-formatted error messages from ApiError:

**Files:**
- `EmailCheckViewModel.kt`
- `LoginViewModel.kt`
- `SignUpViewModel.kt`
- `ForgotPasswordViewModel.kt`

**Before:**
```kotlin
try {
    val result = repository.signIn(email, password)
    // handle result
} catch (e: Exception) {
    _errorMessage.value = "Network error: ${e.message}"
}
```

**After:**
```kotlin
val result = repository.signIn(email, password)
if (result.isSuccess) {
    // handle success
} else {
    // Error message is already user-friendly from ApiError
    val error = result.exceptionOrNull()
    _errorMessage.value = error?.message ?: "Sign in failed. Please try again."
}
```

### 5. Created Error Utility Functions

**File:** `app/src/main/java/com/example/rocketplan_android/util/ErrorUtils.kt`

Utility functions for displaying errors in the UI:

```kotlin
// Show error with optional retry action
ErrorUtils.showErrorSnackbar(
    view = binding.root,
    message = "Unable to connect",
    actionText = "Retry"
) {
    viewModel.signIn()
}

// Show success message
ErrorUtils.showSuccessSnackbar(
    view = binding.root,
    message = "Password reset email sent"
)

// Helper functions
ErrorUtils.isNetworkError(errorMessage) // Check if network-related
ErrorUtils.isAuthError(errorMessage)    // Check if auth-related
```

## Error Message Examples

### Network Errors

| Scenario | User Sees |
|----------|-----------|
| No internet | "Unable to connect. Please check your internet connection and try again." |
| Timeout | "The request took too long. Please check your connection and try again." |
| Server down | "We're experiencing technical difficulties. Please try again later." |

### Authentication Errors

| Scenario | User Sees |
|----------|-----------|
| Wrong password | "The email or password you entered is incorrect. Please try again." |
| Email already exists | "An account with this email already exists. Please sign in instead." |
| Rate limited | "Too many attempts. Please try again later." |
| Session expired | "Your session has expired. Please sign in again." |

### Validation Errors

| Scenario | User Sees |
|----------|-----------|
| Invalid email format | "Please enter a valid email address" |
| Password too short | "Password must be at least 8 characters long." |
| Passwords don't match | "Passwords don't match. Please try again." |
| Missing field | Field-specific error from Laravel backend |

## Implementation Benefits

1. **User Experience**
   - Clear, actionable error messages
   - No technical jargon or stack traces shown to users
   - Consistent error presentation across the app

2. **Maintainability**
   - Centralized error handling logic in `ApiError` class
   - Easy to add new error types or scenarios
   - Reduced code duplication in ViewModels

3. **Debugging**
   - Original error messages preserved in logging
   - Structured error types for analytics
   - Easy to identify error categories

4. **Internationalization Ready**
   - All error messages in one place
   - Easy to extract to string resources for translation
   - Consistent terminology

## Usage Guidelines

### Adding New Error Messages

1. Add new scenario to `ApiError.forAuthScenario()` if needed
2. Or add new sealed class type if it's a new category
3. Update error parsing logic in `fromHttpResponse()` or `fromException()`

### Displaying Errors in UI

```kotlin
// In Fragment or Activity
viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
    if (!error.isNullOrBlank()) {
        ErrorUtils.showErrorSnackbar(binding.root, error)
    }
}
```

### Testing Error Scenarios

1. Network errors: Turn off WiFi/cellular
2. Validation errors: Submit invalid data
3. Auth errors: Use wrong credentials
4. Server errors: Test with staging/dev environments

## Future Improvements

1. **Error Analytics**
   - Track error types and frequencies
   - Monitor API reliability
   - Identify common user issues

2. **Retry Logic**
   - Automatic retry for transient errors
   - Exponential backoff for rate limits
   - Queue failed requests

3. **Offline Support**
   - Cache and sync when online
   - Offline-first architecture
   - Better offline indicators

4. **Localization**
   - Move all error strings to resources
   - Support multiple languages
   - Context-aware messages

## Related Files

- `ApiError.kt` - Error model and parsing
- `AuthRepository.kt` - API error handling
- `ErrorUtils.kt` - UI error utilities
- `*ViewModel.kt` - ViewModel error handling
- `AuthService.kt` - API endpoint definitions

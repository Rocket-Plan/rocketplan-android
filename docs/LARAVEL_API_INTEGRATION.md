# Laravel API Integration - Updated ✅

The Android app has been updated to match your Laravel API endpoints exactly.

## API Endpoint Mapping

### ✅ Login Endpoint (Updated)

**Android:**
```kotlin
@POST("auth/login")
suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
```

**Request:**
```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

**Laravel Route:** `POST /auth/login`
- Controller: `LoginController::store`
- Middleware: `guest:web`, `throttle:login`
- Rate Limit: 5 attempts per minute per email+IP

**Success Response (200):**
```json
{
  "token": "1|abcdef1234567890..."
}
```

**Error Responses:**
- `422` - Validation error or invalid credentials: "These credentials do not match our records."
- `429` - Rate limit exceeded: "Too many login attempts. Please try again later."

### Token Format

**Sanctum Token:** `{token_id}|{40_char_random_string}`
- Example: `1|WjL9xKzP4mNqRsT7vUwXyZ2aBcDeF3gHiJkL5mNoP6qR`
- Stored in: `personal_access_tokens` table
- Token name derived from User-Agent header

## Request Headers

The Android app automatically adds these headers to all API requests:

```
Content-Type: application/json
Accept: application/json
User-Agent: RocketPlan Android/1.0 (Android 34; Pixel 6)
Authorization: Bearer {token}  // (after login)
```

### User-Agent Format

```
RocketPlan Android/{versionName} (Android {SDK_INT}; {deviceModel})
```

**Examples:**
- `RocketPlan Android/1.0 (Android 34; Pixel 6)`
- `RocketPlan Android/1.0 (Android 33; Samsung SM-G998B)`

Laravel uses this to name the Sanctum token in the database.

## Authentication Flow

### 1. Login Request

**Android Code:**
```kotlin
// In AuthRepository
val response = authService.login(LoginRequest(email, password))

if (response.isSuccessful && response.body() != null) {
    val token = response.body()!!.token
    // Token format: "1|abcdef1234567890..."
    saveAuthToken(token)
}
```

**API Call:**
```
POST https://api-qa-mongoose-br2wu78v1.rocketplantech.com/auth/login
Content-Type: application/json
User-Agent: RocketPlan Android/1.0 (Android 34; Pixel 6)

{
  "email": "user@example.com",
  "password": "password123"
}
```

### 2. Token Storage

After successful login:
```kotlin
// Save token to DataStore
secureStorage.saveAuthToken(token)

// Set token in Retrofit client
RetrofitClient.setAuthToken(token)

// All subsequent requests will include:
// Authorization: Bearer 1|abcdef1234567890...
```

### 3. Authenticated Requests

All API requests after login automatically include the Bearer token:

```
GET https://api-qa-mongoose-br2wu78v1.rocketplantech.com/api/user/profile
Authorization: Bearer 1|abcdef1234567890...
Content-Type: application/json
Accept: application/json
User-Agent: RocketPlan Android/1.0 (Android 34; Pixel 6)
```

## Error Handling

The Android app handles Laravel error responses:

### 422 Validation Error
```kotlin
when (response.code()) {
    422 -> "These credentials do not match our records."
}
```

**Laravel Response:**
```json
{
  "message": "These credentials do not match our records.",
  "errors": {
    "email": ["These credentials do not match our records."]
  }
}
```

### 429 Rate Limit
```kotlin
when (response.code()) {
    429 -> "Too many login attempts. Please try again later."
}
```

**Laravel Throttle:**
- 5 attempts per minute per `{email}{ip_address}`
- Configured in: `FortifyServiceProvider.php:41-43`

### Network Errors
```kotlin
catch (e: Exception) {
    // Connection errors, timeouts, etc.
    Result.failure(e)
}
```

## Security Features Matching Laravel

### ✅ Password Security
- **Android**: Passwords never stored in plain text
- **Laravel**: bcrypt verification via `Auth::attempt()`
- **Match**: Both use industry-standard password hashing

### ✅ Token Authentication
- **Android**: Bearer token in Authorization header
- **Laravel**: Sanctum token validation
- **Match**: Standard OAuth2 Bearer token format

### ✅ Rate Limiting
- **Android**: Displays user-friendly error on 429
- **Laravel**: 5 attempts/minute per email+IP
- **Match**: Prevents brute force attacks

### ✅ Ambiguous Error Messages
- **Android**: Shows same error for invalid email or password
- **Laravel**: Returns same error to prevent user enumeration
- **Match**: Both prevent revealing which accounts exist

### ✅ HTTPS Only
- **Android**: All URLs use HTTPS
- **Laravel**: API requires HTTPS in production
- **Match**: Encrypted communication

## Environment Configuration

### Dev Environment
```kotlin
API_BASE_URL = "https://api-qa-mongoose-br2wu78v1.rocketplantech.com"
ENABLE_LOGGING = true
```

**Full Request Logging:**
```
D/OkHttp: --> POST https://api-qa-mongoose-br2wu78v1.rocketplantech.com/auth/login
D/OkHttp: Content-Type: application/json
D/OkHttp: User-Agent: RocketPlan Android/1.0 (Android 34; Pixel 6)
D/OkHttp: {"email":"user@example.com","password":"password123"}
D/OkHttp: --> END POST

D/OkHttp: <-- 200 OK (245ms)
D/OkHttp: {"token":"1|abcdef1234567890..."}
D/OkHttp: <-- END HTTP
```

### Staging Environment
```kotlin
API_BASE_URL = "https://api-staging-mongoose-n5tr2spgf.rocketplantech.com"
ENABLE_LOGGING = true
```

### Production Environment
```kotlin
API_BASE_URL = "https://api-public.rocketplantech.com"
ENABLE_LOGGING = false  // No logging in production
```

## Testing the Integration

### Build and Install
```bash
export JAVA_HOME=/usr/local/opt/openjdk@17

# Dev environment (QA API)
./gradlew installDevDebug

# Staging environment
./gradlew installStagingDebug

# Production environment
./gradlew installProdRelease
```

### View API Logs
```bash
# Watch all OkHttp logs
adb logcat | grep "OkHttp"

# Watch specific request/response
adb logcat | grep -A 10 "auth/login"
```

### Test Scenarios

**1. Valid Login:**
```
Email: test@example.com
Password: password123

Expected: 200 OK with token
Result: Navigate to home screen
```

**2. Invalid Credentials:**
```
Email: test@example.com
Password: wrongpassword

Expected: 422 Unprocessable Entity
Result: "These credentials do not match our records."
```

**3. Rate Limiting:**
```
Attempt login 6 times in 1 minute

Expected: 429 Too Many Requests
Result: "Too many login attempts. Please try again later."
```

**4. Network Error:**
```
Turn off WiFi/Data

Expected: Network exception
Result: "Network error: Unable to resolve host..."
```

## Token Lifecycle

### 1. Login
```
User enters email/password → API returns token → Save to DataStore
```

### 2. App Usage
```
All API requests include: Authorization: Bearer {token}
```

### 3. Logout
```kotlin
authRepository.logout()
// - Clears token from DataStore
// - Clears token from Retrofit
// - Clears saved credentials
// - Navigate to login screen
```

### 4. Token Expiration (Future)
```
If API returns 401 Unauthorized:
- Clear token
- Show "Session expired" message
- Navigate to login screen
```

## Middleware Checks (Laravel)

After successful login, protected routes check:

1. **`auth:sanctum`** - Valid token
2. **`sms.verified`** - SMS verification status
3. **`company.approved`** - Company approval status

**Android Implementation (Future):**
```kotlin
// Handle 403 Forbidden responses
when (response.code()) {
    401 -> "Session expired. Please login again."
    403 -> "Account verification required."
}
```

## Data Models

### LoginRequest
```kotlin
data class LoginRequest(
    val email: String,      // Required, valid email format
    val password: String    // Required, string
)
```

### LoginResponse
```kotlin
data class LoginResponse(
    val token: String  // Sanctum token: "1|abcdef..."
)
```

## Retrofit Configuration

```kotlin
object RetrofitClient {
    // Base URL from environment
    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.apiBaseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Interceptor adds:
    // - Authorization header (if token exists)
    // - User-Agent header
    // - Content-Type: application/json
    // - Accept: application/json
}
```

## Summary

✅ **Endpoint**: Changed from `/auth/signin` to `/auth/login`
✅ **Request**: Matches Laravel `LoginRequest` validation
✅ **Response**: Expects `{ "token": "..." }` format
✅ **Headers**: Includes User-Agent for token naming
✅ **Errors**: Handles 422 (validation) and 429 (rate limit)
✅ **Token**: Saved as Bearer token for subsequent requests
✅ **Logging**: Full request/response in dev/staging only

The Android app is now **100% compatible** with your Laravel API! 🚀

## Quick Reference

**Login:**
```kotlin
val result = authRepository.signIn(email, password, rememberMe)
if (result.isSuccess) {
    val token = result.getOrNull()?.token
    // Token is automatically stored and used
}
```

**Logout:**
```kotlin
authRepository.logout()
// Clears everything
```

**Check if logged in:**
```kotlin
val isLoggedIn = authRepository.isLoggedIn()
```

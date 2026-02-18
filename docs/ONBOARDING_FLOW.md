# Post-Registration Onboarding Flow

## Overview

New users who sign up must complete phone verification and company setup before accessing the app. The flow verifies user identity first (password + phone), then handles company creation or joining.

## Screen Order

```
1. Email Check
2. Sign Up (password)
3. Phone Verification
4. SMS Code Verify
5. Account Type Selection
6. Final Details
7. Home
```

## Detailed Flow

### 1. Email Check (`EmailCheckFragment`)
- User enters email address
- App checks if email is registered via `POST /api/auth/check-email`
- **Registered** -> Sign In screen
- **Not registered** -> Sign Up screen
- Also offers Google OAuth sign-in

### 2. Sign Up (`SignUpFragment`)
- User enters and confirms password (minimum 6 characters)
- Real-time password match feedback (green/red text)
- On success:
  - If user already has a company -> skip onboarding, go to Home
  - If no company -> proceed to Phone Verification

### 3. Phone Verification (`PhoneVerificationFragment`)
- Country code dropdown (50 countries with emoji flags)
- Phone number input
- Client-side validation: non-empty, at least 7 digits
- reCAPTCHA verification via SafetyNet before sending SMS
- Calls `POST /api/auth/sms-send-verification`

### 4. SMS Code Verify (`SmsCodeVerifyFragment`)
- 4-digit code input
- Auto-submits when 4th digit entered
- 120-second resend cooldown timer
- Resend requires fresh reCAPTCHA token
- Calls `POST /api/auth/sms-verify-code`

### 5. Account Type Selection (`AccountTypeFragment`)
- Two options:
  - **"Create a Company"** -> Final Details (with company name field)
  - **"Join a Company"** -> Join Company info screen

### 5b. Join Company (`JoinCompanyFragment`)
- Informational screen: "You'll need an invite link from an existing company"
- **"Create a Company Instead"** button -> Final Details (with company name field)
- Back button returns to Account Type

### 6. Final Details (`FinalDetailsFragment`)
- First name and last name (required)
- Company name (required, only shown when creating a company)
- On submit:
  1. `PUT /api/users/{userId}` - update user profile (name, phone, country code)
  2. `POST /api/companies` - create company (if creating)
  3. Refresh user context to pick up new company association
  4. Verify company ID is set
  5. Trigger initial sync
  6. Navigate to Home

### 7. Home
- App loads with full navigation (projects, map, notifications, etc.)

## App Restart Handling

If the app restarts and the user is authenticated but has no company (`companyId == null && userId > 0`), `MainActivity.checkAuthenticationStatus()` redirects to the Phone Verification screen to resume onboarding.

## Navigation Arguments Passed Through Flow

| Argument | Type | Source | Used By |
|----------|------|--------|---------|
| `userId` | Long | Sign Up response | All onboarding screens |
| `email` | String | Email Check input | All onboarding screens |
| `phone` | String | Phone Verification input | SMS Verify, Account Type, Final Details |
| `countryCode` | String | Country dropdown selection | SMS Verify, Account Type, Final Details |
| `isCreating` | Boolean | Account Type selection | Final Details only |

## API Endpoints

| Endpoint | Method | Used By |
|----------|--------|---------|
| `/api/auth/check-email` | POST | Email Check |
| `/api/auth/register` | POST | Sign Up |
| `/api/auth/sms-send-verification` | POST | Phone Verification, SMS resend |
| `/api/auth/sms-verify-code` | POST | SMS Code Verify |
| `/api/users/{userId}` | PUT | Final Details |
| `/api/companies` | POST | Final Details (create company) |

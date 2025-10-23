 Google Authentication Flow in Mongoose Backend

  The backend implements OAuth 2.0 authentication for Google using Laravel Socialite. Here's how it works:

  1. Configuration (config/services.php:33-37)

  Google OAuth credentials are stored in environment variables:
  'google' => [
      'client_id' => env('GOOGLE_CLIENT_ID'),
      'client_secret' => env('GOOGLE_CLIENT_SECRET'),
      'redirect' => env('GOOGLE_REDIRECT_URI'),
  ]

  2. Authentication Routes (routes/web.php:21-23)

  Two main routes handle the OAuth flow:
  - Redirect Route: /oauth2/redirect/google - Initiates the OAuth flow
  - Callback Route: /oauth2/google/callback - Handles Google's response (supports both GET and POST)

  3. OAuth Flow Process

  Step 1: User Initiates Login (SocialiteController::redirect())

  When a user wants to log in with Google:

  Client Request → GET /oauth2/redirect/google?schema=rocketplan-dev
                                                ↓
                      SocialiteService redirects user to Google OAuth
                                                ↓
             https://accounts.google.com/o/oauth2/auth?client_id=...&redirect_uri=...

  The schema parameter is optional and used for mobile apps to receive the auth token via deep link.

  Step 2: User Authorizes with Google

  User logs into Google and authorizes the Mongoose app to access their profile information (email, name).

  Step 3: Google Redirects Back (SocialiteController::callback())

  Google redirects back to: /oauth2/google/callback?code=OAUTH_CODE&schema=rocketplan-dev

  The SocialiteService processes this callback:

  app/Services/SocialiteService.php:56-73
  public function handleCallback(): View|RedirectResponse
  {
      // Get user data from Google
      $oauthUser = Socialite::driver($this->provider)->user();

      // Find or create local user
      $localUser = $this->retrieveLocalUser($oauthUser);

      // Authenticate the user
      $authResult = $this->handleAuthentication($localUser);

      return $this->response($authResult, Response::HTTP_OK);
  }

  4. User Creation/Retrieval (SocialiteService.php:96-118)

  The system finds an existing user by email or creates a new one:

  $user = User::firstOrCreate([
      'email' => $oauthUser->getEmail(),
  ], [
      'password' => Hash::make(Str::uuid()),  // Random password
      'first_name' => $firstName,
      'last_name' => $lastName,
      'email_verified_at' => Carbon::now(),   // Auto-verified
      'google_id' => $oauthUser->getId(),     // Store Google ID
  ]);

  Key points:
  - Users are matched by email address
  - Email is automatically verified (no confirmation needed)
  - A random password is generated (user can't use password login initially)
  - Google ID is stored for future OAuth logins

  5. Authentication Response (SocialiteService.php:79-90)

  The authentication response differs based on client type:

  Mobile Apps (with schema parameter):

  // Returns API token via deep link redirect
  return redirect()->away("rocketplan-dev://?token=abc123&status=200");

  The mobile app catches this deep link and stores the token.

  Web Apps (without schema parameter):

  // Creates authenticated session
  auth()->login($localUser);

  // Returns postMessage view that communicates with parent window
  return view('app.oauth.response', ['body' => [], 'status' => 200]);

  The web view uses window.postMessage() to send the authentication result to the parent window.

  6. API Token Creation (CreateAuthToken action)

  For mobile apps, a Laravel Sanctum token is generated:
  CreateAuthToken::make()->handle($localUser)->plainTextToken

  This token is used for subsequent API requests with the Authorization: Bearer {token} header.

  7. Multi-Provider Support

  The same flow works for:
  - Google: https://accounts.google.com/o/oauth2/auth
  - Facebook: https://www.facebook.com/v3.3/dialog/oauth
  - Apple: https://appleid.apple.com/auth/authorize

  All three providers use the same SocialiteController and SocialiteService.

  Security Features

  1. No password storage: OAuth users get random passwords
  2. Email auto-verification: OAuth-authenticated emails are trusted
  3. Google ID tracking: Prevents duplicate accounts
  4. Deep link validation: Schema parameter is validated
  5. Error handling: Failed OAuth attempts are caught and reported

  Example Flow Diagram

  Mobile App                    Backend                         Google
      |                            |                               |
      |-- GET /oauth2/redirect/google?schema=rocketplan-dev ----->|
      |                            |                               |
      |                            |--- Redirect to Google ------->|
      |                            |                               |
      |<-------------------------- Login form ---------------------|
      |                            |                               |
      |--- Enter credentials ------------------------------------->|
      |                            |                               |
      |                            |<-- Redirect with code --------|
      |                            |                               |
      |                            |-- Exchange code for user ---->|
      |                            |                               |
      |                            |<-- User profile data ---------|
      |                            |                               |
      |                            | Find/Create User              |
      |                            | Generate API Token            |
      |                            |                               |
      |<-- rocketplan-dev://?token=abc123&status=200 --------------|
      |                            |                               |
      | Store token & make authenticated API requests              |

  That's the complete Google authentication flow! The system uses industry-standard OAuth 2.0 with Laravel Socialite, supports both web and mobile clients, and
  seamlessly creates or matches users based on their Google email.
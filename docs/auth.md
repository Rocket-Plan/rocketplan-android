  Looking at your GOOGLE_OAUTH_FLOW.md:137-142, you removed these dependencies:

  **Removed dependencies** (no longer needed):
  - ❌ `google-play-services-auth`
  - ❌ `androidx-credentials`
  - ❌ `androidx-credentials-play-services`
  - ❌ `google-googleid`

  Native Android Option: Credential Manager API

  Android's Credential Manager API provides a native bottom-sheet experience for account selection - no browser needed. It looks like this:

  1. User taps "Sign in with Google"
  2. Native Android bottom sheet appears with available Google accounts
  3. User taps their account
  4. Done - no browser redirect

  Why You Switched Away From It

  From your comparison table (GOOGLE_OAUTH_FLOW.md:286-295):

  | Aspect               | Native (Credential Manager) | Current (Browser OAuth) |
  |----------------------|-----------------------------|-------------------------|
  | Client Configuration | Android OAuth client needed | ❌ Not needed            |
  | Dependencies         | 4 Google libraries          | 1 browser library       |
  | Token Flow           | ID token → Backend          | Backend → JWT token     |
  | iOS Consistency      | ❌ Different flow            | ✅ Same flow             |
  | Security             | Client has OAuth config     | ✅ Backend-only config   |

  You chose consistency with iOS - since iOS must use ASWebAuthenticationSession (browser-based), you matched that on Android.

  Should You Switch Back?

  Pros of native Credential Manager:
  - ✅ Better UX (no browser opening)
  - ✅ Feels more native
  - ✅ Faster flow
  - ✅ Google's recommended approach for Android

  Cons:
  - ❌ Different from iOS implementation
  - ❌ Requires Android OAuth client credentials
  - ❌ More complex setup (backend needs to validate Google ID tokens separately)
  - ❌ Platform-specific code

  My take: If user experience is more important than code consistency, switch back to Credential Manager. If cross-platform uniformity matters more, keep the browser flow.
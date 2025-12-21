RocketPlan_android Code Review

Scope
- Repository-wide scan with focus on auth, storage, offline DB, and caching paths.

Findings
1) MEDIUM: Auth token logged to stdout during sign-in.
   - `app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt:166-169`
   - Impact: Token fragments appear in logcat on dev/staging; logs can leak via bug reports or shared devices.
   - Fix: Remove token logging or gate behind a build-time flag that never ships; log only non-sensitive metadata.

2) MEDIUM: Auth tokens stored unencrypted in DataStore.
   - `app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt:48-99`
   - Impact: Tokens are readable from app sandbox on rooted devices or during device backups.
   - Fix: Store auth tokens in encrypted storage (EncryptedSharedPreferences or encrypted DataStore).

3) MEDIUM: Offline database uses destructive migration fallback.
   - `app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt:99-103`
   - Impact: Any schema mismatch can wipe offline data; risky for an offline-first app.
   - Fix: Remove `fallbackToDestructiveMigration()` or gate it to debug builds only; add missing migrations instead.

4) LOW (conditional): Photo cache fetches remote URLs without auth headers.
   - `app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt:23-58`
   - Impact: If `remoteUrl` requires Authorization, caching fails and offline photos won’t be available.
   - Fix: Confirm URLs are signed/public; otherwise inject auth headers via a shared OkHttp client.

Testing
- Not run in this review.

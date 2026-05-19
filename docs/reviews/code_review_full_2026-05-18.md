# Code Review: Full Codebase Review

**Reviewer:** Claude Code
**Date:** 2026-05-18 11:29:44
**Build:** Development (uncommitted changes present)

## Summary

Comprehensive review of 150+ Kotlin source files. 23 bugs already tracked in `docs/BUG_TRACKER.md`. Found 4 new issues not yet tracked (see below).

---

## Findings

### New Bugs Found

| ID | Severity | Title | Classification |
|----|----------|-------|----------------|
| `RP-BUG-024` | P2 | Pusher `throttledErrorTimestamps` Unbounded Growth | pre_existing_latent |
| `RP-BUG-025` | P2 | LocalDataService.currentCompanyId Throws If Accessed Before Login | pre_existing_latent |
| `RP-BUG-026` | P2 | SecureStorage.clearAll() Doesn't Clear migrationDeferred | pre_existing_latent |
| `RP-BUG-027` | P3 | Room SyncStatus Enum Comparisons Use String Parsing | pre_existing_latent |

### New Findings Detail

#### RP-BUG-024: Pusher `throttledErrorTimestamps` Unbounded Growth
**File:** `realtime/PusherService.kt:48`  
**Severity:** P2  
**Classification:** pre_existing_latent  

```kotlin
private val throttledErrorTimestamps = ConcurrentHashMap<String, Long>()
```

The throttling cache in `shouldThrottleRemoteLog()` (line 524-543) grows unbounded within the 60-second cutoff window. With `MAX_THROTTLE_CACHE_SIZE = 128` as additional bound, but entries are only removed when size exceeds this AND the entry is older than cutoff. Under heavy error activity with diverse errors, this could still be large.

---

#### RP-BUG-025: LocalDataService.currentCompanyId Throws If Accessed Before Login
**File:** `data/local/LocalDataService.kt:81-83`  
**Severity:** P2  
**Classification:** pre_existing_latent  

```kotlin
val currentCompanyId: Long
    get() = _currentCompanyId
        ?: throw IllegalStateException("currentCompanyId not set...")
```

Callers like `SyncQueueManager.processPendingOperations()` use `localDataService.currentCompanyId` without catching the exception. If called before login completes, this crashes. Mitigated if `ProcessPendingOperations` doesn't actually need companyId - but the pattern is fragile.

---

#### RP-BUG-026: SecureStorage.clearAll() Doesn't Clear migrationDeferred
**File:** `data/storage/SecureStorage.kt:310-325`  
**Severity:** P2  
**Classification:** pre_existing_latent  

```kotlin
suspend fun clearAll() {
    context.dataStore.edit { preferences -> ... }
    encryptedPrefs.edit().clear().apply()
    authTokenState.value = null
}
```

After logout, `clearAll()` is called but `migrationDeferred` (created in init) still holds reference to the old migration task. If user logs in again quickly, old migration could complete AFTER new login, potentially overwriting new token. `compareAndSet(null, legacyToken)` at line 345 mitigates this. Risk: Low.

---

#### RP-BUG-027: Room SyncStatus Enum Comparisons Use String Parsing
**File:** `data/local/SyncStatus.kt`  
**Severity:** P3  
**Classification:** pre_existing_latent  

`SyncStatus.fromValue()` parses strings. If a new status value is added to database but enum doesn't have corresponding entry, silent failures could occur. Fragile during schema evolution but not currently causing issues.

---

### Previously Tracked Bugs (Verified)

| ID | Status | Notes |
|----|--------|-------|
| RP-BUG-001 | planned | Destructive Migration Enabled in Production |
| RP-BUG-002 | open | printStackTrace() Leaks Sensitive Data |
| RP-BUG-003 | open | PriorityQueue Thread-Safety Violation - mutex protection appears correct |
| RP-BUG-004 | open | Silent Fallback When ConnectivityManager Unavailable |
| RP-BUG-005 | open | Non-Blocking Write to EncryptedSharedPreferences |
| RP-BUG-006 | planned | Race Condition in Auth Token Migration |
| RP-BUG-007 | open | Unshared OkHttpClient Configuration |
| RP-BUG-008 | open | Sync Checkpoints Stored in Cleartext |
| RP-BUG-009 | open | Bitmap Memory Leak |
| RP-BUG-010 | open | Unbounded Photo Sync Job Blocking |
| RP-BUG-011 | open | Certificate Pinning Not Implemented |
| RP-BUG-012 | open | Untracked Coroutine Scope in Application Startup |
| RP-BUG-013 | open | Swallowed Exception in RetryWorker |
| RP-BUG-014 | open | Realm-like ID Pattern Fragile Migration |
| RP-BUG-015 | open | Debounce May Not Prevent Rapid Re-enqueues |
| RP-BUG-016 | open | Missing Migration 27_28 |
| RP-BUG-017 | open | Shutdown Doesn't Wait for In-Flight Requests |
| RP-BUG-018 | open | Session Object in Debug Logging |
| RP-BUG-019 | open | Potential ConcurrentModificationException |
| RP-BUG-020 | open | Error Handling Swallows Failures |
| RP-BUG-021 | open | Hardcoded Magic Numbers |
| RP-BUG-022 | open | Inefficient LRU Calculation |
| RP-BUG-023 | open | TODO Comments for Incomplete Features |

---

## Architectural Compliance

**Verified Compliant:**
- Offline-first with Room (SyncStatus, isDirty, isDeleted tracking)
- Coroutines + Flow (viewModelScope, withContext(Dispatchers.IO))
- Mutex protection for SyncQueueManager queue operations
- Soft-delete pattern
- Encrypted storage for tokens/passwords

**Minor Observations:**
- Double FLAG_KEEP_SCREEN_ON set in MainActivity.kt (harmless)
- Inconsistent singleton pattern (some use companion object, others constructor DI)
- Mixed error handling approaches (Result<T>, runCatching, direct throws)

---

## Uncommitted Changes (as of review)

```
M AGENTS.md
M CLAUDE.md
M app/build.gradle.kts
M app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt
M app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt
M docs/ARCHITECTURE.md
R  docs/ROCKETPLAN_IOS_OFFLINE_ARCHITECTURE.md -> docs/reference/ROCKETPLAN_IOS_OFFLINE_ARCHITECTURE.md
```

---

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Claude Code | 2026-05-18 |

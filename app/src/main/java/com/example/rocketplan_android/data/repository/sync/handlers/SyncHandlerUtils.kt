package com.example.rocketplan_android.data.repository.sync.handlers

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException
import retrofit2.Response

internal const val SYNC_TAG = "API"

/**
 * RP-BUG-040: run an optimistic-locked delete and map the result, recovering from a stale-timestamp
 * 409. Delete endpoints return `Response<Unit>` (Retrofit does NOT throw on HTTP errors), so every
 * delete handler must inspect the response — several previously ignored it and silently treated a
 * 409/422/5xx as a successful delete.
 *
 * Behaviour:
 *  - call `delete(lockUpdatedAt)`;
 *  - if it returns 409 (the local `updated_at` is stale because the row was modified server-side) and
 *    a lock was sent, retry ONCE with `null` — the backend skips the staleness check when `updated_at`
 *    is null (`HandlesOptimisticLocking.assertNotStale`), so the user's delete wins;
 *  - success or 404/410 (already gone) → return null (caller finalizes the local delete);
 *  - 422 → return `OperationOutcome.DROP`;
 *  - any other non-2xx → throw `HttpException` so the queue records the failure and retries.
 *
 * @return null when the delete is effectively done (caller should finalize), or a terminal
 *   [OperationOutcome] to return as-is.
 */
internal suspend fun resolveDeleteWithStaleRetry(
    lockUpdatedAt: String?,
    delete: suspend (updatedAt: String?) -> Response<Unit>,
): OperationOutcome? {
    var response = delete(lockUpdatedAt)
    if (response.code() == 409 && lockUpdatedAt != null) {
        response = delete(null)
    }
    if (response.isSuccessful || response.code() in listOf(404, 410)) return null
    if (response.code() == 422) return OperationOutcome.DROP
    throw HttpException(response)
}

/**
 * RP-BUG-040 variant for delete endpoints whose Retrofit method returns `Unit` (so Retrofit throws an
 * `HttpException` on a non-2xx response), e.g. `deleteRoom` / `deleteLocation` / `deleteProperty`. Same
 * recovery as [resolveDeleteWithStaleRetry]: on a stale-timestamp 409, retry once without the lock.
 */
internal suspend fun resolveDeleteThrowingWithStaleRetry(
    lockUpdatedAt: String?,
    delete: suspend (updatedAt: String?) -> Unit,
): OperationOutcome? {
    try {
        delete(lockUpdatedAt)
        return null
    } catch (error: HttpException) {
        if (error.code() == 409 && lockUpdatedAt != null) {
            return try {
                delete(null)
                null
            } catch (retryError: HttpException) {
                mapDeleteHttpError(retryError)
            }
        }
        return mapDeleteHttpError(error)
    }
}

private fun mapDeleteHttpError(error: HttpException): OperationOutcome? = when {
    error.code() in listOf(404, 410) -> null
    error.code() == 422 -> OperationOutcome.DROP
    else -> throw error
}

internal fun Throwable.isConflict(): Boolean = (this as? HttpException)?.code() == 409

internal fun Throwable.isMissingOnServer(): Boolean = (this as? HttpException)?.code() in listOf(404, 410)

internal fun Throwable.isValidationError(): Boolean = (this as? HttpException)?.code() == 422

/**
 * Attempts to extract the `updated_at` timestamp from a 409 error response body.
 * Useful for entities that lack a direct GET-by-ID endpoint (notes, equipment, etc.).
 * Tries top-level `updated_at`, then nested `data.updated_at`.
 */
internal fun HttpException.extractUpdatedAt(gson: Gson): String? {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull() ?: return null
    return runCatching {
        val json = gson.fromJson(body, JsonObject::class.java)
        json.get("updated_at")?.asString
            ?: json.getAsJsonObject("data")?.get("updated_at")?.asString
    }.getOrNull()
}

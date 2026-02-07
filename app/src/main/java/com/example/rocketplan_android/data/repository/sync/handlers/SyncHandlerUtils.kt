package com.example.rocketplan_android.data.repository.sync.handlers

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.HttpException

internal const val SYNC_TAG = "API"

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

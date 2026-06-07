package com.example.rocketplan_android.data.repository.sync.handlers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * RP-BUG-040: the shared delete-409 recovery used by every delete handler. Delete endpoints return
 * Response<Unit> (Retrofit does not throw on HTTP errors), so the handler must inspect the response;
 * a stale-timestamp 409 is recovered by retrying once without the lock.
 */
class ResolveDeleteWithStaleRetryTest {

    private fun resp(code: Int): Response<Unit> =
        if (code in 200..299) Response.success(Unit)
        else Response.error(code, "".toResponseBody("text/plain".toMediaType()))

    @Test
    fun `success deletes once and finalizes`() = runTest {
        var calls = 0
        val outcome = resolveDeleteWithStaleRetry("T1") { calls++; resp(204) }
        assertThat(outcome).isNull() // null = caller finalizes the delete
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `stale 409 retries once without the lock and succeeds`() = runTest {
        val sent = mutableListOf<String?>()
        val outcome = resolveDeleteWithStaleRetry("T1") { ts ->
            sent.add(ts)
            if (ts != null) resp(409) else resp(204)
        }
        assertThat(outcome).isNull()
        // First with the stale lock, then retried with null (backend skips the staleness check).
        assertThat(sent).containsExactly("T1", null).inOrder()
    }

    @Test
    fun `404 already gone finalizes the delete`() = runTest {
        val outcome = resolveDeleteWithStaleRetry("T1") { resp(404) }
        assertThat(outcome).isNull()
    }

    @Test
    fun `422 validation error drops`() = runTest {
        val outcome = resolveDeleteWithStaleRetry("T1") { resp(422) }
        assertThat(outcome).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `500 throws so the queue retries`() = runTest {
        var threw = false
        try {
            resolveDeleteWithStaleRetry("T1") { resp(500) }
        } catch (e: HttpException) {
            threw = true
            assertThat(e.code()).isEqualTo(500)
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `409 with no lock cannot recover and throws (no retry)`() = runTest {
        var calls = 0
        var threw = false
        try {
            resolveDeleteWithStaleRetry(null) { calls++; resp(409) }
        } catch (e: HttpException) {
            threw = true
        }
        assertThat(threw).isTrue()
        assertThat(calls).isEqualTo(1) // no null-retry when there was no lock to drop
    }
}

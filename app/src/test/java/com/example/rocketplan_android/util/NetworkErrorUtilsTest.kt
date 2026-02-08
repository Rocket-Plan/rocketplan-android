package com.example.rocketplan_android.util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetworkErrorUtilsTest {

    @Test
    fun `getHttpStatusCode returns code for HttpException`() {
        val response = Response.error<Any>(404, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        val code = NetworkErrorUtils.getHttpStatusCode(error)

        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `getHttpStatusCode returns null for non-HTTP exceptions`() {
        val error = IOException("Network failure")

        val code = NetworkErrorUtils.getHttpStatusCode(error)

        assertThat(code).isNull()
    }

    @Test
    fun `isClientError returns true for 4xx errors`() {
        val response = Response.error<Any>(400, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isClientError(error)).isTrue()
    }

    @Test
    fun `isClientError returns true for 404`() {
        val response = Response.error<Any>(404, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isClientError(error)).isTrue()
    }

    @Test
    fun `isClientError returns false for 5xx errors`() {
        val response = Response.error<Any>(500, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isClientError(error)).isFalse()
    }

    @Test
    fun `isServerError returns true for 5xx errors`() {
        val response = Response.error<Any>(500, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isServerError(error)).isTrue()
    }

    @Test
    fun `isServerError returns true for 503`() {
        val response = Response.error<Any>(503, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isServerError(error)).isTrue()
    }

    @Test
    fun `isServerError returns false for 4xx errors`() {
        val response = Response.error<Any>(401, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isServerError(error)).isFalse()
    }

    @Test
    fun `isRetryable returns true for SocketTimeoutException`() {
        val error = SocketTimeoutException("Connection timed out")

        assertThat(NetworkErrorUtils.isRetryable(error)).isTrue()
    }

    @Test
    fun `isRetryable returns true for UnknownHostException`() {
        val error = UnknownHostException("Unable to resolve host")

        assertThat(NetworkErrorUtils.isRetryable(error)).isTrue()
    }

    @Test
    fun `isRetryable returns true for generic IOException`() {
        val error = IOException("Connection reset")

        assertThat(NetworkErrorUtils.isRetryable(error)).isTrue()
    }

    @Test
    fun `isRetryable returns true for server errors`() {
        val response = Response.error<Any>(502, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isRetryable(error)).isTrue()
    }

    @Test
    fun `isRetryable returns false for client errors`() {
        val response = Response.error<Any>(400, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isRetryable(error)).isFalse()
    }

    @Test
    fun `isNetworkError returns true for UnknownHostException`() {
        val error = UnknownHostException("No internet")

        assertThat(NetworkErrorUtils.isNetworkError(error)).isTrue()
    }

    @Test
    fun `isNetworkError returns true for SocketTimeoutException`() {
        val error = SocketTimeoutException("Timeout")

        assertThat(NetworkErrorUtils.isNetworkError(error)).isTrue()
    }

    @Test
    fun `isNetworkError returns false for HttpException`() {
        val response = Response.error<Any>(500, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        assertThat(NetworkErrorUtils.isNetworkError(error)).isFalse()
    }

    @Test
    fun `getErrorMessage returns user friendly message for timeout`() {
        val error = SocketTimeoutException("Read timed out")

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("timed out")
    }

    @Test
    fun `getErrorMessage returns user friendly message for no network`() {
        val error = UnknownHostException("api.example.com")

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("reach server")
    }

    @Test
    fun `getErrorMessage returns appropriate message for 401`() {
        val response = Response.error<Any>(401, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("Authentication")
    }

    @Test
    fun `getErrorMessage returns appropriate message for 404`() {
        val response = Response.error<Any>(404, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("Not found")
    }

    @Test
    fun `getErrorMessage returns appropriate message for 409`() {
        val response = Response.error<Any>(409, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("Conflict")
    }

    @Test
    fun `getErrorMessage includes error body when present`() {
        val errorBody = """{"error": "Invalid input"}"""
        val response = Response.error<Any>(
            400,
            errorBody.toResponseBody("application/json".toMediaType())
        )
        val error = HttpException(response)

        val message = NetworkErrorUtils.getErrorMessage(error)

        assertThat(message).contains("Invalid input")
    }

    @Test
    fun `toDetailedErrorString includes exception type and message`() {
        val error = IOException("Connection reset")

        val detailed = error.toDetailedErrorString()

        assertThat(detailed).contains("IOException")
        assertThat(detailed).contains("Connection reset")
    }

    @Test
    fun `toDetailedErrorString includes HTTP code for HttpException`() {
        val response = Response.error<Any>(503, "".toResponseBody("application/json".toMediaType()))
        val error = HttpException(response)

        val detailed = error.toDetailedErrorString()

        assertThat(detailed).contains("HttpException")
        assertThat(detailed).contains("503")
    }
}

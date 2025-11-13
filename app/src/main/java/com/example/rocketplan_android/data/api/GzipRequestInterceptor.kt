package com.example.rocketplan_android.data.api

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.GzipSink
import okio.buffer

/**
 * Compresses request bodies when the caller opts in by setting `Content-Encoding: gzip`.
 * This keeps compression scoped to selected endpoints (e.g., remote logging) without
 * impacting other API requests.
 */
class GzipRequestInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val encoding = request.header(CONTENT_ENCODING_HEADER)
        if (encoding?.equals(GZIP, ignoreCase = true) != true) {
            return chain.proceed(request)
        }

        val compressedBody = object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = -1 // Length unknown until compressed.
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                val gzipSink = GzipSink(sink).buffer()
                body.writeTo(gzipSink)
                gzipSink.close()
            }
        }

        val compressedRequest = request.newBuilder()
            .method(request.method, compressedBody)
            .build()

        return chain.proceed(compressedRequest)
    }

    companion object {
        private const val CONTENT_ENCODING_HEADER = "Content-Encoding"
        private const val GZIP = "gzip"
    }
}

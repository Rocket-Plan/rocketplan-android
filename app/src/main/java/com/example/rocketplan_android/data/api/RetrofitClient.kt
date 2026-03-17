package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.config.AppConfig
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Singleton Retrofit client for API communication
 * Configured with logging, timeouts, and authentication
 */
object RetrofitClient {

    private val authToken: AtomicReference<String?> = AtomicReference(null)
    private val companyId: AtomicReference<Long?> = AtomicReference(null)

    /**
     * Listener invoked on the first 401 response when a token is set.
     * Used by MainActivity to trigger forced sign-out.
     */
    var onUnauthorized: (() -> Unit)? = null

    /**
     * Set the authentication token for API requests
     */
    fun setAuthToken(token: String?) {
        authToken.set(token)
    }

    /**
     * Get the authentication token
     */
    fun getAuthToken(): String? = authToken.get()

    /**
     * Set the company ID for API requests.
     * This is sent as X-Company-Id header on all authenticated requests.
     */
    fun setCompanyId(id: Long?) {
        companyId.set(id)
    }

    /**
     * Get the current company ID
     */
    fun getCompanyId(): Long? = companyId.get()

    /**
     * Logging interceptor for debugging (only enabled in dev/staging)
     */
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        redactHeader("Authorization")
        redactHeader("x-api-key")
        redactHeader("Cookie")
        level = if (AppConfig.isLoggingEnabled) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /**
     * Auth interceptor to add Bearer token, Company ID, and User-Agent to requests
     */
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val requestBuilder = original.newBuilder()

        // Add auth token if available
        val token = authToken.get()
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")

            // Only add company ID header when authenticated
            companyId.get()?.let { id ->
                requestBuilder.addHeader("X-Company-Id", id.toString())
            }
        }

        // Add common headers
        if (original.header("Content-Type") == null) {
            requestBuilder.addHeader("Content-Type", "application/json")
        }
        requestBuilder.addHeader("Accept", "application/json")

        // Add User-Agent for Laravel token naming
        // Format: "RocketPlan Android/1.0 (Android SDK_INT; Model)"
        val userAgent = buildUserAgent()
        requestBuilder.addHeader("User-Agent", userAgent)

        chain.proceed(requestBuilder.build())
    }

    /**
     * Build User-Agent string for API requests
     * Used by Laravel to name Sanctum tokens
     */
    private fun buildUserAgent(): String {
        val versionName = AppConfig.versionName
        val androidVersion = android.os.Build.VERSION.SDK_INT
        val deviceModel = android.os.Build.MODEL
        return "RocketPlan Android/$versionName (Android $androidVersion; $deviceModel)"
    }

    // TODO: Add real certificate pins before production release.
    // To generate certificate hashes, run:
    // openssl s_client -servername <hostname> -connect <hostname>:443 | \
    //   openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | \
    //   openssl dgst -sha256 -binary | openssl enc -base64
    // Pin both the leaf certificate and at least one backup (intermediate CA)
    // to avoid lockout during certificate rotation.
    private val certificatePinner: CertificatePinner? = null

    /**
     * OkHttp client with interceptors, timeouts, and certificate pinning
     */
    /** OkHttp client without auth interceptor — for downloading external URLs (e.g. S3). */
    val plainHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
            .readTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Interceptor that detects 401 Unauthorized responses and triggers forced sign-out.
     * Only fires for authenticated requests (token was set) and skips auth endpoints.
     */
    private val unauthorizedInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 401 && authToken.get() != null) {
            val path = chain.request().url.encodedPath
            // Don't trigger on login/auth endpoints — 401 there means wrong credentials
            if (!path.contains("/auth/login") && !path.contains("/auth/google")) {
                android.util.Log.w("RetrofitClient", "401 Unauthorized on $path — token may be revoked")
                onUnauthorized?.invoke()
            }
        }
        response
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .addInterceptor(GzipRequestInterceptor())
        .addInterceptor(unauthorizedInterceptor)
        .apply { certificatePinner?.let { certificatePinner(it) } }
        .connectTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
        .readTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
        .writeTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
        .build()

    /**
     * Gson converter with custom configurations
     */
    private val gson = GsonBuilder()
        .setLenient()
        .create()

    /**
     * Main Retrofit instance
     */
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.apiBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    /**
     * Create API service
     */
    inline fun <reified T> createService(): T {
        return retrofit.create(T::class.java)
    }

    /**
     * Auth service instance
     */
    val authService: AuthService by lazy {
        createService<AuthService>()
    }

    val loggingService: LoggingService by lazy {
        createService<LoggingService>()
    }

    val imageProcessorService: ImageProcessorService by lazy {
        createService<ImageProcessorService>()
    }

    val imageProcessorApi: ImageProcessorApi by lazy {
        createService<ImageProcessorApi>()
    }

    val companyApi: CompanyApi by lazy {
        createService<CompanyApi>()
    }

}

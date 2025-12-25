package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.config.AppConfig
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        val requestBuilder = chain.request().newBuilder()

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
        requestBuilder.addHeader("Content-Type", "application/json")
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

    /**
     * OkHttp client with interceptors and timeouts
     */
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .addInterceptor(GzipRequestInterceptor())
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
}

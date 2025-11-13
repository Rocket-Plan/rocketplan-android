package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.config.AppConfig
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit client for API communication
 * Configured with logging, timeouts, and authentication
 */
object RetrofitClient {

    private var authToken: String? = null

    /**
     * Set the authentication token for API requests
     */
    fun setAuthToken(token: String?) {
        authToken = token
    }

    /**
     * Get the authentication token
     */
    fun getAuthToken(): String? = authToken

    /**
     * Logging interceptor for debugging (only enabled in dev/staging)
     */
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (AppConfig.isLoggingEnabled) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    /**
     * Auth interceptor to add Bearer token and User-Agent to requests
     */
    private val authInterceptor = Interceptor { chain ->
        val requestBuilder = chain.request().newBuilder()

        // Add auth token if available
        authToken?.let { token ->
            requestBuilder.addHeader("Authorization", "Bearer $token")
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

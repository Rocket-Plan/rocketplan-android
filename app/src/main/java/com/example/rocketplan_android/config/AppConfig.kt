package com.example.rocketplan_android.config

import com.example.rocketplan_android.BuildConfig

/**
 * Application configuration based on build variant.
 * Access environment-specific settings through BuildConfig fields.
 */
object AppConfig {

    /**
     * Base URL for API endpoints
     * - DEV: https://api-qa-mongoose-br2wu78v1.rocketplantech.com
     * - STAGING: https://api-staging-mongoose-n5tr2spgf.rocketplantech.com
     * - PROD: https://api-public.rocketplantech.com
     */
    val apiBaseUrl: String = BuildConfig.API_BASE_URL

    /**
     * Current environment name (DEV, TEST, PROD)
     */
    val environment: String = BuildConfig.ENVIRONMENT

    /**
     * Whether logging is enabled for this build
     */
    val isLoggingEnabled: Boolean = BuildConfig.ENABLE_LOGGING

    /**
     * Check if running in development mode
     */
    val isDevelopment: Boolean = BuildConfig.DEBUG && environment == "DEV"

    /**
     * Check if running in staging/test mode
     */
    val isStaging: Boolean = environment == "STAGING"

    /**
     * Check if running in production mode
     */
    val isProduction: Boolean = environment == "PROD"

    /**
     * Application ID (includes environment suffix for dev/test)
     */
    val applicationId: String = BuildConfig.APPLICATION_ID

    /**
     * Build version name
     */
    val versionName: String = BuildConfig.VERSION_NAME

    /**
     * Build version code
     */
    val versionCode: Int = BuildConfig.VERSION_CODE

    /**
     * Sentry DSN for the current environment (blank disables reporting)
     */
    val sentryDsn: String = BuildConfig.SENTRY_DSN

    /**
     * Flag to enable crash reporting when a DSN is provided
     */
    val isSentryEnabled: Boolean = BuildConfig.SENTRY_ENABLED

    // Add more environment-specific configuration as needed
    // Examples:

    /**
     * API timeout in seconds
     */
    val apiTimeout: Long = if (isProduction) 30 else 60

    /**
     * Enable crash reporting
     */
    val isCrashReportingEnabled: Boolean = isSentryEnabled

    /**
     * Enable analytics
     */
    val isAnalyticsEnabled: Boolean = isProduction || isStaging

    /**
     * Feature flag: enable RocketDry surfaces in the UI.
     * Controlled via BuildConfig so it can be toggled per flavor.
     */
    val isRocketDryEnabled: Boolean = BuildConfig.ENABLE_ROCKET_DRY

    /**
     * Minimum interval between foreground sync checks (milliseconds).
     * Prevents hammering the server on rapid app switches.
     */
    const val FOREGROUND_SYNC_THRESHOLD_MS: Long = 60 * 1000L // 1 minute

    /**
     * Print current configuration to logs
     */
    fun logConfiguration() {
        if (isLoggingEnabled) {
            println("=== RocketPlan Configuration ===")
            println("Environment: $environment")
            println("API Base URL: $apiBaseUrl")
            println("Version: $versionName ($versionCode)")
            println("Application ID: $applicationId")
            println("Logging Enabled: $isLoggingEnabled")
            println("Debug Build: ${BuildConfig.DEBUG}")
            println("==============================")
        }
    }
}

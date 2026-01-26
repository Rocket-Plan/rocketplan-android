package com.example.rocketplan_android.data.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Connectivity state combining network interface availability and backend health.
 */
enum class ConnectivityState {
    /** Initial state, connectivity not yet determined */
    UNKNOWN,
    /** Device has no network interface available */
    OFFLINE,
    /** Network interface available, checking backend health */
    ONLINE_CHECKING,
    /** Network available and backend is healthy */
    ONLINE_HEALTHY,
    /** Network interface available but backend is unreachable */
    ONLINE_BACKEND_DOWN
}

/**
 * Dual-layer connectivity service that combines:
 * 1. Network interface check (ConnectivityManager - fast, local)
 * 2. Backend health check (HTTP request - confirms server reachability)
 *
 * This prevents sync attempts when the device has network but the backend
 * is unreachable (e.g., server down, DNS issues, firewall blocks).
 */
class DualLayerConnectivityService(
    private val connectivityManager: ConnectivityManager?,
    private val healthCheckService: BackendHealthCheckService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var retryJob: Job? = null

    private val _connectivityState = MutableStateFlow(ConnectivityState.UNKNOWN)
    val connectivityState: StateFlow<ConnectivityState> = _connectivityState.asStateFlow()

    private val _isFullyConnected = MutableStateFlow(false)
    val isFullyConnected: StateFlow<Boolean> = _isFullyConnected.asStateFlow()

    /**
     * Fast check using only ConnectivityManager.
     * Use this for quick UI updates where backend health is not critical.
     */
    fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return true // Assume available if no manager
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    /**
     * Full connectivity check combining interface + backend health.
     * Use this before starting sync operations.
     *
     * @param forceHealthCheck If true, bypasses health check cache
     * @return true if both network interface and backend are available
     */
    suspend fun checkFullConnectivity(forceHealthCheck: Boolean = false): Boolean {
        // First check network interface
        if (!isNetworkAvailable()) {
            _connectivityState.value = ConnectivityState.OFFLINE
            _isFullyConnected.value = false
            return false
        }

        _connectivityState.value = ConnectivityState.ONLINE_CHECKING

        // Then check backend health
        val isHealthy = healthCheckService.checkHealth(force = forceHealthCheck)

        if (isHealthy) {
            _connectivityState.value = ConnectivityState.ONLINE_HEALTHY
            _isFullyConnected.value = true
            cancelHealthCheckRetry()
        } else {
            _connectivityState.value = ConnectivityState.ONLINE_BACKEND_DOWN
            _isFullyConnected.value = false
            scheduleHealthCheckRetry()
        }

        return isHealthy
    }

    /**
     * Handles network interface restoration.
     * Verifies backend health before confirming full connectivity.
     *
     * @return true if backend is also reachable
     */
    suspend fun onNetworkRestored(): Boolean {
        Log.d(TAG, "🔄 Network restored, verifying backend health...")

        // Reset health check state since network conditions may have changed
        healthCheckService.reset()

        return checkFullConnectivity(forceHealthCheck = true)
    }

    /**
     * Handles network interface loss.
     * Cancels any pending health check retries.
     */
    fun onNetworkLost() {
        _connectivityState.value = ConnectivityState.OFFLINE
        _isFullyConnected.value = false
        cancelHealthCheckRetry()
        Log.d(TAG, "📴 Network lost")
    }

    /**
     * Schedules a health check retry with exponential backoff.
     */
    private fun scheduleHealthCheckRetry() {
        cancelHealthCheckRetry()

        val delayMs = healthCheckService.getRetryDelayMs()
        Log.d(TAG, "⏰ Scheduling health check retry in ${delayMs}ms")

        retryJob = scope.launch {
            delay(delayMs)

            // Only retry if we still have network interface
            if (isNetworkAvailable()) {
                Log.d(TAG, "🔄 Retrying backend health check...")
                checkFullConnectivity(forceHealthCheck = true)
            }
        }
    }

    /**
     * Cancels any pending health check retry.
     */
    private fun cancelHealthCheckRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Resets the service state (e.g., on logout).
     */
    fun reset() {
        cancelHealthCheckRetry()
        healthCheckService.reset()
        _connectivityState.value = ConnectivityState.UNKNOWN
        _isFullyConnected.value = false
        Log.d(TAG, "🔄 Connectivity service reset")
    }

    companion object {
        private const val TAG = "DualLayerConnectivity"
    }
}

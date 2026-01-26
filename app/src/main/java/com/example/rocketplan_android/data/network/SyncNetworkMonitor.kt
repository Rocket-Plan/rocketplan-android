package com.example.rocketplan_android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.RemoteLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Monitors network connectivity and triggers sync queue processing when network is restored.
 * Uses dual-layer verification: network interface check + backend health check.
 */
class SyncNetworkMonitor(
    context: Context,
    private val syncQueueManager: SyncQueueManager,
    private val remoteLogger: RemoteLogger? = null,
    private val dualLayerConnectivity: DualLayerConnectivityService? = null
) {
    companion object {
        private const val TAG = "SyncNetworkMonitor"
        private const val RESTORE_DEBOUNCE_MS = 2_000L
        private const val LOST_DEBOUNCE_MS = 1_000L
        private const val HEALTH_CHECK_RETRY_MS = 10_000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val isNetworkAvailable = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var restoreJob: Job? = null
    private var lostJob: Job? = null
    private var healthCheckRetryJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (hasInternet) {
                handleNetworkRestored()
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            handleNetworkLost()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (hasInternet) {
                handleNetworkRestored()
            } else {
                scheduleNetworkLost()
            }
        }
    }

    init {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val initialAvailable =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        isNetworkAvailable.set(initialAvailable)

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.d(TAG, "Network monitor initialized (initial state: ${if (initialAvailable) "online" else "offline"})")
    }

    private fun handleNetworkLost() {
        scope.launch {
            stateMutex.withLock {
                if (!isNetworkAvailable.compareAndSet(true, false)) {
                    return@withLock
                }
                Log.d(TAG, "Network lost")
                remoteLogger?.log(
                    com.example.rocketplan_android.logging.LogLevel.INFO,
                    TAG,
                    "Sync network lost"
                )
                lostJob?.cancel()
                lostJob = null
                restoreJob?.cancel()
                restoreJob = null
                healthCheckRetryJob?.cancel()
                healthCheckRetryJob = null
                // Notify dual-layer service
                dualLayerConnectivity?.onNetworkLost()
            }
        }
    }

    private fun handleNetworkRestored() {
        scope.launch {
            stateMutex.withLock {
                if (isNetworkAvailable.compareAndSet(false, true)) {
                    Log.d(TAG, "Network restored - triggering sync queue refresh")
                    remoteLogger?.log(
                        com.example.rocketplan_android.logging.LogLevel.INFO,
                        TAG,
                        "Sync network restored - triggering refresh"
                    )
                    lostJob?.cancel()
                    lostJob = null
                    scheduleRefreshLocked()
                }
            }
        }
    }

    /**
     * Must be called while holding stateMutex
     */
    private fun scheduleRefreshLocked() {
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(RESTORE_DEBOUNCE_MS)
            try {
                // If dual-layer connectivity is available, verify backend is reachable
                if (dualLayerConnectivity != null) {
                    val isFullyConnected = dualLayerConnectivity.onNetworkRestored()
                    if (!isFullyConnected) {
                        Log.d(TAG, "Network interface available but backend unreachable, scheduling retry")
                        remoteLogger?.log(
                            com.example.rocketplan_android.logging.LogLevel.WARN,
                            TAG,
                            "Network restored but backend unreachable",
                            mapOf("willRetry" to "true")
                        )
                        scheduleHealthCheckRetry()
                        return@launch
                    }
                }

                remoteLogger?.flush()
                // Reset any FAILED operations so they get retried now that network is back
                syncQueueManager.resetFailedOperations()
                syncQueueManager.processPendingOperations()
                syncQueueManager.refreshProjects()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during network restore sync", e)
                remoteLogger?.log(
                    com.example.rocketplan_android.logging.LogLevel.ERROR,
                    TAG,
                    "Network restore sync failed: ${e.message}",
                    mapOf("error" to (e.message ?: "unknown"))
                )
            }
        }
    }

    /**
     * Schedules a health check retry when backend is initially unreachable.
     */
    private fun scheduleHealthCheckRetry() {
        healthCheckRetryJob?.cancel()
        healthCheckRetryJob = scope.launch {
            delay(HEALTH_CHECK_RETRY_MS)
            stateMutex.withLock {
                if (isNetworkAvailable.get()) {
                    scheduleRefreshLocked()
                }
            }
        }
    }

    private fun scheduleNetworkLost() {
        scope.launch {
            stateMutex.withLock {
                lostJob?.cancel()
                lostJob = scope.launch {
                    delay(LOST_DEBOUNCE_MS)
                    handleNetworkLost()
                }
            }
        }
    }

    fun unregister() {
        // Cancel all coroutines and clean up
        healthCheckRetryJob?.cancel()
        scope.cancel()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        dualLayerConnectivity?.reset()
        Log.d(TAG, "Network monitor unregistered")
    }
}

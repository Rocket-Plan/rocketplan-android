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
 */
class SyncNetworkMonitor(
    context: Context,
    private val syncQueueManager: SyncQueueManager,
    private val remoteLogger: RemoteLogger? = null
) {
    companion object {
        private const val TAG = "SyncNetworkMonitor"
        private const val RESTORE_DEBOUNCE_MS = 2_000L
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val isNetworkAvailable = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var restoreJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            handleNetworkRestored()
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
            val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && hasValidated) {
                Log.d(TAG, "Network validated and available")
                handleNetworkRestored()
            }
        }
    }

    init {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val initialAvailable =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
                restoreJob?.cancel()
                restoreJob = null
            }
        }
    }

    private fun handleNetworkRestored() {
        scope.launch {
            stateMutex.withLock {
                if (isNetworkAvailable.compareAndSet(false, true)) {
                    Log.d(TAG, "Network restored - triggering sync queue refresh")
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

    fun unregister() {
        // Cancel all coroutines and clean up
        scope.cancel()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        Log.d(TAG, "Network monitor unregistered")
    }
}

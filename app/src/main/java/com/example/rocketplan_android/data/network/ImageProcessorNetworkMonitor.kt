package com.example.rocketplan_android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Monitors network connectivity and triggers retry queue processing when network is restored.
 * Matches iOS behavior of immediate retry on network recovery.
 */
class ImageProcessorNetworkMonitor(
    context: Context,
    private val queueManager: ImageProcessorQueueManager
    ) {
    companion object {
        private const val TAG = "ImgProcessorNetMonitor"
        private const val RESTORE_DEBOUNCE_MS = 2_000L
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val isNetworkAvailable = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private var restoreJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "🌐 Network available")
            handleNetworkRestored()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "📡 Network lost")
            handleNetworkLost()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (hasInternet && hasValidated) {
                Log.d(TAG, "🌐 Network validated and available")
                handleNetworkRestored()
            }
        }
    }

    init {
        // Check current network state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val initialAvailable =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        isNetworkAvailable.set(initialAvailable)

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.d(TAG, "📡 Network monitor initialized (initial state: ${if (initialAvailable) "online" else "offline"})")
    }

    private fun handleNetworkLost() {
        scope.launch {
            val shouldPause = stateMutex.withLock {
                if (!isNetworkAvailable.compareAndSet(true, false)) {
                    return@withLock false
                }
                restoreJob?.cancel()
                restoreJob = null
                true
            }
            if (shouldPause) {
                Log.d(TAG, "⏸️ Network lost - pausing active assemblies")
                // Mark uploading assemblies as WAITING_FOR_CONNECTIVITY (matching iOS behavior)
                queueManager.pauseForConnectivity()
            }
        }
    }

    private fun handleNetworkRestored() {
        scope.launch {
            val shouldSchedule = stateMutex.withLock {
                isNetworkAvailable.compareAndSet(false, true)
            }
            if (shouldSchedule) {
                Log.d(TAG, "🔄 Network restored - triggering retry queue (debounced, bypass timeout)")
                scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        scope.launch {
            stateMutex.withLock {
                restoreJob?.cancel()
                restoreJob = scope.launch {
                    delay(RESTORE_DEBOUNCE_MS)
                    queueManager.processRetryQueue(bypassTimeout = true)
                }
            }
        }
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        Log.d(TAG, "📡 Network monitor unregistered")
    }
}

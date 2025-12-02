package com.example.rocketplan_android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager

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
    private var isNetworkAvailable = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restoreJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "🌐 Network available")
            if (!isNetworkAvailable) {
                isNetworkAvailable = true
                onNetworkRestored()
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "📡 Network lost")
            if (isNetworkAvailable) {
                isNetworkAvailable = false
                onNetworkLost()
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (hasInternet && hasValidated && !isNetworkAvailable) {
                Log.d(TAG, "🌐 Network validated and available")
                isNetworkAvailable = true
                onNetworkRestored()
            }
        }
    }

    init {
        // Check current network state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        isNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.d(TAG, "📡 Network monitor initialized (initial state: ${if (isNetworkAvailable) "online" else "offline"})")
    }

    private fun onNetworkLost() {
        Log.d(TAG, "⏸️ Network lost - pausing active assemblies")
        // Mark uploading assemblies as WAITING_FOR_CONNECTIVITY (matching iOS behavior)
        queueManager.pauseForConnectivity()
    }

    private fun onNetworkRestored() {
        Log.d(TAG, "🔄 Network restored - triggering retry queue (debounced, bypass timeout)")
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(RESTORE_DEBOUNCE_MS)
            queueManager.processRetryQueue(bypassTimeout = true)
        }
    }

    fun unregister() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        Log.d(TAG, "📡 Network monitor unregistered")
    }
}

package com.example.rocketplan_android.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.Channel
import com.pusher.client.channel.SubscriptionEventListener
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import java.lang.reflect.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class PusherService(
    context: Context,
    private val gson: Gson = Gson(),
    private val remoteLogger: RemoteLogger? = null,
    private val appVisibilityTracker: AppVisibilityTracker =
        AppVisibilityTracker.getInstance(context.applicationContext)
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pusher: Pusher = Pusher(
        PusherConfig.appKey(),
        PusherOptions().setCluster(PusherConfig.CLUSTER)
    )
    private val throttledErrorTimestamps = ConcurrentHashMap<String, Long>()
    private val appVisibilityListener: (Boolean) -> Unit = { isForeground ->
        if (isForeground) {
            scope.launch { ensureConnectedIfNeeded("foreground") }
        } else {
            scope.launch { disconnectSocket("background") }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { ensureConnectedIfNeeded("network-restored") }
        }

        override fun onLost(network: Network) {
            scope.launch { disconnectSocket("network-lost") }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                scope.launch { ensureConnectedIfNeeded("network-capabilities") }
            }
        }
    }

    private val connectionListener = object : ConnectionEventListener {
        override fun onConnectionStateChange(change: ConnectionStateChange?) {
            val newState = change?.currentState ?: return
            when (newState) {
                ConnectionState.CONNECTED -> {
                    Log.d(TAG, "🔌 Pusher connected")
                    scope.launch {
                        stateMutex.withLock {
                            reconnectAttempts = 0
                            reconnectJob?.cancel()
                            reconnectJob = null
                        }
                    }
                }

                ConnectionState.DISCONNECTED -> {
                    Log.d(TAG, "🔌 Pusher disconnected")
                    scheduleReconnect()
                }

                ConnectionState.RECONNECTING -> {
                    // No-op: SDK handles reconnection internally. Scheduling our own
                    // reconnect here would call pusher.connect() while the SDK is
                    // mid-reconnect, causing duplicate channel subscriptions.
                    Log.d(TAG, "🔌 Pusher reconnecting (SDK-managed)")
                }

                else -> Unit
            }
        }

        override fun onError(message: String?, code: String?, e: Exception?) {
            // "Existing subscription" is harmless - Pusher SDK auto-resubscribes on reconnect.
            // Log at DEBUG to retain observability without polluting error logs.
            if (message != null && message.contains("existing subscription", ignoreCase = true)) {
                Log.d(TAG, "🔌 Pusher duplicate subscription (harmless): $message")
                remoteLogger?.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Pusher duplicate subscription (harmless): $message"
                )
                return
            }
            val level = classifyConnectionError(message, code, e)
            logConnectionIssue(level, message, code, e)
            scheduleReconnect()
        }
    }

    private data class ChannelBinding(
        val channel: Channel,
        val listeners: MutableMap<String, SubscriptionEventListener>,
        var lifecycleBound: Boolean = false
    )

    private val channelBindings = ConcurrentHashMap<String, ChannelBinding>()
    private val envelopeType = object : TypeToken<PusherEnvelope>() {}.type
    private val updateType = object : TypeToken<ImageProcessorUpdate>() {}.type
    private val stateMutex = Mutex()
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    init {
        appVisibilityTracker.addListener(appVisibilityListener)
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure {
                Log.w(TAG, "Unable to register Pusher network callback: ${it.localizedMessage}")
            }
    }

    fun bindImageProcessorEvent(
        channelName: String,
        eventName: String,
        callback: (ImageProcessorUpdate?) -> Unit
    ) {
        val binding = channelBindings.getOrPut(channelName) {
            ChannelBinding(
                channel = pusher.subscribe(channelName),
                listeners = ConcurrentHashMap()
            )
        }
        attachSubscriptionLifecycle(channelName, binding)

        Log.d(TAG, "📡 Binding image processor event: channel=$channelName event=$eventName")

        // Replace any previous listener for this event to avoid duplicate callbacks
        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            val data = event.data
            if (data.isNullOrBlank() || data == "[]") {
                callback(null)
                return@SubscriptionEventListener
            }

            val update = parseUpdate(data)
            Log.d(
                TAG,
                "🔔 Pusher update: channel=$channelName event=$eventName assembly=${update?.assemblyId ?: "unknown"} status=${update?.status ?: "none"}"
            )
            if (update == null) {
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = "Failed to parse Pusher payload for $eventName",
                    metadata = mapOf("channel" to channelName)
                )
            }
            callback(update)
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        scope.launch { ensureConnectedIfNeeded("bind-image-processor") }
    }

    fun unsubscribe(channelName: String) {
        Log.d(TAG, "🧹 Unsubscribing from channel: $channelName")
        val binding = channelBindings.remove(channelName) ?: return
        binding.listeners.forEach { (event, listener) ->
            binding.channel.unbind(event, listener)
        }
        pusher.unsubscribe(channelName)
        disconnectIfIdle()
    }

    /**
     * Binds a generic event listener that receives raw JSON payload.
     * Used for simple notification events like PhotoUploadingCompletedAnnouncement.
     */
    fun bindGenericEvent(
        channelName: String,
        eventName: String,
        callback: () -> Unit
    ) {
        val binding = channelBindings.getOrPut(channelName) {
            ChannelBinding(
                channel = pusher.subscribe(channelName),
                listeners = ConcurrentHashMap()
            )
        }
        attachSubscriptionLifecycle(channelName, binding)

        Log.d(TAG, "📡 Binding generic event: channel=$channelName event=$eventName")

        // Replace any previous listener for this event to avoid duplicate callbacks
        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            Log.d(TAG, "🔔 Pusher event received: channel=$channelName event=$eventName")
            callback()
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        scope.launch { ensureConnectedIfNeeded("bind-generic") }
    }

    /**
     * Binds a generic event listener and forwards the raw JSON payload.
     * Useful for simple notification events where only the trigger matters.
     */
    fun bindRawEvent(
        channelName: String,
        eventName: String,
        callback: (String?) -> Unit
    ) {
        val binding = channelBindings.getOrPut(channelName) {
            ChannelBinding(
                channel = pusher.subscribe(channelName),
                listeners = ConcurrentHashMap()
            )
        }
        attachSubscriptionLifecycle(channelName, binding)

        Log.d(TAG, "📡 Binding raw event: channel=$channelName event=$eventName")

        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            Log.d(TAG, "🔔 Pusher raw event received: channel=$channelName event=$eventName data=${event.data}")
            callback(event.data)
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        scope.launch { ensureConnectedIfNeeded("bind-raw") }
    }

    /**
     * Binds an event and decodes the JSON payload into the requested type.
     */
    fun <T> bindTypedEvent(
        channelName: String,
        eventName: String,
        type: Type,
        callback: (T?) -> Unit
    ) {
        val binding = channelBindings.getOrPut(channelName) {
            ChannelBinding(
                channel = pusher.subscribe(channelName),
                listeners = ConcurrentHashMap()
            )
        }
        attachSubscriptionLifecycle(channelName, binding)

        Log.d(TAG, "📡 Binding typed event: channel=$channelName event=$eventName")

        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            val raw = event.data
            Log.d(TAG, "🔔 Pusher typed event received: channel=$channelName event=$eventName raw=$raw")
            if (raw.isNullOrBlank() || raw == "[]") {
                callback(null)
                return@SubscriptionEventListener
            }

            val parsed = runCatching { gson.fromJson<T>(raw, type) }
                .onFailure { error ->
                    remoteLogger?.log(
                        level = LogLevel.WARN,
                        tag = TAG,
                        message = "Failed to decode Pusher payload for $eventName",
                        metadata = mapOf(
                            "channel" to channelName,
                            "reason" to (error.message ?: "unknown")
                        )
                    )
                }
                .getOrNull()
            callback(parsed)
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        scope.launch { ensureConnectedIfNeeded("bind-typed") }
    }

    fun disconnect() {
        channelBindings.keys.toList().forEach { unsubscribe(it) }
        scope.launch { disconnectSocket("manual-disconnect") }
    }

    /**
     * Shutdown the Pusher service, canceling all coroutines and releasing resources.
     * Call this when the app is terminating or the service is no longer needed.
     */
    fun shutdown() {
        channelBindings.keys.toList().forEach { unsubscribe(it) }
        // Disconnect synchronously before cancelling the scope so the
        // disconnect call is not dropped by scope cancellation.
        reconnectJob?.cancel()
        reconnectJob = null
        pusher.disconnect()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        appVisibilityTracker.removeListener(appVisibilityListener)
        scope.cancel()
    }

    fun connectIfNeeded() {
        scope.launch { ensureConnectedIfNeeded("manual-connect") }
    }

    fun isConnected(): Boolean = pusher.connection.state == ConnectionState.CONNECTED

    private suspend fun ensureConnectedIfNeeded(reason: String) {
        if (!shouldMaintainConnection()) {
            Log.d(
                TAG,
                "⏸️ Skipping Pusher connect: reason=$reason foreground=${appVisibilityTracker.isAppForeground()} online=${isNetworkAvailable()} channels=${channelBindings.size}"
            )
            return
        }
        ensureConnected()
    }

    private fun ensureConnected() {
        val state = pusher.connection.state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) {
            return
        }
        Log.d(TAG, "🔌 Connecting Pusher (state=$state)")
        pusher.connect(connectionListener, ConnectionState.ALL)
    }

    private fun attachSubscriptionLifecycle(channelName: String, binding: ChannelBinding) {
        if (binding.lifecycleBound) return
        binding.lifecycleBound = true

        val successListener = SubscriptionEventListener {
            Log.d(TAG, "✅ Pusher subscription succeeded for channel=$channelName")
        }
        val errorListener = SubscriptionEventListener { event ->
            Log.e(TAG, "❌ Pusher subscription error for channel=$channelName payload=${event.data}")
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Pusher subscription error",
                metadata = mapOf(
                    "channel" to channelName,
                    "payload" to (event.data ?: "none")
                )
            )
            scheduleReconnect()
        }

        binding.listeners[SUBSCRIPTION_SUCCEEDED] = successListener
        binding.listeners[SUBSCRIPTION_ERROR] = errorListener
        binding.channel.bind(SUBSCRIPTION_SUCCEEDED, successListener)
        binding.channel.bind(SUBSCRIPTION_ERROR, errorListener)
    }

    private fun scheduleReconnect() {
        scope.launch {
            stateMutex.withLock {
                if (channelBindings.isEmpty()) return@withLock
                if (!shouldMaintainConnection()) {
                    reconnectJob?.cancel()
                    reconnectJob = null
                    return@withLock
                }
                val state = pusher.connection.state
                if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
                    return@withLock
                }
                if (reconnectJob?.isActive == true) return@withLock

                val attempt = reconnectAttempts
                reconnectAttempts += 1
                val delayMs = backoffDelayMs(attempt)
                Log.w(TAG, "♻️ Scheduling Pusher reconnect attempt=${attempt + 1} delayMs=$delayMs state=$state")
                reconnectJob = scope.launch {
                    delay(delayMs)
                    Log.d(TAG, "♻️ Reconnect timer fired (attempt=${attempt + 1})")
                    ensureConnectedIfNeeded("reconnect")
                    stateMutex.withLock { reconnectJob = null }
                }
            }
        }
    }

    private fun backoffDelayMs(attempt: Int): Long {
        val clampedAttempt = attempt.coerceIn(0, 6)
        val backoff = (1 shl clampedAttempt) * BASE_BACKOFF_MS
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun disconnectIfIdle() {
        if (channelBindings.isEmpty()) {
            scope.launch { disconnectSocket("idle") }
        }
    }

    private suspend fun disconnectSocket(reason: String) {
        stateMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
        }
        val state = pusher.connection.state
        if (state != ConnectionState.DISCONNECTED) {
            Log.d(TAG, "🔌 Disconnecting Pusher socket (reason=$reason state=$state)")
            pusher.disconnect()
        }
    }

    private fun shouldMaintainConnection(): Boolean {
        return channelBindings.isNotEmpty() &&
            appVisibilityTracker.isAppForeground() &&
            isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private fun classifyConnectionError(
        message: String?,
        code: String?,
        exception: Exception?
    ): LogLevel {
        val reason = exception?.message.orEmpty()
        val combined = buildString {
            append(message.orEmpty())
            append(' ')
            append(reason)
        }
        val inBackground = !appVisibilityTracker.isAppForeground()
        val offline = !isNetworkAvailable()
        val expectedConnectivityIssue =
            code == "4201" ||
                combined.contains("Pong reply not received", ignoreCase = true) ||
                combined.contains("RECONNECTING state", ignoreCase = true) ||
                combined.contains("Unable to resolve host", ignoreCase = true) ||
                combined.contains("exception was thrown by the websocket", ignoreCase = true)

        return when {
            offline || inBackground -> LogLevel.INFO
            expectedConnectivityIssue -> LogLevel.WARN
            else -> LogLevel.ERROR
        }
    }

    private fun logConnectionIssue(
        level: LogLevel,
        message: String?,
        code: String?,
        exception: Exception?
    ) {
        val resolvedMessage = message ?: "unknown"
        val exceptionMessage = exception?.message
        val formatted = "Pusher connection error: $resolvedMessage"
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, "🔌 $formatted", exception)
            LogLevel.INFO -> Log.i(TAG, "ℹ️ $formatted (code=${code ?: "none"})", exception)
            LogLevel.WARN -> Log.w(TAG, "⚠️ $formatted (code=${code ?: "none"})", exception)
            LogLevel.ERROR -> Log.e(TAG, "🚨 $formatted (code=${code ?: "none"})", exception)
        }

        val metadata = buildMap {
            put("code", code ?: "none")
            put("connection_state", pusher.connection.state.name)
            put("app_foreground", appVisibilityTracker.isAppForeground().toString())
            put("network_available", isNetworkAvailable().toString())
            exceptionMessage?.let { put("exception", it) }
        }

        if (shouldThrottleRemoteLog(level, resolvedMessage, code, exceptionMessage)) {
            return
        }

        remoteLogger?.log(
            level = level,
            tag = TAG,
            message = formatted,
            metadata = metadata
        )
    }

    private fun shouldThrottleRemoteLog(
        level: LogLevel,
        message: String,
        code: String?,
        exceptionMessage: String?
    ): Boolean {
        if (level == LogLevel.ERROR) return false

        val key = listOf(message, code ?: "none", exceptionMessage ?: "none").joinToString("|")
        val now = System.currentTimeMillis()
        val lastSeenAt = throttledErrorTimestamps[key]
        if (lastSeenAt != null && now - lastSeenAt < EXPECTED_ERROR_LOG_THROTTLE_MS) {
            return true
        }
        throttledErrorTimestamps[key] = now
        if (throttledErrorTimestamps.size > MAX_THROTTLE_CACHE_SIZE) {
            val cutoff = now - max(EXPECTED_ERROR_LOG_THROTTLE_MS, 60_000L)
            throttledErrorTimestamps.entries.removeIf { (_, timestamp) -> timestamp < cutoff }
        }
        return false
    }

    private fun parseUpdate(raw: String): ImageProcessorUpdate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        return runCatching {
            val envelope = gson.fromJson<PusherEnvelope>(trimmed, envelopeType)
            envelope.imageProcessorUpdate ?: gson.fromJson(trimmed, updateType)
        }.onFailure { error ->
            val reason = error.message ?: "unknown"
            val level = if (error is JsonSyntaxException) LogLevel.DEBUG else LogLevel.ERROR
            remoteLogger?.log(
                level = level,
                tag = TAG,
                message = "Error decoding Pusher payload",
                metadata = mapOf("reason" to reason)
            )
        }.getOrNull()
    }

    companion object {
        private const val TAG = "PusherService"
        private const val SUBSCRIPTION_SUCCEEDED = "pusher:subscription_succeeded"
        private const val SUBSCRIPTION_ERROR = "pusher:subscription_error"
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val EXPECTED_ERROR_LOG_THROTTLE_MS = 15 * 60 * 1000L
        private const val MAX_THROTTLE_CACHE_SIZE = 128
    }
}

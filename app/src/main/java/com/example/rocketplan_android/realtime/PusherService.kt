package com.example.rocketplan_android.realtime

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class PusherService(
    private val gson: Gson = Gson(),
    private val remoteLogger: RemoteLogger? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pusher: Pusher = Pusher(
        PusherConfig.appKey(),
        PusherOptions().setCluster(PusherConfig.CLUSTER)
    )

    private val connectionListener = object : ConnectionEventListener {
        override fun onConnectionStateChange(change: ConnectionStateChange?) {
            val newState = change?.currentState ?: return
            when (newState) {
                ConnectionState.CONNECTED -> {
                    Log.d(TAG, "🔌 Pusher connected")
                    remoteLogger?.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Pusher connected"
                    )
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
                    remoteLogger?.log(
                        level = LogLevel.WARN,
                        tag = TAG,
                        message = "Pusher disconnected"
                    )
                    scheduleReconnect()
                }

                ConnectionState.RECONNECTING -> {
                    Log.d(TAG, "🔌 Pusher reconnecting")
                    remoteLogger?.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Pusher reconnecting"
                    )
                    scheduleReconnect()
                }

                else -> Unit
            }
        }

        override fun onError(message: String?, code: String?, e: Exception?) {
            Log.e(TAG, "🚨 Pusher connection error: ${message ?: "unknown"} (code=${code ?: "none"})", e)
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Pusher connection error: ${message ?: "unknown"}",
                metadata = buildMap {
                    put("code", code ?: "none")
                    e?.message?.let { put("exception", it) }
                }
            )
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
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Subscribing to image processor channel",
            metadata = mapOf("channel" to channelName, "event" to eventName)
        )

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
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Pusher event received",
                metadata = mapOf(
                    "channel" to channelName,
                    "event" to eventName,
                    "assembly_id" to (update?.assemblyId ?: "unknown"),
                    "status" to (update?.status ?: "none")
                )
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
        ensureConnected()
    }

    fun unsubscribe(channelName: String) {
        Log.d(TAG, "🧹 Unsubscribing from channel: $channelName")
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Unsubscribing from channel",
            metadata = mapOf("channel" to channelName)
        )
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
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Subscribing to generic channel",
            metadata = mapOf("channel" to channelName, "event" to eventName)
        )

        // Replace any previous listener for this event to avoid duplicate callbacks
        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            Log.d(TAG, "🔔 Pusher event received: channel=$channelName event=$eventName")
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Pusher generic event received",
                metadata = mapOf("channel" to channelName, "event" to eventName)
            )
            callback()
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        ensureConnected()
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
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Subscribing to raw channel",
            metadata = mapOf("channel" to channelName, "event" to eventName)
        )

        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            Log.d(TAG, "🔔 Pusher raw event received: channel=$channelName event=$eventName data=${event.data}")
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Pusher raw event received",
                metadata = mapOf("channel" to channelName, "event" to eventName)
            )
            callback(event.data)
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        ensureConnected()
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
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Subscribing to typed channel",
            metadata = mapOf("channel" to channelName, "event" to eventName)
        )

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
        ensureConnected()
    }

    fun disconnect() {
        channelBindings.keys.toList().forEach { unsubscribe(it) }
        scope.launch {
            stateMutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
            }
            pusher.disconnect()
        }
    }

    fun connectIfNeeded() {
        ensureConnected()
    }

    fun isConnected(): Boolean = pusher.connection.state == ConnectionState.CONNECTED

    private fun ensureConnected() {
        val state = pusher.connection.state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
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
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Pusher subscription succeeded",
                metadata = mapOf("channel" to channelName)
            )
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
                    ensureConnected()
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
            scope.launch {
                stateMutex.withLock {
                    reconnectJob?.cancel()
                    reconnectJob = null
                }
                pusher.disconnect()
            }
        }
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
    }
}

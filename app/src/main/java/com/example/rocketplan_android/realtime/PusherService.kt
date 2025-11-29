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
import java.util.concurrent.ConcurrentHashMap

class PusherService(
    private val gson: Gson = Gson(),
    private val remoteLogger: RemoteLogger? = null
) {

    private val pusher: Pusher = Pusher(
        PusherConfig.appKey(),
        PusherOptions().setCluster(PusherConfig.CLUSTER)
    )

    private val connectionListener = object : ConnectionEventListener {
        override fun onConnectionStateChange(change: ConnectionStateChange?) {
            // TODO: Remove temporary debug logging
            Log.d(TAG, "🔌 Connection state: ${change?.previousState} -> ${change?.currentState}")
        }

        override fun onError(message: String?, code: String?, e: Exception?) {
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Pusher connection error: ${message ?: "unknown"}",
                metadata = buildMap {
                    put("code", code ?: "none")
                    e?.message?.let { put("exception", it) }
                }
            )
        }
    }

    private data class ChannelBinding(
        val channel: Channel,
        val listeners: MutableMap<String, SubscriptionEventListener>
    )

    private val channelBindings = ConcurrentHashMap<String, ChannelBinding>()
    private val envelopeType = object : TypeToken<PusherEnvelope>() {}.type
    private val updateType = object : TypeToken<ImageProcessorUpdate>() {}.type

    fun bindImageProcessorEvent(
        channelName: String,
        eventName: String,
        callback: (ImageProcessorUpdate?) -> Unit
    ) {
        // TODO: Remove temporary debug logging
        Log.d(TAG, "🔔 Binding event: channel=$channelName event=$eventName")

        val binding = channelBindings.getOrPut(channelName) {
            Log.d(TAG, "📡 Subscribing to new channel: $channelName")
            ChannelBinding(
                channel = pusher.subscribe(channelName),
                listeners = ConcurrentHashMap()
            )
        }

        // Replace any previous listener for this event to avoid duplicate callbacks
        binding.listeners.remove(eventName)?.let { existing ->
            binding.channel.unbind(eventName, existing)
        }

        val listener = SubscriptionEventListener { event ->
            // TODO: Remove temporary debug logging
            Log.d(TAG, "📨 Event received: channel=$channelName event=${event.eventName}")
            Log.d(TAG, "📦 Payload: ${event.data?.take(500)}")

            val data = event.data
            if (data.isNullOrBlank() || data == "[]") {
                Log.d(TAG, "⚠️ Empty payload, calling callback with null")
                callback(null)
                return@SubscriptionEventListener
            }

            val update = parseUpdate(data)
            if (update == null) {
                Log.w(TAG, "❌ Failed to parse payload")
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = "Failed to parse Pusher payload for $eventName",
                    metadata = mapOf("channel" to channelName)
                )
            } else {
                Log.d(TAG, "✅ Parsed update: status=${update.status}, assemblyId=${update.assemblyId}")
            }
            callback(update)
        }

        binding.listeners[eventName] = listener
        binding.channel.bind(eventName, listener)
        ensureConnected()
    }

    fun unsubscribe(channelName: String) {
        // TODO: Remove temporary debug logging
        Log.d(TAG, "🔕 Unsubscribing from channel: $channelName")

        val binding = channelBindings.remove(channelName) ?: return
        binding.listeners.forEach { (event, listener) ->
            binding.channel.unbind(event, listener)
        }
        pusher.unsubscribe(channelName)
    }

    fun disconnect() {
        channelBindings.keys.toList().forEach { unsubscribe(it) }
        pusher.disconnect()
    }

    fun connectIfNeeded() {
        ensureConnected()
    }

    private fun ensureConnected() {
        val state = pusher.connection.state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            return
        }
        pusher.connect(connectionListener, ConnectionState.ALL)
    }

    private fun parseUpdate(raw: String): ImageProcessorUpdate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        return runCatching {
            val envelope = gson.fromJson<PusherEnvelope>(trimmed, envelopeType)
            envelope.imageProcessorUpdate ?: gson.fromJson(trimmed, updateType)
        }.onFailure { error ->
            if (error !is JsonSyntaxException) {
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Unexpected error decoding Pusher payload",
                    metadata = mapOf("reason" to (error.message ?: "unknown"))
                )
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "PusherService"
    }
}

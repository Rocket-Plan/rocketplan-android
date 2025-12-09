package com.example.rocketplan_android.data.model

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Test

class RemoteLogBatchSerializationTest {

    private val gson = Gson()

    @Test
    fun `build number serializes using snake case`() {
        val entry = RemoteLogEntry(
            timestamp = "2025-09-20T12:00:00Z",
            level = "INFO",
            category = "test",
            message = "build number serialization"
        )
        val batch = RemoteLogBatch(
            batchId = "batch-1",
            deviceId = "device-123",
            userId = "42",
            companyId = "7",
            sessionId = "session-abc",
            appVersion = "1.2.3",
            buildNumber = "12345",
            logs = listOf(entry)
        )

        val json = gson.toJson(batch)
        val jsonObject = gson.fromJson(json, JsonObject::class.java)

        assertThat(jsonObject.get("build_number").asString).isEqualTo("12345")
        assertThat(jsonObject.has("buildNumber")).isFalse()
    }
}

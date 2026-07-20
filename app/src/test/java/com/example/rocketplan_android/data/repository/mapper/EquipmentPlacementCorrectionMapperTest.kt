package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * RP-FR-030 — mapper coverage for the placement date-correction request. Asserts the request
 * emits snake_case keys (RP-CD-006), carries the placement's lock token, and renders dates as
 * date-only (UTC `yyyy-MM-dd`) so a correction never shifts a day (mirrors iOS RP-BUG-345).
 */
class EquipmentPlacementCorrectionMapperTest {

    private val gson = Gson()

    private fun placement() = OfflineEquipmentPlacementEntity(
        placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
        isOpen = false, createdAt = Date(), updatedAt = Date()
    )

    private fun utcDate(year: Int, month0: Int, day: Int, hour: Int = 0): Date =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month0, day, hour, 0, 0)
        }.time

    @Test
    fun `emits snake_case fields, lock token, and date-only strings`() {
        val request = placement().toCorrectPlacementRequest(
            dateIn = utcDate(2026, Calendar.JUNE, 1),
            dateOut = utcDate(2026, Calendar.JUNE, 10),
            lockUpdatedAt = "2026-06-12T10:00:00+00:00",
            idempotencyKey = "idem-key"
        )
        val json = gson.toJsonTree(request).asJsonObject

        assertThat(json.keySet()).containsAtLeast("date_in", "date_out", "updated_at", "idempotency_key")
        assertThat(json.get("date_in").asString).isEqualTo("2026-06-01")
        assertThat(json.get("date_out").asString).isEqualTo("2026-06-10")
        assertThat(json.get("updated_at").asString).isEqualTo("2026-06-12T10:00:00+00:00")
        assertThat(json.get("idempotency_key").asString).isEqualTo("idem-key")
    }

    @Test
    fun `a late-in-the-day UTC instant still renders the same calendar date (no off-by-one)`() {
        // 2026-06-01T23:30Z must serialize as 2026-06-01, not 2026-06-02.
        val request = placement().toCorrectPlacementRequest(
            dateIn = utcDate(2026, Calendar.JUNE, 1, hour = 23),
            dateOut = null,
            lockUpdatedAt = "lock",
            idempotencyKey = "k"
        )
        val json: JsonObject = gson.toJsonTree(request).asJsonObject
        assertThat(json.get("date_in").asString).isEqualTo("2026-06-01")
        // date_out omitted (null) — Gson drops it, so the server leaves it unchanged.
        assertThat(json.has("date_out")).isFalse()
    }
}

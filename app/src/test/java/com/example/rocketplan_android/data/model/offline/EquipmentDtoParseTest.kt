package com.example.rocketplan_android.data.model.offline

import com.example.rocketplan_android.data.model.SingleDataResponse
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Test

class EquipmentDtoParseTest {

    private val gson = Gson()

    private fun fixture(name: String): String =
        javaClass.classLoader!!
            .getResourceAsStream("fixtures/equipment/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `room equipment list parses hand-wrapped data array`() {
        val type = TypeToken.getParameterized(
            SingleDataResponse::class.java,
            TypeToken.getParameterized(List::class.java, EquipmentDto::class.java).type
        ).type
        val resp: SingleDataResponse<List<EquipmentDto>> = gson.fromJson(fixture("room_equipment_list.json"), type)
        assertThat(resp.data).hasSize(2)

        val first = resp.data[0]
        assertThat(first.id).isEqualTo(7001L)
        assertThat(first.uuid).isEqualTo("pivot-uuid-001")
        assertThat(first.equipmentId).isEqualTo(6001L)
        assertThat(first.roomId).isEqualTo(4001L)
        assertThat(first.projectId).isEqualTo(1001L)
        assertThat(first.type).isEqualTo("Air Mover")
        assertThat(first.displayName).isEqualTo("Air Mover (Standard)")
        assertThat(first.quantity).isEqualTo(3)
        assertThat(first.duration).isEqualTo("4h")
        assertThat(first.number).isEqualTo("EQ-2026-001")
        assertThat(first.status).isEqualTo("active")
        assertThat(first.dateIn).isEqualTo("2026-01-15T08:00:00.000000Z")
        assertThat(first.dateOut).isNull()

        val second = resp.data[1]
        assertThat(second.id).isEqualTo(7002L)
        assertThat(second.uuid).isEqualTo("pivot-uuid-002")
        assertThat(second.equipmentId).isEqualTo(6002L)
        assertThat(second.quantity).isEqualTo(2)
        assertThat(second.duration).isEqualTo("24h")
        assertThat(second.brand).isEqualTo("Dri-Eaz")
        assertThat(second.model).isEqualTo("LGR 7000")
        assertThat(second.serialNumber).isEqualTo("DE-LGR7K-12345")
        assertThat(second.status).isEqualTo("completed")
        assertThat(second.dateOut).isNotNull()
    }

    @Test
    fun `pivot dto parses with all pivot fields`() {
        val type = TypeToken.getParameterized(
            SingleDataResponse::class.java,
            EquipmentDto::class.java
        ).type
        val resp: SingleDataResponse<EquipmentDto> = gson.fromJson(fixture("equipment_room_single.json"), type)
        val dto = resp.data

        assertThat(dto.id).isEqualTo(7001L)
        assertThat(dto.uuid).isEqualTo("pivot-uuid-001")
        assertThat(dto.equipmentId).isEqualTo(6001L)
        assertThat(dto.roomId).isEqualTo(4001L)
        assertThat(dto.projectId).isEqualTo(1001L)
        assertThat(dto.type).isEqualTo("Air Mover")
        assertThat(dto.displayName).isEqualTo("Air Mover (Standard)")
        assertThat(dto.quantity).isEqualTo(3)
        assertThat(dto.duration).isEqualTo("4h")
        assertThat(dto.number).isEqualTo("EQ-2026-001")
        assertThat(dto.status).isEqualTo("active")
        assertThat(dto.dateIn).isEqualTo("2026-01-15T08:00:00.000000Z")
        assertThat(dto.dateOut).isNull()
        assertThat(dto.brand).isNull()
        assertThat(dto.model).isNull()
        assertThat(dto.serialNumber).isNull()
    }

    @Test
    fun `CreateEquipmentCatalogRequest serializes correctly`() {
        val request = CreateEquipmentCatalogRequest(
            name = "Custom Tool",
            idempotencyKey = "uuid-123"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"name\":\"Custom Tool\"")
        assertThat(json).contains("\"idempotency_key\":\"uuid-123\"")
    }

    @Test
    fun `AttachRoomEquipmentRequest serializes correctly`() {
        val request = AttachRoomEquipmentRequest(
            equipmentIds = listOf(6001L, 6002L),
            uuid = "pivot-uuid-001",
            roomUuid = null,
            idempotencyKey = "uuid-123",
            dateIn = "2026-01-15T08:00:00.000000Z"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"equipment_ids\":[6001,6002]")
        assertThat(json).contains("\"uuid\":\"pivot-uuid-001\"")
        assertThat(json).contains("\"idempotency_key\":\"uuid-123\"")
        assertThat(json).contains("\"date_in\":\"2026-01-15T08:00:00.000000Z\"")
    }

    @Test
    fun `EquipmentRoomUpdateRequest serializes correctly`() {
        val request = EquipmentRoomUpdateRequest(
            quantity = 5,
            duration = "8h",
            dateIn = "2026-01-15T08:00:00.000000Z",
            dateOut = "2026-01-16T08:00:00.000000Z",
            updatedAt = "2026-01-15T12:00:00.000000Z"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"quantity\":5")
        assertThat(json).contains("\"duration\":\"8h\"")
        assertThat(json).contains("\"date_in\":\"2026-01-15T08:00:00.000000Z\"")
        assertThat(json).contains("\"date_out\":\"2026-01-16T08:00:00.000000Z\"")
        assertThat(json).contains("\"updated_at\":\"2026-01-15T12:00:00.000000Z\"")
    }
}

package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.model.offline.AttachRoomEquipmentRequest
import com.example.rocketplan_android.data.model.offline.AttachRoomEquipmentItem
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.EquipmentMoveRequest
import com.example.rocketplan_android.data.model.offline.EquipmentTransferRequest
import com.example.rocketplan_android.data.model.offline.EquipmentMovementDto
import com.example.rocketplan_android.data.model.offline.UpdateEquipmentRoomRequest
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class EquipmentDtoMapperTest {

    private val gson = Gson()

    // ===== AttachRoomEquipmentRequest =====

    @Test
    fun `AttachRoomEquipmentRequest serializes extended equipment items`() {
        val request = AttachRoomEquipmentRequest(
            idempotencyKey = "pivot-uuid",
            equipment = listOf(AttachRoomEquipmentItem(
                equipmentId = 7000L,
                uuid = "pivot-uuid",
                dateIn = "2026-01-30T12:00:00.000000Z",
                quantity = 3
            ))
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"equipment\":[")
        assertThat(json).contains("\"equipment_id\":7000")
        assertThat(json).contains("\"idempotency_key\":\"pivot-uuid\"")
        assertThat(json).contains("\"quantity\":3")
    }

    @Test
    fun `AttachRoomEquipmentRequest is lenient with extra fields`() {
        val json = """{"equipment":[{"equipment_id":7000,"uuid":"pivot-uuid"}],"idempotency_key":"key","extra_field":"ignored"}"""
        val request = gson.fromJson(json, AttachRoomEquipmentRequest::class.java)
        assertThat(request.equipment).hasSize(1)
        assertThat(request.equipment[0].equipmentId).isEqualTo(7000L)
        assertThat(request.equipment[0].uuid).isEqualTo("pivot-uuid")
    }

    // ===== UpdateEquipmentRoomRequest =====

    @Test
    fun `UpdateEquipmentRoomRequest serializes with correct field names`() {
        val request = UpdateEquipmentRoomRequest(
            quantity = 5,
            duration = 7,
            dateIn = "2026-01-30T12:00:00.000000Z",
            dateOut = null,
            updatedAt = "2026-02-01T12:00:00.000000Z"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"quantity\":5")
        assertThat(json).contains("\"duration\":7")
        assertThat(json).contains("\"updated_at\":\"2026-02-01T12:00:00.000000Z\"")
    }

    // ===== EquipmentDto (pivot fields) =====

    @Test
    fun `EquipmentDto parses pivot fields from room-equipment read`() {
        val json = """{
            "id": 6000,
            "uuid": "pivot-uuid",
            "project_id": 100,
            "room_id": 400,
            "name": "Dehumidifier",
            "brand": "Dri-Eaz",
            "quantity": 3,
            "status": "active",
            "equipment_id": 7000,
            "catalog_uuid": "cat-uuid",
            "number": "EQ-001",
            "duration": "7 days",
            "date_in": "2026-01-30T12:00:00.000000Z",
            "date_out": null,
            "location_id": 5,
            "last_moved_at": "2026-01-30T12:00:00.000000Z",
            "current_room_id": 400,
            "updated_at": "2026-01-30T12:00:00.000000Z"
        }"""
        val dto = gson.fromJson(json, EquipmentDto::class.java)
        assertThat(dto.id).isEqualTo(6000L)
        assertThat(dto.uuid).isEqualTo("pivot-uuid")
        assertThat(dto.equipmentId).isEqualTo(7000L)
        assertThat(dto.catalogUuid).isEqualTo("cat-uuid")
        assertThat(dto.number).isEqualTo("EQ-001")
        assertThat(dto.duration).isEqualTo("7 days")
        assertThat(dto.quantity).isEqualTo(3)
        assertThat(dto.locationId).isEqualTo(5L)
        assertThat(dto.lastMovedAt).isEqualTo("2026-01-30T12:00:00.000000Z")
        assertThat(dto.currentRoomId).isEqualTo(400L)
    }

    @Test
    fun `EquipmentDto tolerates missing pivot fields (pre-MONGOOSE-BUG-036 contract)`() {
        val json = """{
            "id": 6000,
            "uuid": null,
            "project_id": 100,
            "room_id": 400,
            "name": "Dehumidifier",
            "quantity": 1,
            "status": "active",
            "updated_at": "2026-01-30T12:00:00.000000Z"
        }"""
        val dto = gson.fromJson(json, EquipmentDto::class.java)
        assertThat(dto.id).isEqualTo(6000L)
        assertThat(dto.pivotId).isNull()
        assertThat(dto.uuid).isNull()
        assertThat(dto.equipmentId).isNull()
    }

    @Test
    fun `EquipmentDto toEntity maps pivot fields correctly`() {
        val dto = EquipmentDto(
            id = 6000L,
            uuid = "pivot-uuid",
            projectId = 100L,
            roomId = 400L,
            type = "Dehumidifier",
            brand = "Dri-Eaz",
            model = null,
            serialNumber = null,
            quantity = 3,
            status = "active",
            startDate = null,
            endDate = null,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T12:00:00.000000Z",
            pivotId = null,
            pivotUuid = null,
            equipmentId = 7000L,
            catalogUuid = "cat-uuid",
            number = "EQ-001",
            duration = "7 days",
            dateIn = "2026-01-30T12:00:00.000000Z",
            dateOut = null,
            locationId = 5L,
            lastMovedAt = "2026-01-30T12:00:00.000000Z",
            currentRoomId = 400L
        )
        val entity = dto.toEntity()
        // serverId uses id (the pivot id from server) when pivotId (from pivot_id key) is absent
        assertThat(entity.serverId).isEqualTo(6000L)
        assertThat(entity.catalogServerId).isEqualTo(7000L)
        assertThat(entity.catalogUuid).isEqualTo("cat-uuid")
        assertThat(entity.quantity).isEqualTo(3)
    }

    @Test
    fun `EquipmentDto toEntity preserves existing serverId when pivotId is absent`() {
        val existingEntity = com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity(
            equipmentId = 100L,
            serverId = 6000L,
            catalogServerId = 7000L,
            catalogUuid = "cat-uuid",
            uuid = "old-equipment-uuid",
            projectId = 100L,
            roomId = 400L,
            type = "Dehumidifier",
            status = "active"
        )
        val dto = EquipmentDto(
            id = 6000L,
            uuid = "pivot-uuid",
            projectId = 100L,
            roomId = 400L,
            type = "Dehumidifier",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = 1,
            status = "active",
            startDate = null,
            endDate = null,
            createdAt = null,
            updatedAt = null,
            pivotId = null,
            pivotUuid = null,
            equipmentId = 7000L,
            catalogUuid = "cat-uuid"
        )
        val entity = dto.toEntity(existingEntity)
        assertThat(entity.serverId).isEqualTo(6000L)
        assertThat(entity.catalogServerId).isEqualTo(7000L)
        assertThat(entity.equipmentId).isEqualTo(100L)
    }

    // ===== EquipmentMoveRequest =====

    @Test
    fun `EquipmentMoveRequest serializes with correct field names`() {
        val request = EquipmentMoveRequest(
            toRoomId = 401L,
            quantity = 2,
            movedAt = "2026-01-30T12:00:00.000000Z",
            note = "Moved for drying",
            idempotencyKey = "move-key",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"to_room_id\":401")
        assertThat(json).contains("\"quantity\":2")
        assertThat(json).contains("\"moved_at\":\"2026-01-30T12:00:00.000000Z\"")
        assertThat(json).contains("\"note\":\"Moved for drying\"")
        assertThat(json).contains("\"idempotency_key\":\"move-key\"")
        assertThat(json).contains("\"updated_at\":\"2026-01-30T12:00:00.000000Z\"")
    }

    @Test
    fun `EquipmentMoveRequest quantity is nullable for full-pivot moves`() {
        val json = """{"to_room_id":401,"moved_at":"2026-01-30T12:00:00.000000Z","updated_at":"2026-01-30T12:00:00.000000Z"}"""
        val request = gson.fromJson(json, EquipmentMoveRequest::class.java)
        assertThat(request.quantity).isNull()
        assertThat(request.toRoomId).isEqualTo(401L)
    }

    // ===== EquipmentTransferRequest =====

    @Test
    fun `EquipmentTransferRequest serializes with correct field names`() {
        val request = EquipmentTransferRequest(
            toRoomId = 500L,
            quantity = 3,
            movedAt = "2026-01-30T12:00:00.000000Z",
            idempotencyKey = "transfer-key",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )
        val json = gson.toJson(request)
        assertThat(json).contains("\"to_room_id\":500")
        assertThat(json).contains("\"quantity\":3")
        assertThat(json).contains("\"moved_at\":\"2026-01-30T12:00:00.000000Z\"")
        assertThat(json).contains("\"idempotency_key\":\"transfer-key\"")
        assertThat(json).contains("\"updated_at\":\"2026-01-30T12:00:00.000000Z\"")
    }

    @Test
    fun `EquipmentTransferRequest quantity is required (not nullable)`() {
        val json = """{"to_room_id":500,"quantity":3,"moved_at":"2026-01-30T12:00:00.000000Z","updated_at":"2026-01-30T12:00:00.000000Z"}"""
        val request = gson.fromJson(json, EquipmentTransferRequest::class.java)
        assertThat(request.quantity).isEqualTo(3)
    }

    // ===== EquipmentMovementDto =====

    @Test
    fun `EquipmentMovementDto parses full movement ledger response`() {
        val json = """{
            "id": 1,
            "uuid": "mov-uuid",
            "equipment_room_id": 6000,
            "equipment_id": 7000,
            "catalog_uuid": "cat-uuid",
            "from_room_id": 400,
            "from_location_id": 5,
            "to_room_id": 401,
            "to_location_id": 6,
            "from_project_id": 100,
            "to_project_id": 100,
            "quantity": 2,
            "moved_at": "2026-01-30T12:00:00.000000Z",
            "note": "Moved for drying",
            "moved_by_user_id": 1,
            "idempotency_key": "move-key",
            "created_at": "2026-01-30T12:00:00.000000Z",
            "updated_at": "2026-01-30T12:00:00.000000Z"
        }"""
        val dto = gson.fromJson(json, EquipmentMovementDto::class.java)
        assertThat(dto.id).isEqualTo(1L)
        assertThat(dto.uuid).isEqualTo("mov-uuid")
        assertThat(dto.equipmentRoomId).isEqualTo(6000L)
        assertThat(dto.equipmentId).isEqualTo(7000L)
        assertThat(dto.catalogUuid).isEqualTo("cat-uuid")
        assertThat(dto.fromRoomId).isEqualTo(400L)
        assertThat(dto.fromLocationId).isEqualTo(5L)
        assertThat(dto.toRoomId).isEqualTo(401L)
        assertThat(dto.toLocationId).isEqualTo(6L)
        assertThat(dto.quantity).isEqualTo(2)
        assertThat(dto.note).isEqualTo("Moved for drying")
        assertThat(dto.movedByUserId?.toLong()).isEqualTo(1L)
        assertThat(dto.idempotencyKey).isEqualTo("move-key")
    }

    @Test
    fun `EquipmentMovementDto tolerates null fields`() {
        val json = """{"id":1,"equipment_room_id":6000,"to_room_id":401,"quantity":2,"moved_at":"2026-01-30T12:00:00.000000Z"}"""
        val dto = gson.fromJson(json, EquipmentMovementDto::class.java)
        assertThat(dto.uuid).isNull()
        assertThat(dto.fromRoomId).isNull()
        assertThat(dto.fromProjectId).isNull()
    }
}

package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.util.Date

/**
 * Pure mapper coverage for the 2026-06 sync-fix batch:
 *  - RP-BUG-032: DamageMaterialDto.toMaterialEntity carries projectId.
 *  - RP-BUG-033: OfflineMoistureLogEntity.toRequest includes dryingGoal (wire key drying_goal).
 *  - Equipment pull: localProjectId/localRoomId override server IDs from DTO.
 */
class SyncEntityMappersTest {

    @Test
    fun `toMaterialEntity carries projectId from dto`() {
        val dto = DamageMaterialDto(
            id = 9L,
            uuid = "mat-uuid",
            projectId = 77L,
            roomId = 400L,
            title = "Drywall",
            description = "wet",
            severity = "high",
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )

        val entity = dto.toMaterialEntity()

        assertThat(entity.projectId).isEqualTo(77L)
        assertThat(entity.serverId).isEqualTo(9L)
    }

    @Test
    fun `toMaterialEntity tolerates null projectId`() {
        val dto = DamageMaterialDto(
            id = 9L,
            uuid = "mat-uuid",
            projectId = null,
            roomId = 400L,
            title = "Drywall",
            description = null,
            severity = null,
            createdAt = null,
            updatedAt = null
        )

        assertThat(dto.toMaterialEntity().projectId).isNull()
    }

    private fun moistureLog(dryingGoal: Double?) = OfflineMoistureLogEntity(
        uuid = "log-uuid",
        projectId = 100L,
        roomId = 400L,
        materialId = 9L,
        date = Date(0),
        moistureContent = 12.5,
        location = "wall",
        dryingGoal = dryingGoal
    )

    @Test
    fun `toRequest includes dryingGoal`() {
        assertThat(moistureLog(dryingGoal = 42.0).toRequest().dryingGoal).isEqualTo(42.0)
        assertThat(moistureLog(dryingGoal = null).toRequest().dryingGoal).isNull()
    }

    @Test
    fun `toRequest serializes dryingGoal as drying_goal`() {
        val json = Gson().toJson(moistureLog(dryingGoal = 42.0).toRequest())
        assertThat(json).contains("\"drying_goal\":42.0")
    }

    @Test
    fun `toEntity uses localProjectId and localRoomId instead of server IDs from DTO`() {
        val dto = EquipmentDto(
            id = 7001L,
            uuid = "pivot-uuid-001",
            equipmentId = 6001L,
            projectId = 1001L,
            roomId = 4001L,
            type = "Air Mover",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = 3,
            status = "active",
            startDate = "2026-01-15",
            endDate = null,
            createdAt = "2026-01-15T10:00:00.000000Z",
            updatedAt = "2026-01-15T10:00:00.000000Z"
        )
        val localProjectId = 50L
        val localRoomId = 25L

        val entity = dto.toEntity(
            existing = null,
            localProjectId = localProjectId,
            localRoomId = localRoomId
        )

        assertThat(entity.projectId).isEqualTo(localProjectId)
        assertThat(entity.roomId).isEqualTo(localRoomId)
        assertThat(entity.serverId).isEqualTo(7001L)
        assertThat(entity.catalogServerId).isEqualTo(6001L)
        assertThat(entity.uuid).isEqualTo("pivot-uuid-001")
        assertThat(entity.syncStatus).isEqualTo(SyncStatus.SYNCED)
        assertThat(entity.isDirty).isFalse()
    }

    @Test
    fun `toEntity falls back to DTO values when localProjectId and localRoomId are null`() {
        val dto = EquipmentDto(
            id = 7001L,
            uuid = "pivot-uuid-001",
            equipmentId = 6001L,
            projectId = 1001L,
            roomId = 4001L,
            type = "Air Mover",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = 3,
            status = "active",
            startDate = "2026-01-15",
            endDate = null,
            createdAt = "2026-01-15T10:00:00.000000Z",
            updatedAt = "2026-01-15T10:00:00.000000Z"
        )

        val entity = dto.toEntity(existing = null)

        assertThat(entity.projectId).isEqualTo(1001L)
        assertThat(entity.roomId).isEqualTo(4001L)
    }

    @Test
    fun `toEntity preserves existing entity local IDs when localProjectId and localRoomId are null`() {
        val dto = EquipmentDto(
            id = 7001L,
            uuid = "pivot-uuid-001",
            equipmentId = 6001L,
            projectId = 1001L,
            roomId = 4001L,
            type = "Air Mover",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = 3,
            status = "active",
            startDate = "2026-01-15",
            endDate = null,
            createdAt = "2026-01-15T10:00:00.000000Z",
            updatedAt = "2026-01-15T10:00:00.000000Z"
        )
        val existing = OfflineEquipmentEntity(
            equipmentId = 99L,
            serverId = 7001L,
            catalogServerId = 6001L,
            catalogUuid = "cat-uuid",
            uuid = "pivot-uuid-001",
            projectId = 50L,
            roomId = 25L,
            type = "Air Mover",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = 3,
            status = "active",
            syncStatus = SyncStatus.SYNCED,
            isDirty = true
        )

        val entity = dto.toEntity(existing = existing)

        assertThat(entity.projectId).isEqualTo(50L)
        assertThat(entity.roomId).isEqualTo(25L)
        assertThat(entity.equipmentId).isEqualTo(99L)
        assertThat(entity.uuid).isEqualTo("pivot-uuid-001")
        assertThat(entity.isDirty).isFalse()
    }
}

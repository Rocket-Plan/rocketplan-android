package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.util.Date

/**
 * Pure mapper coverage for the 2026-06 sync-fix batch:
 *  - RP-BUG-032: DamageMaterialDto.toMaterialEntity carries projectId.
 *  - RP-BUG-033: OfflineMoistureLogEntity.toRequest includes dryingGoal (wire key drying_goal).
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
}

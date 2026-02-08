package com.example.rocketplan_android.ui.rocketdry

data class AtmosphericLogItem(
    val logId: Long,
    val roomId: Long?,
    val roomName: String?,
    val dateTime: String,
    val humidity: Double,
    val temperature: Double,
    val pressure: Double,
    val windSpeed: Double,
    val isExternal: Boolean = false,
    val photoUrl: String? = null,
    val photoLocalPath: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class AtmosphericLogArea(
    val roomId: Long?,
    val label: String,
    val logCount: Int
)

data class LocationLevel(
    val levelName: String,
    val locations: List<LocationItem>
)

data class LocationItem(
    val roomId: Long,
    val name: String,
    val materialCount: Int,
    val iconRes: Int
)

data class EquipmentTotals(
    val total: Int,
    val active: Int,
    val removed: Int,
    val damaged: Int
)

data class EquipmentTypeSummary(
    val type: String,
    val label: String,
    val count: Int,
    val iconRes: Int
)

data class EquipmentRoomSummary(
    val roomId: Long?,
    val roomName: String,
    val summary: String
)

data class EquipmentLevel(
    val levelName: String,
    val rooms: List<EquipmentRoomSummary>
)

enum class DryingStatus { ON_TARGET, APPROACHING, IN_PROGRESS, FAR, UNKNOWN }

data class MaterialDryingGoalItem(
    val materialId: Long,
    val name: String,
    val targetMoisture: Double?,
    val latestReading: Double?,
    val lastUpdatedLabel: String?,
    val logsCount: Int,
    val dryingStatus: DryingStatus = DryingStatus.UNKNOWN
)

sealed class RocketDryUiState {
    object Loading : RocketDryUiState()
    data class Ready(
        val projectAddress: String,
        val latestExternalLog: AtmosphericLogItem?,
        val externalLogCount: Int,
        val locationLevels: List<LocationLevel>,
        val equipmentTotals: EquipmentTotals,
        val equipmentByType: List<EquipmentTypeSummary>,
        val equipmentLevels: List<EquipmentLevel>,
        val totalMoistureLogs: Int
    ) : RocketDryUiState()
}

sealed class RocketDryRoomUiState {
    object Loading : RocketDryRoomUiState()
    data class Ready(
        val projectAddress: String,
        val roomName: String,
        val roomIconRes: Int,
        val atmosphericLogCount: Int,
        val atmosphericLogs: List<AtmosphericLogItem>,
        val materialGoals: List<MaterialDryingGoalItem>
    ) : RocketDryRoomUiState()
}

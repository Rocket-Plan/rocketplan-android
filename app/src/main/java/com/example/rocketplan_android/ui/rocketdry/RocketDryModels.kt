package com.example.rocketplan_android.ui.rocketdry

data class AtmosphericLogItem(
    val roomId: Long?,
    val roomName: String?,
    val dateTime: String,
    val humidity: Double,
    val temperature: Double,
    val pressure: Double,
    val windSpeed: Double
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
    val roomName: String,
    val summary: String
)

data class EquipmentLevel(
    val levelName: String,
    val rooms: List<EquipmentRoomSummary>
)

data class MaterialDryingGoalItem(
    val materialId: Long,
    val name: String,
    val targetMoisture: Double?,
    val latestReading: Double?,
    val lastUpdatedLabel: String?,
    val logsCount: Int
)

sealed class RocketDryUiState {
    object Loading : RocketDryUiState()
    data class Ready(
        val projectAddress: String,
        val atmosphericLogs: List<AtmosphericLogItem>,
        val atmosphericAreas: List<AtmosphericLogArea>,
        val selectedAtmosphericRoomId: Long?,
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
        val materialGoals: List<MaterialDryingGoalItem>
    ) : RocketDryRoomUiState()
}

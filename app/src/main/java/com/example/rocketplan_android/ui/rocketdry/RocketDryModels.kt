package com.example.rocketplan_android.ui.rocketdry

data class AtmosphericLogItem(
    val dateTime: String,
    val humidity: Double,
    val temperature: Double,
    val pressure: Double,
    val windSpeed: Double
)

data class LocationLevel(
    val levelName: String,
    val locations: List<LocationItem>
)

data class LocationItem(
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

sealed class RocketDryUiState {
    object Loading : RocketDryUiState()
    data class Ready(
        val projectAddress: String,
        val atmosphericLogs: List<AtmosphericLogItem>,
        val locationLevels: List<LocationLevel>,
        val equipmentTotals: EquipmentTotals,
        val equipmentByType: List<EquipmentTypeSummary>,
        val equipmentLevels: List<EquipmentLevel>,
        val totalMoistureLogs: Int
    ) : RocketDryUiState()
}

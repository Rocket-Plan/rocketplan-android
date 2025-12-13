package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val DEFAULT_LEVEL_LABEL = "General"
private const val UNASSIGNED_LABEL = "Unassigned"

class RocketDryViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState = MutableStateFlow<RocketDryUiState>(RocketDryUiState.Loading)
    val uiState: StateFlow<RocketDryUiState> = _uiState
    private val selectedAtmosphericRoomId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeAtmosphericLogsForProject(projectId),
                localDataService.observeRooms(projectId),
                localDataService.observeLocations(projectId),
                localDataService.observeMoistureLogsForProject(projectId)
            ) { projects, atmosphericLogs, rooms, locations, moistureLogs ->
                Data(projects, atmosphericLogs, rooms, locations, moistureLogs)
            }.combine(selectedAtmosphericRoomId) { data, selectedRoomId ->
                data.copy(selectedAtmosphericRoomId = selectedRoomId)
            }.combine(localDataService.observeEquipmentForProject(projectId)) { data, equipment ->
                val project = data.projects.firstOrNull { it.projectId == projectId }
                android.util.Log.d("RocketDryVM", "🌡️ projectId=$projectId, atmosphericLogs.size=${data.atmosphericLogs.size}, projectIds=${data.atmosphericLogs.map { it.projectId }.distinct()}")
                if (project == null) {
                    RocketDryUiState.Loading
                } else {
                    val locationLookup = data.locations.associateBy { it.locationId }
                    val moistureByRoom = data.moistureLogs.groupBy { it.roomId }
                    val locationLevels = buildLocationLevels(data.rooms, locationLookup, moistureByRoom)
                    val equipmentByRoom = equipment.groupBy { it.roomId }
                    val roomsById = data.rooms.associateBy { it.roomId }
                    val atmosphericLogItems = data.atmosphericLogs.map { it.toUiItem(roomsById) }
                    val logsByRoom = atmosphericLogItems.groupBy { it.roomId }
                    val areaOptions = buildAtmosphericAreas(logsByRoom, roomsById)
                    val resolvedSelection = resolveAtmosphericSelection(
                        requestedRoomId = data.selectedAtmosphericRoomId,
                        availableRoomIds = logsByRoom.keys.toList(),
                        areaOptions = areaOptions
                    )
                    if (resolvedSelection != data.selectedAtmosphericRoomId) {
                        selectedAtmosphericRoomId.value = resolvedSelection
                    }
                    val atmosphericLogsToShow = logsByRoom[resolvedSelection].orEmpty()
                    RocketDryUiState.Ready(
                        projectAddress = buildProjectAddress(project),
                        atmosphericLogs = atmosphericLogsToShow,
                        atmosphericAreas = areaOptions,
                        selectedAtmosphericRoomId = resolvedSelection,
                        locationLevels = locationLevels,
                        equipmentTotals = buildEquipmentTotals(equipment),
                        equipmentByType = buildEquipmentTypeSummaries(equipment),
                        equipmentLevels = buildEquipmentLevels(data.rooms, locationLookup, equipmentByRoom),
                        totalMoistureLogs = data.moistureLogs.size
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private data class Data(
        val projects: List<OfflineProjectEntity>,
        val atmosphericLogs: List<OfflineAtmosphericLogEntity>,
        val rooms: List<OfflineRoomEntity>,
        val locations: List<OfflineLocationEntity>,
        val moistureLogs: List<OfflineMoistureLogEntity>,
        val selectedAtmosphericRoomId: Long? = null
    )

    fun selectAtmosphericRoom(roomId: Long?) {
        android.util.Log.d("RocketDryVM", "🎛 selectAtmosphericRoom roomId=$roomId")
        selectedAtmosphericRoomId.value = roomId
    }

    fun addExternalAtmosphericLog(
        humidity: Double,
        temperature: Double,
        pressure: Double,
        windSpeed: Double,
        roomId: Long?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = Date()
            val log = OfflineAtmosphericLogEntity(
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                date = now,
                relativeHumidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                isExternal = true,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            android.util.Log.d(
                "RocketDryVM",
                "🧪 Saving external atmospheric log: uuid=${log.uuid} projectId=$projectId roomId=$roomId rh=$humidity temp=$temperature pressure=$pressure wind=$windSpeed"
            )
            runCatching { localDataService.saveAtmosphericLogs(listOf(log)) }
                .onSuccess {
                    android.util.Log.d(
                        "RocketDryVM",
                        "✅ External atmospheric log saved: uuid=${log.uuid}"
                    )
                }
                .onFailure {
                    android.util.Log.e(
                        "RocketDryVM",
                        "❌ Failed to save external atmospheric log uuid=${log.uuid}",
                        it
                    )
                }
        }
    }

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return listOfNotNull(address, title, alias).firstOrNull()
            ?: "Project ${project.projectId}"
    }

    private fun buildLocationLevels(
        rooms: List<OfflineRoomEntity>,
        locations: Map<Long, OfflineLocationEntity>,
        moistureByRoom: Map<Long, List<OfflineMoistureLogEntity>>
    ): List<LocationLevel> {
        val roomsByLevel = rooms.groupBy { room ->
            resolveLevelName(room, locations)
        }
        return roomsByLevel.map { (levelName, levelRooms) ->
            val roomItems = levelRooms.map { room ->
                LocationItem(
                    roomId = room.roomId,
                    name = room.title,
                    materialCount = moistureByRoom[room.roomId]?.size ?: 0,
                    iconRes = resolveRoomIcon(room)
                )
            }.sortedBy { it.name.lowercase(Locale.getDefault()) }
            LocationLevel(levelName = levelName, locations = roomItems)
        }.sortedBy { it.levelName.lowercase(Locale.getDefault()) }
    }

    private fun resolveRoomIcon(room: OfflineRoomEntity): Int {
        val iconName = room.roomType?.takeIf { it.isNotBlank() } ?: room.title
        return RoomTypeCatalog.resolveIconRes(rocketPlanApp, room.roomTypeId, iconName)
    }

    private fun OfflineAtmosphericLogEntity.toUiItem(
        rooms: Map<Long, OfflineRoomEntity>
    ): AtmosphericLogItem =
        AtmosphericLogItem(
            roomId = roomId,
            roomName = roomId?.let { rooms[it]?.title },
            dateTime = formatDateTime(date),
            humidity = relativeHumidity,
            temperature = temperature,
            pressure = pressure ?: 0.0,
            windSpeed = windSpeed ?: 0.0
        )

    private fun buildAtmosphericAreas(
        logsByRoom: Map<Long?, List<AtmosphericLogItem>>,
        rooms: Map<Long, OfflineRoomEntity>
    ): List<AtmosphericLogArea> {
        if (logsByRoom.isEmpty()) return emptyList()
        return logsByRoom.entries.map { (roomId, logs) ->
            val roomLabel = roomId?.let { id ->
                rooms[id]?.title?.takeIf { it.isNotBlank() }
            }
            val label = when {
                roomId == null -> rocketPlanApp.getString(R.string.rocketdry_atmos_room_external)
                roomLabel != null -> roomLabel
                else -> rocketPlanApp.getString(R.string.rocketdry_atmos_room_unknown)
            }
            AtmosphericLogArea(
                roomId = roomId,
                label = label,
                logCount = logs.size
            )
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun resolveAtmosphericSelection(
        requestedRoomId: Long?,
        availableRoomIds: List<Long?>,
        areaOptions: List<AtmosphericLogArea>
    ): Long? {
        if (availableRoomIds.isEmpty()) return requestedRoomId
        if (requestedRoomId in availableRoomIds) return requestedRoomId
        if (availableRoomIds.contains(null)) return null
        return areaOptions.firstOrNull()?.roomId ?: availableRoomIds.first()
    }

    private fun buildEquipmentTotals(equipment: List<OfflineEquipmentEntity>): EquipmentTotals {
        val total = equipment.sumOf { it.quantity }
        val active = equipment.filter { it.status.equals("active", ignoreCase = true) }
            .sumOf { it.quantity }
        val removed = equipment.filter { it.status.equals("removed", ignoreCase = true) }
            .sumOf { it.quantity }
        val damaged = equipment.filter { it.status.equals("damaged", ignoreCase = true) }
            .sumOf { it.quantity }
        return EquipmentTotals(
            total = total,
            active = active,
            removed = removed,
            damaged = damaged
        )
    }

    private fun buildEquipmentTypeSummaries(
        equipment: List<OfflineEquipmentEntity>
    ): List<EquipmentTypeSummary> {
        val grouped = equipment.groupBy { normalizeType(it.type) }
        return grouped.map { (typeKey, items) ->
            val meta = equipmentMeta(typeKey)
            EquipmentTypeSummary(
                type = typeKey,
                label = meta.label,
                count = items.sumOf { it.quantity },
                iconRes = meta.iconRes
            )
        }.sortedWith(
            compareByDescending<EquipmentTypeSummary> { it.count }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    private fun buildEquipmentLevels(
        rooms: List<OfflineRoomEntity>,
        locations: Map<Long, OfflineLocationEntity>,
        equipmentByRoom: Map<Long?, List<OfflineEquipmentEntity>>
    ): List<EquipmentLevel> {
        val roomIds = rooms.map { it.roomId }.toSet()
        val roomSummaries = rooms.map { room ->
            val roomEquipment = equipmentByRoom[room.roomId].orEmpty()

            val levelName = resolveLevelName(room, locations)
            levelName to EquipmentRoomSummary(
                roomName = room.title,
                summary = buildEquipmentSummaryText(roomEquipment)
            )
        }.toMutableList()

        val orphanedEquipment = equipmentByRoom
            .filterKeys { key -> key != null && key !in roomIds }
            .values
            .flatten()
        val unassignedEquipment = equipmentByRoom[null].orEmpty() + orphanedEquipment
        if (unassignedEquipment.isNotEmpty()) {
            roomSummaries.add(
                UNASSIGNED_LABEL to EquipmentRoomSummary(
                    roomName = UNASSIGNED_LABEL,
                    summary = buildEquipmentSummaryText(unassignedEquipment)
                )
            )
        }

        val groupedByLevel = roomSummaries.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        )

        return groupedByLevel.map { (levelName, roomsForLevel) ->
            EquipmentLevel(
                levelName = levelName,
                rooms = roomsForLevel.sortedBy { it.roomName.lowercase(Locale.getDefault()) }
            )
        }.sortedBy { it.levelName.lowercase(Locale.getDefault()) }
    }

    private fun buildEquipmentSummaryText(items: List<OfflineEquipmentEntity>): String {
        val summary = items
            .groupBy { normalizeType(it.type) }
            .map { (typeKey, groupedItems) ->
                val count = groupedItems.sumOf { it.quantity }
                val label = equipmentMeta(typeKey).label
                formatEquipmentCount(count, label)
            }
            .sortedBy { it.lowercase(Locale.getDefault()) }
            .joinToString(separator = ", ")
        return summary.ifBlank { rocketPlanApp.getString(R.string.rocketdry_no_equipment) }
    }

    private fun resolveLevelName(
        room: OfflineRoomEntity,
        locations: Map<Long, OfflineLocationEntity>
    ): String {
        return room.level?.takeIf { it.isNotBlank() }
            ?: room.locationId?.let { locations[it]?.title }
            ?: DEFAULT_LEVEL_LABEL
    }

    private fun normalizeType(type: String?): String =
        type?.trim()
            ?.lowercase(Locale.getDefault())
            ?.replace(" ", "_")
            ?.replace("-", "_")
            ?: "equipment"

    private fun equipmentMeta(typeKey: String): EquipmentTypeMeta =
        when (typeKey) {
            "dehumidifier", "dehumidifiers" ->
                EquipmentTypeMeta("Dehumidifier", R.drawable.ic_water_drop)

            "air_mover", "air_movers" ->
                EquipmentTypeMeta("Air Mover", R.drawable.ic_material)

            "air_scrubber", "air_scrubbers" ->
                EquipmentTypeMeta("Air Scrubber", R.drawable.ic_material)

            "inject_drier", "injectidrier", "inject_dryers" ->
                EquipmentTypeMeta("Injectidrier", R.drawable.ic_material)

            "drying_mat", "drying_mats" ->
                EquipmentTypeMeta("Drying Mat", R.drawable.ic_material)

            else -> {
                val label = typeKey.replace("_", " ")
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                    }
                EquipmentTypeMeta(label.ifBlank { "Equipment" }, R.drawable.ic_material)
            }
        }

    private fun formatEquipmentCount(count: Int, label: String): String {
        val base = label.trim().ifBlank { "Equipment" }
        val needsPlural = count != 1 && !base.endsWith("s", ignoreCase = true)
        val finalLabel = if (needsPlural) "$base" + "s" else base
        return "$count $finalLabel"
    }

    private data class EquipmentTypeMeta(
        val label: String,
        val iconRes: Int
    )

    private fun formatDateTime(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RocketDryViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return RocketDryViewModel(application, projectId) as T
            }
        }
    }
}

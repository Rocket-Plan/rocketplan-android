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
import kotlinx.coroutines.withContext
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
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

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

    suspend fun renameAtmosphericArea(roomId: Long, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalized = newName.trim()
            if (normalized.isEmpty()) {
                android.util.Log.w("RocketDryVM", "⚠️ Cannot rename area; blank name provided")
                return@withContext false
            }
            val room = localDataService.getRoom(roomId)
                ?: localDataService.getRoomByServerId(roomId)
            if (room == null) {
                android.util.Log.w(
                    "RocketDryVM",
                    "⚠️ Cannot rename area; roomId=$roomId not found"
                )
                return@withContext false
            }

            val updated = room.copy(
                title = normalized,
                updatedAt = Date(),
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveRooms(listOf(updated))
            android.util.Log.d(
                "RocketDryVM",
                "✏️ Renamed atmospheric area roomId=$roomId to '$normalized'"
            )
            true
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
                logId = -System.currentTimeMillis(),
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
                    // Enqueue for sync to server
                    runCatching {
                        // Re-fetch the saved log to get the generated logId
                        val savedLog = localDataService.getAtmosphericLogByUuid(log.uuid)
                        if (savedLog != null) {
                            offlineSyncRepository.enqueueAtmosphericLogSync(savedLog)
                            android.util.Log.d(
                                "RocketDryVM",
                                "📤 External atmospheric log enqueued for sync: uuid=${log.uuid}"
                            )
                        }
                    }.onFailure {
                        android.util.Log.e(
                            "RocketDryVM",
                            "⚠️ Failed to enqueue atmospheric log for sync uuid=${log.uuid}",
                            it
                        )
                    }
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
        // Only include rooms that actually have atmospheric logs
        val roomIdsWithLogs = logsByRoom.keys.filterNotNull().toSet()

        val areas = roomIdsWithLogs.map { roomId ->
            val roomLabel = rooms[roomId]?.title?.takeIf { it.isNotBlank() }
            val label = roomLabel ?: rocketPlanApp.getString(R.string.rocketdry_atmos_room_unknown)
            AtmosphericLogArea(
                roomId = roomId,
                label = label,
                logCount = logsByRoom[roomId]?.size ?: 0
            )
        }.toMutableList()

        val externalCount = logsByRoom[null]?.size ?: 0
        areas.add(
            AtmosphericLogArea(
                roomId = null,
                label = rocketPlanApp.getString(R.string.rocketdry_atmos_room_external),
                logCount = externalCount
            )
        )

        return areas.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun resolveAtmosphericSelection(
        requestedRoomId: Long?,
        areaOptions: List<AtmosphericLogArea>
    ): Long? {
        if (areaOptions.isEmpty()) return requestedRoomId
        if (requestedRoomId != null && areaOptions.any { it.roomId == requestedRoomId }) {
            return requestedRoomId
        }

        val hasAnyLogs = areaOptions.any { it.logCount > 0 }
        if (!hasAnyLogs) {
            return areaOptions.firstOrNull { it.roomId == null }?.roomId
                ?: requestedRoomId
        }

        val areaWithLogs = areaOptions.firstOrNull { it.logCount > 0 }
        return areaWithLogs?.roomId ?: areaOptions.first().roomId
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
        val grouped = equipment.groupBy { EquipmentTypeMapper.normalize(it.type) }
        return grouped.map { (typeKey, items) ->
            val meta = EquipmentTypeMapper.metaFor(typeKey)
            EquipmentTypeSummary(
                type = meta.key,
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
                roomId = room.roomId,
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
                    roomId = null,
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
            .groupBy { EquipmentTypeMapper.normalize(it.type) }
            .map { (typeKey, groupedItems) ->
                val count = groupedItems.sumOf { it.quantity }
                val label = EquipmentTypeMapper.metaFor(typeKey).label
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

    private fun formatEquipmentCount(count: Int, label: String): String {
        val base = label.trim().ifBlank { "Equipment" }
        val needsPlural = count != 1 && !base.endsWith("s", ignoreCase = true)
        val finalLabel = if (needsPlural) "$base" + "s" else base
        return "$count $finalLabel"
    }

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

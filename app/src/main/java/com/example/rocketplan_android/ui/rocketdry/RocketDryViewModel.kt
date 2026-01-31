package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import android.net.Uri
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
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_LEVEL_LABEL = "General"
private const val UNASSIGNED_LABEL = "Unassigned"

enum class RocketDryTab {
    EQUIPMENT,
    MOISTURE
}

class RocketDryViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository

    private val _uiState = MutableStateFlow<RocketDryUiState>(RocketDryUiState.Loading)
    val uiState: StateFlow<RocketDryUiState> = _uiState

    private val _currentTab = MutableStateFlow<RocketDryTab?>(null)
    val currentTab: StateFlow<RocketDryTab?> = _currentTab

    fun setCurrentTab(tab: RocketDryTab) {
        _currentTab.value = tab
    }

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

                    // Filter for external logs only (roomId == null, isExternal = true)
                    val externalLogs = data.atmosphericLogs
                        .filter { it.roomId == null && it.isExternal && !it.isDeleted }
                        .sortedByDescending { it.date.time }
                    val latestExternalLog = externalLogs.firstOrNull()?.toUiItem(roomsById)
                    val externalLogCount = externalLogs.size

                    RocketDryUiState.Ready(
                        projectAddress = buildProjectAddress(project),
                        latestExternalLog = latestExternalLog,
                        externalLogCount = externalLogCount,
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

    fun addExternalAtmosphericLog(
        humidity: Double,
        temperature: Double,
        pressure: Double,
        windSpeed: Double,
        photoLocalPath: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = Date()
            val hasPhoto = !photoLocalPath.isNullOrBlank()
            val logUuid = UuidUtils.generateUuidV7()

            // Create assembly at photo capture time (before log syncs)
            var photoAssemblyId: String? = null
            if (hasPhoto && photoLocalPath != null) {
                val filename = "atmos_${logUuid}_${System.currentTimeMillis()}.jpg"
                val fileToUpload = FileToUpload(
                    uri = Uri.parse(photoLocalPath),
                    filename = filename,
                    deleteOnCompletion = false
                )
                val assemblyResult = imageProcessorRepository.createAssembly(
                    roomId = null,
                    projectId = projectId,
                    filesToUpload = listOf(fileToUpload),
                    templateId = "atmospheric_log",
                    entityType = "AtmosphericLog", // Must match iOS/server expected format
                    entityId = null,
                    entityUuid = logUuid
                )
                assemblyResult.onSuccess { assemblyId ->
                    photoAssemblyId = assemblyId
                    android.util.Log.d(
                        "RocketDryVM",
                        "📸 Created WAITING_FOR_ENTITY assembly for atmospheric log photo: assemblyId=$assemblyId logUuid=$logUuid"
                    )
                }.onFailure { error ->
                    android.util.Log.e(
                        "RocketDryVM",
                        "❌ Failed to create assembly for atmospheric log photo: logUuid=$logUuid",
                        error
                    )
                }
            }

            val log = OfflineAtmosphericLogEntity(
                logId = -System.currentTimeMillis(),
                uuid = logUuid,
                projectId = projectId,
                roomId = null,
                date = now,
                relativeHumidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                isExternal = true,
                photoLocalPath = if (hasPhoto) photoLocalPath else null,
                photoUploadStatus = if (photoAssemblyId != null) "queued" else "none",
                photoAssemblyId = photoAssemblyId,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            android.util.Log.d(
                "RocketDryVM",
                "🧪 Saving external atmospheric log: uuid=${log.uuid} projectId=$projectId assemblyId=$photoAssemblyId"
            )
            runCatching { localDataService.saveAtmosphericLogs(listOf(log)) }
                .onSuccess {
                    android.util.Log.d(
                        "RocketDryVM",
                        "✅ External atmospheric log saved: uuid=${log.uuid}"
                    )
                    runCatching {
                        val savedLog = localDataService.getAtmosphericLogByUuid(log.uuid)
                        if (savedLog != null) {
                            offlineSyncRepository.enqueueAtmosphericLogSync(savedLog)
                        }
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

    private data class Data(
        val projects: List<OfflineProjectEntity>,
        val atmosphericLogs: List<OfflineAtmosphericLogEntity>,
        val rooms: List<OfflineRoomEntity>,
        val locations: List<OfflineLocationEntity>,
        val moistureLogs: List<OfflineMoistureLogEntity>
    )

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
            logId = logId,
            roomId = roomId,
            roomName = roomId?.let { rooms[it]?.title },
            dateTime = formatDateTime(date),
            humidity = relativeHumidity,
            temperature = temperature,
            pressure = pressure ?: 0.0,
            windSpeed = windSpeed ?: 0.0,
            isExternal = isExternal,
            photoUrl = photoUrl,
            photoLocalPath = photoLocalPath,
            createdAt = formatDateTime(createdAt),
            updatedAt = formatDateTime(updatedAt)
        )

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

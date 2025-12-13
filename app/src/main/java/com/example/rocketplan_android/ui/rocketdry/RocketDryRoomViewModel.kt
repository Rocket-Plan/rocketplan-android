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
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import com.example.rocketplan_android.util.parseTargetMoisture
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

class RocketDryRoomViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState = MutableStateFlow<RocketDryRoomUiState>(RocketDryRoomUiState.Loading)
    val uiState: StateFlow<RocketDryRoomUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observeMaterials(),
                localDataService.observeMoistureLogsForRoom(roomId),
                localDataService.observeAtmosphericLogsForRoom(roomId)
            ) { projects, rooms, materials, moistureLogs, atmosphericLogs ->
                Data(projects, rooms, materials, moistureLogs, atmosphericLogs)
            }.collect { data ->
                android.util.Log.d("RocketDryRoomVM", "🌡️ Observing: projectId=$projectId, roomId=$roomId, atmosphericLogs=${data.atmosphericLogs.size}, logRoomIds=${data.atmosphericLogs.map { it.roomId }}")
                val project = data.projects.firstOrNull { it.projectId == projectId }
                val room = data.rooms.firstOrNull { it.roomId == roomId }
                if (project == null || room == null) {
                    _uiState.value = RocketDryRoomUiState.Loading
                } else {
                    _uiState.value = RocketDryRoomUiState.Ready(
                        projectAddress = buildProjectAddress(project),
                        roomName = room.title,
                        roomIconRes = resolveRoomIcon(room),
                        atmosphericLogCount = data.atmosphericLogs.size,
                        materialGoals = buildMaterialGoals(
                            materials = resolveMaterialsForLogs(data.materials, data.moistureLogs),
                            moistureLogs = data.moistureLogs
                        )
                    )
                }
            }
        }
    }

    private data class Data(
        val projects: List<OfflineProjectEntity>,
        val rooms: List<OfflineRoomEntity>,
        val materials: List<OfflineMaterialEntity>,
        val moistureLogs: List<OfflineMoistureLogEntity>,
        val atmosphericLogs: List<OfflineAtmosphericLogEntity>
    )

    fun addRoomAtmosphericLog(
        humidity: Double,
        temperature: Double,
        pressure: Double,
        windSpeed: Double
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
                isExternal = false,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            android.util.Log.d(
                "RocketDryRoomVM",
                "🌡️ Adding atmospheric log: uuid=${log.uuid}, projectId=$projectId, roomId=$roomId, humidity=$humidity"
            )
            runCatching { localDataService.saveAtmosphericLogs(listOf(log)) }
                .onSuccess {
                    android.util.Log.d(
                        "RocketDryRoomVM",
                        "✅ Saved atmospheric log for roomId=$roomId uuid=${log.uuid}"
                    )
                }
                .onFailure {
                    android.util.Log.e(
                        "RocketDryRoomVM",
                        "❌ Failed to save atmospheric log for roomId=$roomId uuid=${log.uuid}",
                        it
                    )
                }
        }
    }

    suspend fun addMaterialDryingGoal(
        name: String,
        targetMoisture: Double?
    ): Boolean = withContext(Dispatchers.IO) {
        val now = Date()
        val materialUuid = UUID.randomUUID().toString()
        val materialName = name.trim().ifBlank {
            rocketPlanApp.getString(R.string.rocketdry_material_fallback_name)
        }
        android.util.Log.d(
            "RocketDryRoomVM",
            "🎯 Creating material goal materialUuid=$materialUuid name='$materialName' targetMoisture=$targetMoisture roomId=$roomId"
        )
        val material = OfflineMaterialEntity(
            uuid = materialUuid,
            name = materialName,
            description = targetMoisture?.let {
                rocketPlanApp.getString(
                    R.string.rocketdry_material_goal_description,
                    it
                )
            },
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = null
        )
        localDataService.saveMaterials(listOf(material))
        val savedMaterial = localDataService.getMaterialByUuid(materialUuid)
            ?: run {
                android.util.Log.e(
                    "RocketDryRoomVM",
                    "❌ Failed to load material after insert: uuid=$materialUuid"
                )
                return@withContext false
            }

        // Anchor the material to the room with a goal log so it appears immediately.
        val log = OfflineMoistureLogEntity(
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            roomId = roomId,
            materialId = savedMaterial.materialId,
            date = now,
            moistureContent = targetMoisture ?: 0.0,
            location = rocketPlanApp.getString(R.string.rocketdry_material_goal_location),
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false
        )
        runCatching { localDataService.saveMoistureLogs(listOf(log)) }
            .onFailure {
                android.util.Log.e(
                    "RocketDryRoomVM",
                    "❌ Failed to save material drying goal log uuid=${log.uuid}",
                    it
                )
                return@withContext false
            }
        true
    }

    suspend fun addMaterialMoistureLog(
        materialId: Long,
        moistureContent: Double,
        location: String?
    ): Boolean = withContext(Dispatchers.IO) {
        val material = localDataService.getMaterial(materialId) ?: return@withContext false
        val now = Date()
        val log = OfflineMoistureLogEntity(
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            roomId = roomId,
            materialId = material.materialId,
            date = now,
            moistureContent = moistureContent,
            location = location ?: rocketPlanApp.getString(R.string.rocketdry_material_reading_location),
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false
        )
        localDataService.saveMoistureLogs(listOf(log))
        true
    }

    private fun resolveMaterialsForLogs(
        materials: List<OfflineMaterialEntity>,
        moistureLogs: List<OfflineMoistureLogEntity>
    ): Map<Long, OfflineMaterialEntity> {
        if (materials.isEmpty() || moistureLogs.isEmpty()) return emptyMap()
        val materialIds = moistureLogs.map { it.materialId }.toSet()
        return materials
            .filter { material ->
                material.materialId in materialIds || (material.serverId != null && material.serverId in materialIds)
            }
            .associateBy { it.materialId }
    }

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return listOfNotNull(address, title, alias).firstOrNull()
            ?: "Project ${project.projectId}"
    }

    private fun resolveRoomIcon(room: OfflineRoomEntity): Int {
        val iconName = room.roomType?.takeIf { it.isNotBlank() } ?: room.title
        return RoomTypeCatalog.resolveIconRes(rocketPlanApp, room.roomTypeId, iconName)
    }

    private fun buildMaterialGoals(
        materials: Map<Long, OfflineMaterialEntity>,
        moistureLogs: List<OfflineMoistureLogEntity>
    ): List<MaterialDryingGoalItem> {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val goalLocation = rocketPlanApp.getString(R.string.rocketdry_material_goal_location)
        val logsByMaterial = moistureLogs.groupBy { it.materialId }
        val materialsByServerId = materials.values
            .filter { it.serverId != null }
            .associateBy { it.serverId }

        val allMaterialIds = logsByMaterial.keys
        return allMaterialIds.distinct().mapNotNull { materialId ->
            val material = materials[materialId]
                ?: materialsByServerId[materialId]
            val materialName = material?.name?.takeIf { it.isNotBlank() }
                ?: rocketPlanApp.getString(R.string.rocketdry_material_fallback_name)
            val logs = logsByMaterial[materialId].orEmpty()
            val target = material?.description?.let { parseTargetMoisture(it) }
                ?: logs.firstOrNull { it.location == goalLocation }?.moistureContent
            val readingLogs = logs.filterNot { it.location == goalLocation }
            val latestReading = readingLogs.maxByOrNull { it.date.time }
            MaterialDryingGoalItem(
                materialId = materialId,
                name = materialName,
                targetMoisture = target,
                latestReading = latestReading?.moistureContent,
                lastUpdatedLabel = latestReading?.let { latest ->
                    formatter.format(latest.date)
                        .replace("AM", "am")
                        .replace("PM", "pm")
                },
                logsCount = readingLogs.size
            )
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RocketDryRoomViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return RocketDryRoomViewModel(application, projectId, roomId) as T
            }
        }
    }
}
